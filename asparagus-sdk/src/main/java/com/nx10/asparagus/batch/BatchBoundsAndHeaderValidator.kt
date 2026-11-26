package com.nx10.asparagus.batch

import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.Carried
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.ContactBefore
import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.GameEvent
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.contract.Hold
import com.nx10.asparagus.contract.OpenSpan
import com.nx10.asparagus.contract.OpenSpans
import com.nx10.asparagus.contract.SensorBlock
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.SpanMarker
import com.nx10.asparagus.contract.State
import com.nx10.asparagus.contract.StateKind
import com.nx10.asparagus.contract.StateRecord
import com.nx10.asparagus.contract.Touch
import com.nx10.asparagus.contract.TouchContact
import com.nx10.asparagus.contract.TouchEnd
import com.nx10.asparagus.contract.TouchHold
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.internal.util.Utf8Bounds

/**
 * Enforces all 6 intake `Header` rules (`BUILDING_AN_SDK.md` line 187), all W8-26 inclusive bounds
 * (`BUILDING_AN_SDK.md` lines 233-256), the `CLOCK_SENSOR` -> `clocks.sensorUs` invariant, and
 * deterministic post-flush batch splitting so no valid telemetry is refused by intake or collector.
 */
object BatchBoundsAndHeaderValidator {
    const val MAX_UPLOAD_BYTES: Int = 262_144
    const val MAX_UNZIPPED_BATCH_BYTES: Int = 262_144
    const val MAX_WINDOW_LENGTH_S: Int = 150
    const val MAX_GAME_EVENTS: Int = 750
    const val MAX_GAP_RECORDS: Int = 750
    const val MAX_MOTION_SAMPLES: Int = 7_500
    const val MAX_TOUCH_CONTACTS: Int = 750
    const val MAX_SCREEN_CHANGES: Int = 750
    const val MAX_TOUCH_SAMPLES: Int = 7_500
    const val MAX_TABLE_ROWS: Int = 750
    const val MAX_TOTAL_ROW_BYTES: Long = 2_500_000L

    // Proactive soft limits to close windows before in-flight samples cross W8-26 bounds.
    const val PROACTIVE_MOTION_SAMPLE_LIMIT: Int = 7_000
    const val PROACTIVE_TOUCH_SAMPLE_LIMIT: Int = 7_000

    private const val MAX_SPLIT_DEPTH: Int = 16
    private const val PROBE_SEQUENCE: Long = Long.MAX_VALUE

    /**
     * Checks all 6 intake Header rules (`BUILDING_AN_SDK.md` line 187).
     * Returns an empty list when valid, or human-readable fault descriptions.
     */
    fun validateHeader(header: Header?): List<String> {
        if (header == null) return listOf("Header is missing.")
        val faults = mutableListOf<String>()

        checkRequiredId(faults, "id_org", header.idOrg)
        checkRequiredId(faults, "id_deployment", header.idDeployment)
        checkRequiredId(faults, "id_source", header.idSource)
        checkRequiredId(faults, "id_session", header.idSession)

        if (header.sequence < 1L) {
            faults.add("Header.sequence must be >= 1 (got ${header.sequence}).")
        }
        if (header.schemaVersion != ASPARAGUS_SCHEMA_VERSION) {
            faults.add("Header.schema_version must be $ASPARAGUS_SCHEMA_VERSION (got ${header.schemaVersion}).")
        }
        if (header.windowStartUs % 1_000_000L != 0L) {
            faults.add("Header.window_start_us must lie on a whole second (got ${header.windowStartUs}).")
        }
        if (header.windowLengthS !in 1..MAX_WINDOW_LENGTH_S) {
            faults.add("Header.window_length_s must be in 1..$MAX_WINDOW_LENGTH_S (got ${header.windowLengthS}).")
        }

        checkBoundedText(faults, "os_version", header.osVersion)
        checkBoundedText(faults, "device_model", header.deviceModel)
        checkBoundedText(faults, "settings_version", header.settingsVersion)

        return faults
    }

    /**
     * Checks all W8-26 inclusive bounds plus the `CLOCK_SENSOR` -> `clocks.sensorUs` invariant.
     */
    fun validateBounds(
        batch: Batch,
        rawProtoBytes: ByteArray,
        wireBytes: ByteArray,
    ): List<String> {
        val faults = mutableListOf<String>()
        val header = batch.header

        if (wireBytes.size > MAX_UPLOAD_BYTES) {
            faults.add("Upload wire bytes ${wireBytes.size} exceed $MAX_UPLOAD_BYTES.")
        }
        if (rawProtoBytes.size > MAX_UNZIPPED_BATCH_BYTES) {
            faults.add("Unzipped batch bytes ${rawProtoBytes.size} exceed $MAX_UNZIPPED_BATCH_BYTES.")
        }
        if (header != null && header.windowLengthS !in 1..MAX_WINDOW_LENGTH_S) {
            faults.add("window_length_s ${header.windowLengthS} outside 1..$MAX_WINDOW_LENGTH_S.")
        }
        if (batch.events.size > MAX_GAME_EVENTS) {
            faults.add("Game events ${batch.events.size} exceed $MAX_GAME_EVENTS.")
        }
        if (batch.gaps.size > MAX_GAP_RECORDS) {
            faults.add("Gap records ${batch.gaps.size} exceed $MAX_GAP_RECORDS.")
        }

        val motionSamples = motionSampleCount(batch)
        if (motionSamples > MAX_MOTION_SAMPLES) {
            faults.add("Motion samples $motionSamples exceed $MAX_MOTION_SAMPLES.")
        }

        val touchContacts = touchContactCount(batch)
        if (touchContacts > MAX_TOUCH_CONTACTS) {
            faults.add("Touch contacts $touchContacts exceed $MAX_TOUCH_CONTACTS.")
        }

        if (batch.screenChanges.size > MAX_SCREEN_CHANGES) {
            faults.add("Screen changes ${batch.screenChanges.size} exceed $MAX_SCREEN_CHANGES.")
        }

        val touchSamples = touchSampleCount(batch)
        if (touchSamples > MAX_TOUCH_SAMPLES) {
            faults.add("Touch samples $touchSamples exceed $MAX_TOUCH_SAMPLES.")
        }

        val maxRows = maxSingleTableRows(batch)
        if (maxRows > MAX_TABLE_ROWS) {
            faults.add("Single-table row count $maxRows exceeds $MAX_TABLE_ROWS.")
        }

        val estRowBytes = estimateTotalRowBytes(batch, rawProtoBytes.size)
        if (estRowBytes > MAX_TOTAL_ROW_BYTES) {
            faults.add("Estimated total row bytes $estRowBytes exceed $MAX_TOTAL_ROW_BYTES.")
        }

        val usesSensorClock = batch.sensorBlocks.any { it.clock == Clock.CLOCK_SENSOR } ||
            batch.gaps.any { it.clock == Clock.CLOCK_SENSOR }
        if (usesSensorClock && header?.clocks?.sensorUs == null) {
            faults.add("Batch references CLOCK_SENSOR but Header.clocks.sensor_us is absent.")
        }

        return faults
    }

    fun motionSampleCount(batch: Batch): Int =
        batch.sensorBlocks.sumOf { it.sampleCount }

    fun touchContactCount(batch: Batch): Int =
        batch.touch?.contacts?.size ?: 0

    fun touchSampleCount(batch: Batch): Int =
        batch.touch?.contacts?.sumOf { contactSampleCount(it) } ?: 0

    fun sensorSecondsRowCount(batch: Batch): Int {
        val maxRowsPerBlock = (batch.header?.windowLengthS ?: 1).coerceAtLeast(1) + 1
        return batch.sensorBlocks.sumOf { minOf(it.sampleCount, maxRowsPerBlock) }
    }

    fun touchSecondsRowCount(batch: Batch): Int {
        val maxRowsPerContact = (batch.header?.windowLengthS ?: 1).coerceAtLeast(1) + 1
        return batch.touch?.contacts?.sumOf { minOf(contactSampleCount(it), maxRowsPerContact) } ?: 0
    }

    fun maxSingleTableRows(batch: Batch): Int = maxOf(
        sensorSecondsRowCount(batch),
        touchSecondsRowCount(batch),
        batch.events.size,
        batch.gaps.size,
        batch.stateRecords.size,
        batch.screenChanges.size,
        batch.lifecycle.size,
        batch.diagnostics.size,
    )

    fun estimateTotalRowBytes(batch: Batch, rawProtoBytesSize: Int): Long =
        rawProtoBytesSize.toLong() * 3L +
            motionSampleCount(batch).toLong() * 24L +
            touchSampleCount(batch).toLong() * 32L +
            (batch.events.size + batch.stateRecords.size).toLong() * 256L +
            batch.gaps.size.toLong() * 128L

    /**
     * Encodes [prototypeBatch], splitting deterministically into 2+ consecutive sequenced
     * [PreparedBatch]es whenever any W8-26 count, row, unzipped-byte, or wire-byte bound is exceeded.
     */
    fun encodeAndSplitToFitBounds(
        prototypeBatch: Batch,
        compress: Boolean,
        nextSequence: () -> Long,
        diagnosticsSink: ((Diagnostic) -> Unit)? = null,
        carriedDroppedSummary: DroppedBatchesSummary? = null,
    ): List<PreparedBatch> {
        val normalized = normalizeHeaderWindowLength(prototypeBatch)

        // Fast path: check count/row bounds before encoding to avoid double work on normal windows.
        if (countsWithinBounds(normalized)) {
            val probe = normalized.withSequence(PROBE_SEQUENCE)
            val probeRaw = BatchProtoCodec.encode(probe)
            val probeWire = if (compress) GzipCodec.gzip(probeRaw) else probeRaw
            if (validateBounds(probe, probeRaw, probeWire).isEmpty()) {
                val finalBatch = normalized.withSequence(nextSequence())
                val raw = BatchProtoCodec.encode(finalBatch)
                val wire = if (compress) GzipCodec.gzip(raw) else raw
                return listOf(
                    PreparedBatch(
                        batch = finalBatch,
                        rawProtoBytes = raw,
                        wireBytes = wire,
                        compressed = compress,
                        carriedDroppedSummary = carriedDroppedSummary,
                    ),
                )
            }
        }

        // Slow path: recursively bisect until every chunk fits all W8-26 bounds.
        val fittingChunks = mutableListOf<Batch>()
        splitRecursive(
            batch = normalized,
            compress = compress,
            depth = 0,
            out = fittingChunks,
            diagnosticsSink = diagnosticsSink,
        )

        return fittingChunks.mapIndexed { idx, chunk ->
            val sequenced = chunk.withSequence(nextSequence())
            val raw = BatchProtoCodec.encode(sequenced)
            val wire = if (compress) GzipCodec.gzip(raw) else raw
            PreparedBatch(
                batch = sequenced,
                rawProtoBytes = raw,
                wireBytes = wire,
                compressed = compress,
                carriedDroppedSummary = if (idx == 0) carriedDroppedSummary else null,
            )
        }
    }

    private fun normalizeHeaderWindowLength(batch: Batch): Batch {
        val header = batch.header ?: return batch
        val clampedLen = header.windowLengthS.coerceIn(1, MAX_WINDOW_LENGTH_S)
        return if (clampedLen == header.windowLengthS) {
            batch
        } else {
            batch.copy(header = header.copy(windowLengthS = clampedLen))
        }
    }

    private fun countsWithinBounds(batch: Batch): Boolean =
        batch.events.size <= MAX_GAME_EVENTS &&
            batch.gaps.size <= MAX_GAP_RECORDS &&
            motionSampleCount(batch) <= MAX_MOTION_SAMPLES &&
            touchContactCount(batch) <= MAX_TOUCH_CONTACTS &&
            batch.screenChanges.size <= MAX_SCREEN_CHANGES &&
            touchSampleCount(batch) <= MAX_TOUCH_SAMPLES &&
            maxSingleTableRows(batch) <= MAX_TABLE_ROWS

    private fun splitRecursive(
        batch: Batch,
        compress: Boolean,
        depth: Int,
        out: MutableList<Batch>,
        diagnosticsSink: ((Diagnostic) -> Unit)?,
    ) {
        val probe = batch.withSequence(PROBE_SEQUENCE)
        val probeRaw = BatchProtoCodec.encode(probe)
        val probeWire = if (compress) GzipCodec.gzip(probeRaw) else probeRaw
        val faults = validateBounds(probe, probeRaw, probeWire)

        if (faults.isEmpty()) {
            out.add(batch)
            return
        }

        if (depth >= MAX_SPLIT_DEPTH) {
            diagnosticsSink?.invoke(
                Diagnostic(
                    timeUs = batch.header?.clocks?.unityUs ?: 0L,
                    severity = Severity.SEVERITY_ERROR,
                    code = DIAGNOSTIC_BOUND_UNSPLIT,
                    detail = faults.firstOrNull().orEmpty(),
                    count = 1,
                ),
            )
            out.add(batch)
            return
        }

        val split = bisectBatch(batch)
        if (split == null) {
            diagnosticsSink?.invoke(
                Diagnostic(
                    timeUs = batch.header?.clocks?.unityUs ?: 0L,
                    severity = Severity.SEVERITY_ERROR,
                    code = DIAGNOSTIC_BOUND_UNSPLIT,
                    detail = faults.firstOrNull().orEmpty(),
                    count = 1,
                ),
            )
            out.add(batch)
            return
        }

        splitRecursive(split.first, compress, depth + 1, out, diagnosticsSink)
        splitRecursive(split.second, compress, depth + 1, out, diagnosticsSink)
    }

    /**
     * Deterministically bisects [batch] into `(left, right)` while preserving:
     * - `Header.batchesDropped`, `lifecycle`, `diagnostics` on `left` only
     * - `Carried` progression across split `events` and `stateRecords`
     * - collector-valid deltas and `timeStepsUs` when splitting a single `SensorBlock` or `TouchContact`
     */
    private fun bisectBatch(batch: Batch): Pair<Batch, Batch>? {
        val rightHeader = batch.header?.copy(batchesDropped = 0)

        // 1. Events / stateRecords bound exceeded
        val totalCalls = batch.events.size + batch.stateRecords.size
        if (totalCalls > 1 && (batch.events.size > MAX_GAME_EVENTS || batch.stateRecords.size > MAX_TABLE_ROWS)) {
            return bisectEventsAndState(batch, rightHeader)
        }

        // 2. Gaps bound exceeded
        if (batch.gaps.size > MAX_GAP_RECORDS) {
            val mid = batch.gaps.size / 2
            val left = batch.copy(gaps = batch.gaps.subList(0, mid))
            val right = emptyShellForRight(batch, rightHeader, advanceCarried(batch)).copy(
                gaps = batch.gaps.subList(mid, batch.gaps.size),
            )
            return left to right
        }

        // 3. Motion samples or sensor_seconds rows bound exceeded
        if (motionSampleCount(batch) > MAX_MOTION_SAMPLES || sensorSecondsRowCount(batch) > MAX_TABLE_ROWS) {
            bisectMotion(batch, rightHeader)?.let { return it }
        }

        // 4. Touch contacts or touch samples bound exceeded
        if (touchContactCount(batch) > MAX_TOUCH_CONTACTS ||
            touchSampleCount(batch) > MAX_TOUCH_SAMPLES ||
            touchSecondsRowCount(batch) > MAX_TABLE_ROWS
        ) {
            bisectTouch(batch, rightHeader)?.let { return it }
        }

        // 5. Screen changes bound exceeded
        if (batch.screenChanges.size > MAX_SCREEN_CHANGES) {
            val mid = batch.screenChanges.size / 2
            val left = batch.copy(screenChanges = batch.screenChanges.subList(0, mid))
            val right = emptyShellForRight(batch, rightHeader, advanceCarried(batch)).copy(
                screenChanges = batch.screenChanges.subList(mid, batch.screenChanges.size),
            )
            return left to right
        }

        // 6. Byte or row-byte bound exceeded: split the largest splittable section first.
        if (totalCalls > 1) return bisectEventsAndState(batch, rightHeader)
        bisectMotion(batch, rightHeader)?.let { return it }
        bisectTouch(batch, rightHeader)?.let { return it }
        if (batch.gaps.size > 1) {
            val mid = batch.gaps.size / 2
            val left = batch.copy(gaps = batch.gaps.subList(0, mid))
            val right = emptyShellForRight(batch, rightHeader, advanceCarried(batch)).copy(
                gaps = batch.gaps.subList(mid, batch.gaps.size),
            )
            return left to right
        }
        if (batch.screenChanges.size > 1) {
            val mid = batch.screenChanges.size / 2
            val left = batch.copy(screenChanges = batch.screenChanges.subList(0, mid))
            val right = emptyShellForRight(batch, rightHeader, advanceCarried(batch)).copy(
                screenChanges = batch.screenChanges.subList(mid, batch.screenChanges.size),
            )
            return left to right
        }

        // 7. Separate heterogeneous top-level sections (e.g. 1 large sensor block + 1 large event)
        val hasMotionOrGaps = batch.sensorBlocks.isNotEmpty() || batch.gaps.isNotEmpty()
        val hasTouchOrEvents = batch.touch != null || batch.events.isNotEmpty() ||
            batch.stateRecords.isNotEmpty() || batch.screenChanges.isNotEmpty()
        if (hasMotionOrGaps && hasTouchOrEvents) {
            val left = batch.copy(
                touch = null,
                events = emptyList(),
                stateRecords = emptyList(),
                screenChanges = emptyList(),
            )
            val right = Batch(
                header = rightHeader,
                touch = batch.touch,
                events = batch.events,
                stateRecords = batch.stateRecords,
                screenChanges = batch.screenChanges,
                carried = batch.carried,
            )
            return left to right
        }

        return null
    }

    private fun bisectEventsAndState(batch: Batch, rightHeader: Header?): Pair<Batch, Batch> {
        data class OrderedItem(val order: Int, val event: GameEvent? = null, val state: StateRecord? = null)

        val combined = ArrayList<OrderedItem>(batch.events.size + batch.stateRecords.size)
        for (e in batch.events) combined.add(OrderedItem(e.order, event = e))
        for (s in batch.stateRecords) combined.add(OrderedItem(s.order, state = s))
        combined.sortBy { it.order }

        val mid = combined.size / 2
        val leftItems = combined.subList(0, mid)
        val rightItems = combined.subList(mid, combined.size)

        val leftEvents = mutableListOf<GameEvent>()
        val leftStates = mutableListOf<StateRecord>()
        leftItems.forEachIndexed { idx, item ->
            val newOrder = idx + 1
            item.event?.let { leftEvents.add(it.copy(order = newOrder)) }
            item.state?.let { leftStates.add(it.copy(order = newOrder)) }
        }

        val rightEvents = mutableListOf<GameEvent>()
        val rightStates = mutableListOf<StateRecord>()
        rightItems.forEachIndexed { idx, item ->
            val newOrder = idx + 1
            item.event?.let { rightEvents.add(it.copy(order = newOrder)) }
            item.state?.let { rightStates.add(it.copy(order = newOrder)) }
        }

        val rightCarried = advanceCarried(batch.carried, leftEvents, leftStates)

        val left = batch.copy(
            events = leftEvents,
            stateRecords = leftStates,
        )
        val right = emptyShellForRight(batch, rightHeader, rightCarried).copy(
            events = rightEvents,
            stateRecords = rightStates,
        )
        return left to right
    }

    private fun bisectMotion(batch: Batch, rightHeader: Header?): Pair<Batch, Batch>? {
        val blocks = batch.sensorBlocks
        if (blocks.isEmpty()) return null

        val (leftBlocks, rightBlocks) = if (blocks.size > 1) {
            val mid = blocks.size / 2
            blocks.subList(0, mid) to blocks.subList(mid, blocks.size)
        } else {
            val splitBlock = splitSensorBlock(blocks[0]) ?: return null
            listOf(splitBlock.first) to listOf(splitBlock.second)
        }

        val left = batch.copy(sensorBlocks = leftBlocks)
        val right = emptyShellForRight(batch, rightHeader, advanceCarried(batch)).copy(
            sensorBlocks = rightBlocks,
        )
        return left to right
    }

    private fun bisectTouch(batch: Batch, rightHeader: Header?): Pair<Batch, Batch>? {
        val touch = batch.touch ?: return null
        val contacts = touch.contacts
        if (contacts.isEmpty()) return null

        val (leftContacts, rightContacts) = if (contacts.size > 1) {
            val mid = contacts.size / 2
            contacts.subList(0, mid) to contacts.subList(mid, contacts.size)
        } else {
            val splitContact = splitTouchContact(contacts[0]) ?: return null
            listOf(splitContact.first) to listOf(splitContact.second)
        }

        val left = batch.copy(touch = touch.copy(contacts = leftContacts))
        val right = emptyShellForRight(batch, rightHeader, advanceCarried(batch)).copy(
            touch = touch.copy(contacts = rightContacts),
        )
        return left to right
    }

    private fun emptyShellForRight(
        original: Batch,
        rightHeader: Header?,
        rightCarried: Carried?,
    ): Batch = Batch(
        header = rightHeader,
        carried = rightCarried,
    )

    private fun advanceCarried(batch: Batch): Carried? =
        advanceCarried(batch.carried, batch.events, batch.stateRecords)

    private fun advanceCarried(
        openingCarried: Carried?,
        events: List<GameEvent>,
        stateRecords: List<StateRecord>,
    ): Carried? {
        if (openingCarried == null) return null

        val stateValues = LinkedHashMap<String, Value>(openingCarried.state?.values ?: emptyMap())
        var stateVersion = openingCarried.state?.version ?: 0
        val openSpans = LinkedHashMap<String, OpenSpan>()
        openingCarried.openSpans?.spans?.forEach { openSpans[it.name] = it }
        var lastEventTimeUs = openingCarried.lastEventTimeUs

        data class Call(val order: Int, val event: GameEvent? = null, val state: StateRecord? = null)

        val ordered = ArrayList<Call>(events.size + stateRecords.size)
        for (e in events) ordered.add(Call(e.order, event = e))
        for (s in stateRecords) ordered.add(Call(s.order, state = s))
        ordered.sortBy { it.order }

        for (call in ordered) {
            call.state?.let { rec ->
                when (rec.kind) {
                    StateKind.STATE_KIND_PUT -> {
                        stateValues.clear()
                        stateValues.putAll(rec.values)
                    }
                    StateKind.STATE_KIND_PATCH -> {
                        for (k in rec.removedKeys) stateValues.remove(k)
                        stateValues.putAll(rec.values)
                    }
                    else -> Unit
                }
                stateVersion = rec.version
            }
            call.event?.let { ev ->
                lastEventTimeUs = ev.timeUs
                if (ev.spanName.isNotEmpty()) {
                    when (ev.spanMarker) {
                        SpanMarker.SPAN_MARKER_OPEN -> {
                            openSpans.remove(ev.spanName)
                            openSpans[ev.spanName] = OpenSpan(ev.spanName, ev.spanNumber, ev.timeUs)
                        }
                        SpanMarker.SPAN_MARKER_CLOSE -> {
                            if (ev.spanNumber > 0) {
                                openSpans.remove(ev.spanName)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        return Carried(
            state = openingCarried.state?.let { State(values = stateValues, version = stateVersion) },
            openSpans = openingCarried.openSpans?.let { OpenSpans(spans = openSpans.values.toList()) },
            lastEventTimeUs = lastEventTimeUs,
        )
    }

    internal fun splitSensorBlock(block: SensorBlock): Pair<SensorBlock, SensorBlock>? {
        val fullCount = block.x.size
        if (fullCount >= 2) {
            val mid = fullCount / 2
            var absTime = block.firstTimeUs
            var absX = block.x[0]
            var absY = block.y[0]
            var absZ = block.z[0]
            for (i in 1..mid) {
                absTime += block.timeStepsUs[i - 1].toLong()
                absX += block.x[i]
                absY += block.y[i]
                absZ += block.z[i]
            }

            val leftHolds = block.holds.filter { it.startTimeUs < absTime }
            val rightHolds = block.holds.filter { it.startTimeUs >= absTime }

            val leftX = block.x.subList(0, mid)
            val leftY = block.y.subList(0, mid)
            val leftZ = block.z.subList(0, mid)
            val leftSteps = if (mid > 1) block.timeStepsUs.subList(0, mid - 1) else emptyList()

            val rightX = ArrayList<Int>(fullCount - mid).apply {
                add(absX)
                for (i in mid + 1 until fullCount) add(block.x[i])
            }
            val rightY = ArrayList<Int>(fullCount - mid).apply {
                add(absY)
                for (i in mid + 1 until fullCount) add(block.y[i])
            }
            val rightZ = ArrayList<Int>(fullCount - mid).apply {
                add(absZ)
                for (i in mid + 1 until fullCount) add(block.z[i])
            }
            val rightSteps = if (fullCount - mid > 1) block.timeStepsUs.subList(mid, fullCount - 1) else emptyList()

            val left = block.copy(
                sampleCount = leftX.size + leftHolds.sumOf { it.sampleCount },
                timeStepsUs = leftSteps,
                x = leftX,
                y = leftY,
                z = leftZ,
                holds = leftHolds,
            )
            val right = block.copy(
                sampleCount = rightX.size + rightHolds.sumOf { it.sampleCount },
                firstTimeUs = absTime,
                timeStepsUs = rightSteps,
                x = rightX,
                y = rightY,
                z = rightZ,
                holds = rightHolds,
            )
            return left to right
        }

        if (block.holds.size >= 2) {
            val mid = block.holds.size / 2
            val leftHolds = block.holds.subList(0, mid)
            val rightHolds = block.holds.subList(mid, block.holds.size)
            val left = block.copy(
                sampleCount = block.x.size + leftHolds.sumOf { it.sampleCount },
                holds = leftHolds,
            )
            val right = block.copy(
                sampleCount = rightHolds.sumOf { it.sampleCount },
                firstTimeUs = 0L,
                timeStepsUs = emptyList(),
                x = emptyList(),
                y = emptyList(),
                z = emptyList(),
                holds = rightHolds,
            )
            return left to right
        }

        if (block.holds.size == 1 && block.holds[0].sampleCount >= 2) {
            val h = block.holds[0]
            val count1 = h.sampleCount / 2
            val count2 = h.sampleCount - count1
            val midTimeUs = h.startTimeUs + ((h.endTimeUs - h.startTimeUs) * count1.toLong()) / h.sampleCount.toLong()
            val hold1 = Hold(
                startTimeUs = h.startTimeUs,
                endTimeUs = midTimeUs,
                sampleCount = count1,
                x = h.x,
                y = h.y,
                z = h.z,
                stats = emptyList(),
            )
            val hold2 = Hold(
                startTimeUs = midTimeUs,
                endTimeUs = h.endTimeUs,
                sampleCount = count2,
                x = h.x,
                y = h.y,
                z = h.z,
                stats = emptyList(),
            )
            val left = block.copy(
                sampleCount = block.x.size + count1,
                holds = listOf(hold1),
            )
            val right = block.copy(
                sampleCount = count2,
                firstTimeUs = 0L,
                timeStepsUs = emptyList(),
                x = emptyList(),
                y = emptyList(),
                z = emptyList(),
                holds = listOf(hold2),
            )
            return left to right
        }

        return null
    }

    internal fun splitTouchContact(contact: TouchContact): Pair<TouchContact, TouchContact>? {
        val fullCount = contact.x.size
        if (fullCount >= 2) {
            val mid = fullCount / 2
            val hasPressure = contact.pressure.size == fullCount
            var absTime = contact.firstTimeUs
            var absX = contact.x[0]
            var absY = contact.y[0]
            var absMaj = contact.sizeMajor[0]
            var absMin = contact.sizeMinor[0]
            var absPress = if (hasPressure) contact.pressure[0] else 0

            var minX = contact.before?.minX ?: absX
            var maxX = contact.before?.maxX ?: absX
            var minY = contact.before?.minY ?: absY
            var maxY = contact.before?.maxY ?: absY
            if (absX < minX) minX = absX
            if (absX > maxX) maxX = absX
            if (absY < minY) minY = absY
            if (absY > maxY) maxY = absY

            for (i in 1..mid) {
                absTime += contact.timeStepsUs[i - 1].toLong()
                absX += contact.x[i]
                absY += contact.y[i]
                absMaj += contact.sizeMajor[i]
                absMin += contact.sizeMinor[i]
                if (hasPressure) absPress += contact.pressure[i]
                if (i < mid) {
                    if (absX < minX) minX = absX
                    if (absX > maxX) maxX = absX
                    if (absY < minY) minY = absY
                    if (absY > maxY) maxY = absY
                }
            }

            val leftHolds = contact.holds.filter { it.startTimeUs < absTime }
            val rightHolds = contact.holds.filter { it.startTimeUs >= absTime }
            for (h in leftHolds) {
                if (h.x < minX) minX = h.x
                if (h.x > maxX) maxX = h.x
                if (h.y < minY) minY = h.y
                if (h.y > maxY) maxY = h.y
            }

            val leftEventsSeen = (contact.eventsSeen / 2).coerceAtLeast(1)
            val rightEventsSeen = (contact.eventsSeen - leftEventsSeen).coerceAtLeast(1)

            val left = contact.copy(
                end = TouchEnd.TOUCH_END_STILL_DOWN,
                timeStepsUs = if (mid > 1) contact.timeStepsUs.subList(0, mid - 1) else emptyList(),
                x = contact.x.subList(0, mid),
                y = contact.y.subList(0, mid),
                sizeMajor = contact.sizeMajor.subList(0, mid),
                sizeMinor = contact.sizeMinor.subList(0, mid),
                pressure = if (hasPressure) contact.pressure.subList(0, mid) else emptyList(),
                holds = leftHolds,
                eventsSeen = leftEventsSeen,
            )

            val rightX = ArrayList<Int>(fullCount - mid).apply {
                add(absX)
                for (i in mid + 1 until fullCount) add(contact.x[i])
            }
            val rightY = ArrayList<Int>(fullCount - mid).apply {
                add(absY)
                for (i in mid + 1 until fullCount) add(contact.y[i])
            }
            val rightMaj = ArrayList<Int>(fullCount - mid).apply {
                add(absMaj)
                for (i in mid + 1 until fullCount) add(contact.sizeMajor[i])
            }
            val rightMin = ArrayList<Int>(fullCount - mid).apply {
                add(absMin)
                for (i in mid + 1 until fullCount) add(contact.sizeMinor[i])
            }
            val rightPress = if (hasPressure) {
                ArrayList<Int>(fullCount - mid).apply {
                    add(absPress)
                    for (i in mid + 1 until fullCount) add(contact.pressure[i])
                }
            } else {
                emptyList()
            }

            val right = contact.copy(
                continued = true,
                firstTimeUs = absTime,
                timeStepsUs = if (fullCount - mid > 1) contact.timeStepsUs.subList(mid, fullCount - 1) else emptyList(),
                x = rightX,
                y = rightY,
                sizeMajor = rightMaj,
                sizeMinor = rightMin,
                pressure = rightPress,
                holds = rightHolds,
                eventsSeen = rightEventsSeen,
                before = ContactBefore(
                    downTimeUs = contact.before?.downTimeUs ?: contact.firstTimeUs,
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                ),
            )
            return left to right
        }

        if (contact.holds.size >= 2) {
            val mid = contact.holds.size / 2
            val leftHolds = contact.holds.subList(0, mid)
            val rightHolds = contact.holds.subList(mid, contact.holds.size)
            val left = contact.copy(
                end = TouchEnd.TOUCH_END_STILL_DOWN,
                holds = leftHolds,
            )
            val right = contact.copy(
                continued = true,
                firstTimeUs = 0L,
                timeStepsUs = emptyList(),
                x = emptyList(),
                y = emptyList(),
                sizeMajor = emptyList(),
                sizeMinor = emptyList(),
                pressure = emptyList(),
                holds = rightHolds,
            )
            return left to right
        }

        if (contact.holds.size == 1 && contact.holds[0].sampleCount >= 2) {
            val h = contact.holds[0]
            val count1 = h.sampleCount / 2
            val count2 = h.sampleCount - count1
            val midTimeUs = h.startTimeUs + ((h.endTimeUs - h.startTimeUs) * count1.toLong()) / h.sampleCount.toLong()
            val h1 = TouchHold(h.startTimeUs, midTimeUs, count1, h.x, h.y, h.sizeMajor, h.sizeMinor, h.pressure, emptyList())
            val h2 = TouchHold(midTimeUs, h.endTimeUs, count2, h.x, h.y, h.sizeMajor, h.sizeMinor, h.pressure, emptyList())
            val left = contact.copy(end = TouchEnd.TOUCH_END_STILL_DOWN, holds = listOf(h1))
            val right = contact.copy(
                continued = true,
                firstTimeUs = 0L,
                timeStepsUs = emptyList(),
                x = emptyList(),
                y = emptyList(),
                sizeMajor = emptyList(),
                sizeMinor = emptyList(),
                pressure = emptyList(),
                holds = listOf(h2),
            )
            return left to right
        }

        return null
    }

    private fun contactSampleCount(contact: TouchContact): Int =
        contact.x.size + contact.holds.sumOf { it.sampleCount }

    private fun Batch.withSequence(seq: Long): Batch {
        val h = header ?: return this
        return copy(header = h.copy(sequence = seq))
    }

    private fun checkRequiredId(faults: MutableList<String>, fieldName: String, value: String) {
        if (value.isEmpty()) {
            faults.add("Header.$fieldName must not be empty.")
        } else if (Utf8Bounds.utf8ByteLength(value) > Utf8Bounds.MAX_HEADER_TEXT_BYTES) {
            faults.add("Header.$fieldName exceeds ${Utf8Bounds.MAX_HEADER_TEXT_BYTES} UTF-8 bytes.")
        }
    }

    private fun checkBoundedText(faults: MutableList<String>, fieldName: String, value: String) {
        if (Utf8Bounds.utf8ByteLength(value) > Utf8Bounds.MAX_HEADER_TEXT_BYTES) {
            faults.add("Header.$fieldName exceeds ${Utf8Bounds.MAX_HEADER_TEXT_BYTES} UTF-8 bytes.")
        }
    }

    const val DIAGNOSTIC_BOUND_UNSPLIT: String = "batch_bound_unsplit"
}
