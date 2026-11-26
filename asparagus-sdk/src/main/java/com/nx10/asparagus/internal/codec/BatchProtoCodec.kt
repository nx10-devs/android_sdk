package com.nx10.asparagus.internal.codec

import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.CaptureSource
import com.nx10.asparagus.contract.Carried
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.ClockReading
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.ContactBefore
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.FallbackReason
import com.nx10.asparagus.contract.GameEvent
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapRecord
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.contract.Hold
import com.nx10.asparagus.contract.HoldStats
import com.nx10.asparagus.contract.LifecycleKind
import com.nx10.asparagus.contract.LifecycleRecord
import com.nx10.asparagus.contract.OpenSpan
import com.nx10.asparagus.contract.OpenSpans
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.Platform
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.SensorBlock
import com.nx10.asparagus.contract.ServerAnchor
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.SpanMarker
import com.nx10.asparagus.contract.State
import com.nx10.asparagus.contract.StateKind
import com.nx10.asparagus.contract.StateRecord
import com.nx10.asparagus.contract.Touch
import com.nx10.asparagus.contract.TouchContact
import com.nx10.asparagus.contract.TouchEnd
import com.nx10.asparagus.contract.TouchHold
import com.nx10.asparagus.contract.TouchMode
import com.nx10.asparagus.contract.UploadResponse
import com.nx10.asparagus.contract.UploadStatus
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.contract.ValueUnit

/**
 * Binary Protobuf v3 encoder and decoder for `asparagus_contract.v1.Batch`
 * and `asparagus_contract.v1.UploadResponse`.
 */
object BatchProtoCodec {

    fun encode(batch: Batch): ByteArray {
        val writer = ProtoWriter(1024)
        writeBatch(writer, batch)
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): Batch {
        val reader = ProtoReader(bytes)
        return readBatch(reader)
    }

    fun encodeUploadResponse(response: UploadResponse): ByteArray {
        val writer = ProtoWriter(8)
        writer.writeEnum(1, response.status.protoNumber)
        return writer.toByteArray()
    }

    fun decodeUploadResponse(bytes: ByteArray): UploadResponse {
        val reader = ProtoReader(bytes)
        var status: UploadStatus = UploadStatus.UPLOAD_STATUS_UNSPECIFIED
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> {
                    reader.expectWireType(wireType, WireType.VARINT, "UploadResponse.status")
                    status = UploadStatus.fromProtoNumber(reader.readEnum())
                }
                else -> reader.skipField(wireType)
            }
        }
        return UploadResponse(status = status)
    }

    // region Writers

    private fun writeBatch(w: ProtoWriter, batch: Batch) {
        batch.header?.let { h -> w.writeMessage(1) { writeHeader(it, h) } }
        for (block in batch.sensorBlocks) {
            w.writeMessage(2) { writeSensorBlock(it, block) }
        }
        batch.touch?.let { t -> w.writeMessage(3) { writeTouch(it, t) } }
        for (event in batch.events) {
            w.writeMessage(4) { writeGameEvent(it, event) }
        }
        for (stateRecord in batch.stateRecords) {
            w.writeMessage(5) { writeStateRecord(it, stateRecord) }
        }
        for (lifecycle in batch.lifecycle) {
            w.writeMessage(6) { writeLifecycleRecord(it, lifecycle) }
        }
        for (gap in batch.gaps) {
            w.writeMessage(7) { writeGapRecord(it, gap) }
        }
        for (screenChange in batch.screenChanges) {
            w.writeMessage(8) { writeScreenRecord(it, screenChange) }
        }
        for (diagnostic in batch.diagnostics) {
            w.writeMessage(9) { writeDiagnostic(it, diagnostic) }
        }
        batch.carried?.let { c -> w.writeMessage(10) { writeCarried(it, c) } }
    }

    private fun writeHeader(w: ProtoWriter, h: Header) {
        w.writeString(1, h.idOrg)
        w.writeString(2, h.idDeployment)
        w.writeString(3, h.idSource)
        w.writeString(4, h.idSession)
        w.writeUInt64(5, h.sequence)
        w.writeUInt32(6, h.schemaVersion)
        h.sdkVersion?.let { v -> w.writeMessage(7) { writeSdkVersion(it, v) } }
        w.writeEnum(8, h.platform.protoNumber)
        w.writeString(9, h.osVersion)
        w.writeString(10, h.deviceModel)
        w.writeString(11, h.settingsVersion)
        w.writeSInt64(12, h.windowStartUs)
        w.writeUInt32(13, h.windowLengthS)
        w.writeSInt64(14, h.closedUs)
        w.writeEnum(15, h.closeReason.protoNumber)
        h.clocks?.let { c -> w.writeMessage(16) { writeClockReading(it, c) } }
        h.serverAnchor?.let { a -> w.writeMessage(17) { writeServerAnchor(it, a) } }
        h.screen?.let { s -> w.writeMessage(18) { writeScreenRecord(it, s) } }
        w.writeBool(19, h.hasGyroscope)
        w.writeUInt32(20, h.batchesDropped)
        h.utcOffsetMinutes?.let { offset ->
            w.writeSInt32(21, offset, writeDefault = true)
        }
    }

    private fun writeSdkVersion(w: ProtoWriter, v: SdkVersion) {
        w.writeUInt32(1, v.major)
        w.writeUInt32(2, v.minor)
        w.writeUInt32(3, v.patch)
    }

    private fun writeClockReading(w: ProtoWriter, c: ClockReading) {
        w.writeSInt64(1, c.sessionUs)
        w.writeSInt64(2, c.unityUs)
        c.sensorUs?.let { sensorUs ->
            w.writeSInt64(3, sensorUs, writeDefault = true)
        }
        w.writeSInt64(4, c.wallUtcMs)
    }

    private fun writeServerAnchor(w: ProtoWriter, a: ServerAnchor) {
        w.writeSInt64(1, a.serverUtcMs)
        a.atReceipt?.let { r -> w.writeMessage(2) { writeClockReading(it, r) } }
        w.writeUInt32(3, a.roundTripMs)
    }

    private fun writeSensorBlock(w: ProtoWriter, b: SensorBlock) {
        w.writeEnum(1, b.sensor.protoNumber)
        w.writeEnum(2, b.source.protoNumber)
        w.writeEnum(3, b.fallbackReason.protoNumber)
        w.writeEnum(4, b.clock.protoNumber)
        w.writeEnum(5, b.unit.protoNumber)
        w.writeDouble(6, b.scale)
        w.writeFloat(7, b.requestedHz)
        w.writeUInt32(8, b.sampleCount)
        w.writeUInt32(9, b.longestGapUs)
        w.writeSInt64(10, b.firstTimeUs)
        w.writePackedSInt32(11, b.timeStepsUs)
        w.writePackedSInt32(12, b.x)
        w.writePackedSInt32(13, b.y)
        w.writePackedSInt32(14, b.z)
        for (hold in b.holds) {
            w.writeMessage(15) { writeHold(it, hold) }
        }
    }

    private fun writeHold(w: ProtoWriter, h: Hold) {
        w.writeSInt64(1, h.startTimeUs)
        w.writeSInt64(2, h.endTimeUs)
        w.writeUInt32(3, h.sampleCount)
        w.writeSInt32(4, h.x)
        w.writeSInt32(5, h.y)
        w.writeSInt32(6, h.z)
        for (s in h.stats) {
            w.writeMessage(7) { writeHoldStats(it, s) }
        }
    }

    private fun writeHoldStats(w: ProtoWriter, s: HoldStats) {
        w.writeSInt64(1, s.sum)
        w.writeDouble(2, s.sumOfSquares)
        w.writeSInt32(3, s.minimum)
        w.writeSInt32(4, s.maximum)
    }

    private fun writeTouch(w: ProtoWriter, t: Touch) {
        w.writeEnum(1, t.mode.protoNumber)
        w.writeFloat(2, t.requestedHz)
        w.writeDouble(3, t.positionScale)
        w.writeDouble(4, t.sizeScale)
        w.writeDouble(5, t.pressureScale)
        for (c in t.contacts) {
            w.writeMessage(6) { writeTouchContact(it, c) }
        }
    }

    private fun writeTouchContact(w: ProtoWriter, c: TouchContact) {
        w.writeUInt32(1, c.fingerId)
        w.writeBool(2, c.continued)
        w.writeEnum(3, c.end.protoNumber)
        w.writeSInt64(4, c.firstTimeUs)
        w.writePackedSInt32(5, c.timeStepsUs)
        w.writePackedSInt32(6, c.x)
        w.writePackedSInt32(7, c.y)
        w.writePackedSInt32(8, c.sizeMajor)
        w.writePackedSInt32(9, c.sizeMinor)
        w.writePackedSInt32(10, c.pressure)
        for (hold in c.holds) {
            w.writeMessage(11) { writeTouchHold(it, hold) }
        }
        w.writeUInt32(12, c.eventsSeen)
        c.before?.let { b -> w.writeMessage(13) { writeContactBefore(it, b) } }
    }

    private fun writeContactBefore(w: ProtoWriter, b: ContactBefore) {
        w.writeSInt64(1, b.downTimeUs)
        w.writeSInt32(2, b.minX)
        w.writeSInt32(3, b.maxX)
        w.writeSInt32(4, b.minY)
        w.writeSInt32(5, b.maxY)
    }

    private fun writeTouchHold(w: ProtoWriter, h: TouchHold) {
        w.writeSInt64(1, h.startTimeUs)
        w.writeSInt64(2, h.endTimeUs)
        w.writeUInt32(3, h.sampleCount)
        w.writeSInt32(4, h.x)
        w.writeSInt32(5, h.y)
        w.writeSInt32(6, h.sizeMajor)
        w.writeSInt32(7, h.sizeMinor)
        w.writeSInt32(8, h.pressure)
        for (s in h.stats) {
            w.writeMessage(9) { writeHoldStats(it, s) }
        }
    }

    private fun writeScreenRecord(w: ProtoWriter, s: ScreenRecord) {
        w.writeSInt64(1, s.timeUs)
        w.writeUInt32(2, s.widthPx)
        w.writeUInt32(3, s.heightPx)
        w.writeFloat(4, s.densityDpi)
        w.writeEnum(5, s.orientation.protoNumber)
        w.writeEnum(6, s.origin.protoNumber)
    }

    private fun writeValue(w: ProtoWriter, v: Value) {
        // Inside `oneof kind`, explicit default values ("", 0.0, false) MUST be written on the wire.
        when (v) {
            is Value.Text -> w.writeString(1, v.value, writeDefault = true)
            is Value.Number -> w.writeDouble(2, v.value, writeDefault = true)
            is Value.Flag -> w.writeBool(3, v.value, writeDefault = true)
            Value.Empty -> Unit
        }
    }

    private fun writeValueMap(w: ProtoWriter, fieldNumber: Int, map: Map<String, Value>) {
        for ((key, value) in map) {
            w.writeMessage(fieldNumber) { entryWriter ->
                entryWriter.writeString(1, key)
                entryWriter.writeMessage(2) { valueWriter ->
                    writeValue(valueWriter, value)
                }
            }
        }
    }

    private fun writeGameEvent(w: ProtoWriter, e: GameEvent) {
        w.writeSInt64(1, e.timeUs)
        w.writeString(2, e.name)
        writeValueMap(w, 3, e.fields)
        e.outcome?.let { outcome ->
            w.writeString(4, outcome, writeDefault = true)
        }
        w.writeString(5, e.spanName)
        w.writeEnum(6, e.spanMarker.protoNumber)
        w.writeUInt32(7, e.spanNumber)
        w.writeUInt32(8, e.order)
    }

    private fun writeStateRecord(w: ProtoWriter, s: StateRecord) {
        w.writeSInt64(1, s.timeUs)
        w.writeEnum(2, s.kind.protoNumber)
        writeValueMap(w, 3, s.values)
        for (removedKey in s.removedKeys) {
            // Elements of repeated string always write even if empty.
            w.writeString(4, removedKey, writeDefault = true)
        }
        w.writeUInt32(5, s.order)
        w.writeUInt32(6, s.version)
    }

    private fun writeCarried(w: ProtoWriter, c: Carried) {
        c.state?.let { s -> w.writeMessage(1) { writeState(it, s) } }
        c.openSpans?.let { o -> w.writeMessage(2) { writeOpenSpans(it, o) } }
        c.lastEventTimeUs?.let { t ->
            w.writeSInt64(3, t, writeDefault = true)
        }
    }

    private fun writeState(w: ProtoWriter, s: State) {
        writeValueMap(w, 1, s.values)
        w.writeUInt32(2, s.version)
    }

    private fun writeOpenSpans(w: ProtoWriter, o: OpenSpans) {
        for (span in o.spans) {
            w.writeMessage(1) { writeOpenSpan(it, span) }
        }
    }

    private fun writeOpenSpan(w: ProtoWriter, s: OpenSpan) {
        w.writeString(1, s.name)
        w.writeUInt32(2, s.number)
        w.writeSInt64(3, s.openedTimeUs)
    }

    private fun writeLifecycleRecord(w: ProtoWriter, l: LifecycleRecord) {
        w.writeSInt64(1, l.timeUs)
        w.writeEnum(2, l.kind.protoNumber)
        w.writeSInt64(3, l.clockStepS)
    }

    private fun writeGapRecord(w: ProtoWriter, g: GapRecord) {
        w.writeEnum(1, g.signal.protoNumber)
        w.writeEnum(2, g.clock.protoNumber)
        w.writeSInt64(3, g.startTimeUs)
        w.writeSInt64(4, g.endTimeUs)
        w.writeEnum(5, g.cause.protoNumber)
        w.writeUInt32(6, g.batchesDropped)
        w.writeUInt64(7, g.firstDroppedSequence)
    }

    private fun writeDiagnostic(w: ProtoWriter, d: Diagnostic) {
        w.writeSInt64(1, d.timeUs)
        w.writeEnum(2, d.severity.protoNumber)
        w.writeString(3, d.code)
        w.writeString(4, d.detail)
        w.writeUInt32(5, d.count)
    }

    // endregion

    // region Readers

    private fun readBatch(r: ProtoReader): Batch {
        var header: Header? = null
        val sensorBlocks = mutableListOf<SensorBlock>()
        var touch: Touch? = null
        val events = mutableListOf<GameEvent>()
        val stateRecords = mutableListOf<StateRecord>()
        val lifecycle = mutableListOf<LifecycleRecord>()
        val gaps = mutableListOf<GapRecord>()
        val screenChanges = mutableListOf<ScreenRecord>()
        val diagnostics = mutableListOf<Diagnostic>()
        var carried: Carried? = null

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.header")
                    header = readHeader(r.readSubMessageReader())
                }
                2 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.sensor_blocks")
                    sensorBlocks.add(readSensorBlock(r.readSubMessageReader()))
                }
                3 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.touch")
                    touch = readTouch(r.readSubMessageReader())
                }
                4 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.events")
                    events.add(readGameEvent(r.readSubMessageReader()))
                }
                5 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.state_records")
                    stateRecords.add(readStateRecord(r.readSubMessageReader()))
                }
                6 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.lifecycle")
                    lifecycle.add(readLifecycleRecord(r.readSubMessageReader()))
                }
                7 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.gaps")
                    gaps.add(readGapRecord(r.readSubMessageReader()))
                }
                8 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.screen_changes")
                    screenChanges.add(readScreenRecord(r.readSubMessageReader()))
                }
                9 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.diagnostics")
                    diagnostics.add(readDiagnostic(r.readSubMessageReader()))
                }
                10 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Batch.carried")
                    carried = readCarried(r.readSubMessageReader())
                }
                else -> r.skipField(wireType)
            }
        }

        return Batch(
            header = header,
            sensorBlocks = sensorBlocks,
            touch = touch,
            events = events,
            stateRecords = stateRecords,
            lifecycle = lifecycle,
            gaps = gaps,
            screenChanges = screenChanges,
            diagnostics = diagnostics,
            carried = carried,
        )
    }

    private fun readHeader(r: ProtoReader): Header {
        var idOrg = ""
        var idDeployment = ""
        var idSource = ""
        var idSession = ""
        var sequence = 0L
        var schemaVersion = 0
        var sdkVersion: SdkVersion? = null
        var platform: Platform = Platform.PLATFORM_UNSPECIFIED
        var osVersion = ""
        var deviceModel = ""
        var settingsVersion = ""
        var windowStartUs = 0L
        var windowLengthS = 0
        var closedUs = 0L
        var closeReason: CloseReason = CloseReason.CLOSE_REASON_UNSPECIFIED
        var clocks: ClockReading? = null
        var serverAnchor: ServerAnchor? = null
        var screen: ScreenRecord? = null
        var hasGyroscope = false
        var batchesDropped = 0
        var utcOffsetMinutes: Int? = null

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.id_org"); idOrg = r.readString() }
                2 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.id_deployment"); idDeployment = r.readString() }
                3 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.id_source"); idSource = r.readString() }
                4 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.id_session"); idSession = r.readString() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "Header.sequence"); sequence = r.readUInt64() }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "Header.schema_version"); schemaVersion = r.readUInt32() }
                7 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.sdk_version"); sdkVersion = readSdkVersion(r.readSubMessageReader()) }
                8 -> { r.expectWireType(wireType, WireType.VARINT, "Header.platform"); platform = Platform.fromProtoNumber(r.readEnum()) }
                9 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.os_version"); osVersion = r.readString() }
                10 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.device_model"); deviceModel = r.readString() }
                11 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.settings_version"); settingsVersion = r.readString() }
                12 -> { r.expectWireType(wireType, WireType.VARINT, "Header.window_start_us"); windowStartUs = r.readSInt64() }
                13 -> { r.expectWireType(wireType, WireType.VARINT, "Header.window_length_s"); windowLengthS = r.readUInt32() }
                14 -> { r.expectWireType(wireType, WireType.VARINT, "Header.closed_us"); closedUs = r.readSInt64() }
                15 -> { r.expectWireType(wireType, WireType.VARINT, "Header.close_reason"); closeReason = CloseReason.fromProtoNumber(r.readEnum()) }
                16 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.clocks"); clocks = readClockReading(r.readSubMessageReader()) }
                17 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.server_anchor"); serverAnchor = readServerAnchor(r.readSubMessageReader()) }
                18 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Header.screen"); screen = readScreenRecord(r.readSubMessageReader()) }
                19 -> { r.expectWireType(wireType, WireType.VARINT, "Header.has_gyroscope"); hasGyroscope = r.readBool() }
                20 -> { r.expectWireType(wireType, WireType.VARINT, "Header.batches_dropped"); batchesDropped = r.readUInt32() }
                21 -> { r.expectWireType(wireType, WireType.VARINT, "Header.utc_offset_minutes"); utcOffsetMinutes = r.readSInt32() }
                else -> r.skipField(wireType)
            }
        }

        return Header(
            idOrg = idOrg,
            idDeployment = idDeployment,
            idSource = idSource,
            idSession = idSession,
            sequence = sequence,
            schemaVersion = schemaVersion,
            sdkVersion = sdkVersion,
            platform = platform,
            osVersion = osVersion,
            deviceModel = deviceModel,
            settingsVersion = settingsVersion,
            windowStartUs = windowStartUs,
            windowLengthS = windowLengthS,
            closedUs = closedUs,
            closeReason = closeReason,
            clocks = clocks,
            serverAnchor = serverAnchor,
            screen = screen,
            hasGyroscope = hasGyroscope,
            batchesDropped = batchesDropped,
            utcOffsetMinutes = utcOffsetMinutes,
        )
    }

    private fun readSdkVersion(r: ProtoReader): SdkVersion {
        var major = 0
        var minor = 0
        var patch = 0
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "SdkVersion.major"); major = r.readUInt32() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "SdkVersion.minor"); minor = r.readUInt32() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "SdkVersion.patch"); patch = r.readUInt32() }
                else -> r.skipField(wireType)
            }
        }
        return SdkVersion(major = major, minor = minor, patch = patch)
    }

    private fun readClockReading(r: ProtoReader): ClockReading {
        var sessionUs = 0L
        var unityUs = 0L
        var sensorUs: Long? = null
        var wallUtcMs = 0L
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "ClockReading.session_us"); sessionUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "ClockReading.unity_us"); unityUs = r.readSInt64() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "ClockReading.sensor_us"); sensorUs = r.readSInt64() }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "ClockReading.wall_utc_ms"); wallUtcMs = r.readSInt64() }
                else -> r.skipField(wireType)
            }
        }
        return ClockReading(
            sessionUs = sessionUs,
            unityUs = unityUs,
            sensorUs = sensorUs,
            wallUtcMs = wallUtcMs,
        )
    }

    private fun readServerAnchor(r: ProtoReader): ServerAnchor {
        var serverUtcMs = 0L
        var atReceipt: ClockReading? = null
        var roundTripMs = 0
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "ServerAnchor.server_utc_ms"); serverUtcMs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "ServerAnchor.at_receipt"); atReceipt = readClockReading(r.readSubMessageReader()) }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "ServerAnchor.round_trip_ms"); roundTripMs = r.readUInt32() }
                else -> r.skipField(wireType)
            }
        }
        return ServerAnchor(
            serverUtcMs = serverUtcMs,
            atReceipt = atReceipt,
            roundTripMs = roundTripMs,
        )
    }

    private fun readSensorBlock(r: ProtoReader): SensorBlock {
        var sensor: Sensor = Sensor.SENSOR_UNSPECIFIED
        var source: CaptureSource = CaptureSource.CAPTURE_SOURCE_UNSPECIFIED
        var fallbackReason: FallbackReason = FallbackReason.FALLBACK_REASON_UNSPECIFIED
        var clock: Clock = Clock.CLOCK_UNSPECIFIED
        var unit: ValueUnit = ValueUnit.VALUE_UNIT_UNSPECIFIED
        var scale = 0.0
        var requestedHz = 0f
        var sampleCount = 0
        var longestGapUs = 0
        var firstTimeUs = 0L
        val timeStepsUs = mutableListOf<Int>()
        val x = mutableListOf<Int>()
        val y = mutableListOf<Int>()
        val z = mutableListOf<Int>()
        val holds = mutableListOf<Hold>()

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.sensor"); sensor = Sensor.fromProtoNumber(r.readEnum()) }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.source"); source = CaptureSource.fromProtoNumber(r.readEnum()) }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.fallback_reason"); fallbackReason = FallbackReason.fromProtoNumber(r.readEnum()) }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.clock"); clock = Clock.fromProtoNumber(r.readEnum()) }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.unit"); unit = ValueUnit.fromProtoNumber(r.readEnum()) }
                6 -> { r.expectWireType(wireType, WireType.FIXED64, "SensorBlock.scale"); scale = r.readDouble() }
                7 -> { r.expectWireType(wireType, WireType.FIXED32, "SensorBlock.requested_hz"); requestedHz = r.readFloat() }
                8 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.sample_count"); sampleCount = r.readUInt32() }
                9 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.longest_gap_us"); longestGapUs = r.readUInt32() }
                10 -> { r.expectWireType(wireType, WireType.VARINT, "SensorBlock.first_time_us"); firstTimeUs = r.readSInt64() }
                11 -> r.readRepeatedSInt32(wireType, timeStepsUs)
                12 -> r.readRepeatedSInt32(wireType, x)
                13 -> r.readRepeatedSInt32(wireType, y)
                14 -> r.readRepeatedSInt32(wireType, z)
                15 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "SensorBlock.holds"); holds.add(readHold(r.readSubMessageReader())) }
                else -> r.skipField(wireType)
            }
        }

        return SensorBlock(
            sensor = sensor,
            source = source,
            fallbackReason = fallbackReason,
            clock = clock,
            unit = unit,
            scale = scale,
            requestedHz = requestedHz,
            sampleCount = sampleCount,
            longestGapUs = longestGapUs,
            firstTimeUs = firstTimeUs,
            timeStepsUs = timeStepsUs,
            x = x,
            y = y,
            z = z,
            holds = holds,
        )
    }

    private fun readHold(r: ProtoReader): Hold {
        var startTimeUs = 0L
        var endTimeUs = 0L
        var sampleCount = 0
        var x = 0
        var y = 0
        var z = 0
        val stats = mutableListOf<HoldStats>()

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "Hold.start_time_us"); startTimeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "Hold.end_time_us"); endTimeUs = r.readSInt64() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "Hold.sample_count"); sampleCount = r.readUInt32() }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "Hold.x"); x = r.readSInt32() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "Hold.y"); y = r.readSInt32() }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "Hold.z"); z = r.readSInt32() }
                7 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Hold.stats"); stats.add(readHoldStats(r.readSubMessageReader())) }
                else -> r.skipField(wireType)
            }
        }

        return Hold(
            startTimeUs = startTimeUs,
            endTimeUs = endTimeUs,
            sampleCount = sampleCount,
            x = x,
            y = y,
            z = z,
            stats = stats,
        )
    }

    private fun readHoldStats(r: ProtoReader): HoldStats {
        var sum = 0L
        var sumOfSquares = 0.0
        var minimum = 0
        var maximum = 0
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "HoldStats.sum"); sum = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.FIXED64, "HoldStats.sum_of_squares"); sumOfSquares = r.readDouble() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "HoldStats.minimum"); minimum = r.readSInt32() }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "HoldStats.maximum"); maximum = r.readSInt32() }
                else -> r.skipField(wireType)
            }
        }
        return HoldStats(
            sum = sum,
            sumOfSquares = sumOfSquares,
            minimum = minimum,
            maximum = maximum,
        )
    }

    private fun readTouch(r: ProtoReader): Touch {
        var mode: TouchMode = TouchMode.TOUCH_MODE_UNSPECIFIED
        var requestedHz = 0f
        var positionScale = 0.0
        var sizeScale = 0.0
        var pressureScale = 0.0
        val contacts = mutableListOf<TouchContact>()

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "Touch.mode"); mode = TouchMode.fromProtoNumber(r.readEnum()) }
                2 -> { r.expectWireType(wireType, WireType.FIXED32, "Touch.requested_hz"); requestedHz = r.readFloat() }
                3 -> { r.expectWireType(wireType, WireType.FIXED64, "Touch.position_scale"); positionScale = r.readDouble() }
                4 -> { r.expectWireType(wireType, WireType.FIXED64, "Touch.size_scale"); sizeScale = r.readDouble() }
                5 -> { r.expectWireType(wireType, WireType.FIXED64, "Touch.pressure_scale"); pressureScale = r.readDouble() }
                6 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Touch.contacts"); contacts.add(readTouchContact(r.readSubMessageReader())) }
                else -> r.skipField(wireType)
            }
        }

        return Touch(
            mode = mode,
            requestedHz = requestedHz,
            positionScale = positionScale,
            sizeScale = sizeScale,
            pressureScale = pressureScale,
            contacts = contacts,
        )
    }

    private fun readTouchContact(r: ProtoReader): TouchContact {
        var fingerId = 0
        var continued = false
        var end: TouchEnd = TouchEnd.TOUCH_END_UNSPECIFIED
        var firstTimeUs = 0L
        val timeStepsUs = mutableListOf<Int>()
        val x = mutableListOf<Int>()
        val y = mutableListOf<Int>()
        val sizeMajor = mutableListOf<Int>()
        val sizeMinor = mutableListOf<Int>()
        val pressure = mutableListOf<Int>()
        val holds = mutableListOf<TouchHold>()
        var eventsSeen = 0
        var before: ContactBefore? = null

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "TouchContact.finger_id"); fingerId = r.readUInt32() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "TouchContact.continued"); continued = r.readBool() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "TouchContact.end"); end = TouchEnd.fromProtoNumber(r.readEnum()) }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "TouchContact.first_time_us"); firstTimeUs = r.readSInt64() }
                5 -> r.readRepeatedSInt32(wireType, timeStepsUs)
                6 -> r.readRepeatedSInt32(wireType, x)
                7 -> r.readRepeatedSInt32(wireType, y)
                8 -> r.readRepeatedSInt32(wireType, sizeMajor)
                9 -> r.readRepeatedSInt32(wireType, sizeMinor)
                10 -> r.readRepeatedSInt32(wireType, pressure)
                11 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "TouchContact.holds"); holds.add(readTouchHold(r.readSubMessageReader())) }
                12 -> { r.expectWireType(wireType, WireType.VARINT, "TouchContact.events_seen"); eventsSeen = r.readUInt32() }
                13 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "TouchContact.before"); before = readContactBefore(r.readSubMessageReader()) }
                else -> r.skipField(wireType)
            }
        }

        return TouchContact(
            fingerId = fingerId,
            continued = continued,
            end = end,
            firstTimeUs = firstTimeUs,
            timeStepsUs = timeStepsUs,
            x = x,
            y = y,
            sizeMajor = sizeMajor,
            sizeMinor = sizeMinor,
            pressure = pressure,
            holds = holds,
            eventsSeen = eventsSeen,
            before = before,
        )
    }

    private fun readContactBefore(r: ProtoReader): ContactBefore {
        var downTimeUs = 0L
        var minX = 0
        var maxX = 0
        var minY = 0
        var maxY = 0
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "ContactBefore.down_time_us"); downTimeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "ContactBefore.min_x"); minX = r.readSInt32() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "ContactBefore.max_x"); maxX = r.readSInt32() }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "ContactBefore.min_y"); minY = r.readSInt32() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "ContactBefore.max_y"); maxY = r.readSInt32() }
                else -> r.skipField(wireType)
            }
        }
        return ContactBefore(
            downTimeUs = downTimeUs,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
        )
    }

    private fun readTouchHold(r: ProtoReader): TouchHold {
        var startTimeUs = 0L
        var endTimeUs = 0L
        var sampleCount = 0
        var x = 0
        var y = 0
        var sizeMajor = 0
        var sizeMinor = 0
        var pressure = 0
        val stats = mutableListOf<HoldStats>()

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.start_time_us"); startTimeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.end_time_us"); endTimeUs = r.readSInt64() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.sample_count"); sampleCount = r.readUInt32() }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.x"); x = r.readSInt32() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.y"); y = r.readSInt32() }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.size_major"); sizeMajor = r.readSInt32() }
                7 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.size_minor"); sizeMinor = r.readSInt32() }
                8 -> { r.expectWireType(wireType, WireType.VARINT, "TouchHold.pressure"); pressure = r.readSInt32() }
                9 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "TouchHold.stats"); stats.add(readHoldStats(r.readSubMessageReader())) }
                else -> r.skipField(wireType)
            }
        }

        return TouchHold(
            startTimeUs = startTimeUs,
            endTimeUs = endTimeUs,
            sampleCount = sampleCount,
            x = x,
            y = y,
            sizeMajor = sizeMajor,
            sizeMinor = sizeMinor,
            pressure = pressure,
            stats = stats,
        )
    }

    private fun readScreenRecord(r: ProtoReader): ScreenRecord {
        var timeUs = 0L
        var widthPx = 0
        var heightPx = 0
        var densityDpi = 0f
        var orientation: Orientation = Orientation.ORIENTATION_UNSPECIFIED
        var origin: CoordinateOrigin = CoordinateOrigin.COORDINATE_ORIGIN_UNSPECIFIED

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "ScreenRecord.time_us"); timeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "ScreenRecord.width_px"); widthPx = r.readUInt32() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "ScreenRecord.height_px"); heightPx = r.readUInt32() }
                4 -> { r.expectWireType(wireType, WireType.FIXED32, "ScreenRecord.density_dpi"); densityDpi = r.readFloat() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "ScreenRecord.orientation"); orientation = Orientation.fromProtoNumber(r.readEnum()) }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "ScreenRecord.origin"); origin = CoordinateOrigin.fromProtoNumber(r.readEnum()) }
                else -> r.skipField(wireType)
            }
        }

        return ScreenRecord(
            timeUs = timeUs,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            orientation = orientation,
            origin = origin,
        )
    }

    private fun readValue(r: ProtoReader): Value {
        var value: Value = Value.Empty
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Value.text"); value = Value.Text(r.readString()) }
                2 -> { r.expectWireType(wireType, WireType.FIXED64, "Value.number"); value = Value.Number(r.readDouble()) }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "Value.flag"); value = Value.Flag(r.readBool()) }
                else -> r.skipField(wireType)
            }
        }
        return value
    }

    private fun readValueMapEntry(r: ProtoReader): Pair<String, Value> {
        var key = ""
        var value: Value = Value.Empty
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "map.key"); key = r.readString() }
                2 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "map.value"); value = readValue(r.readSubMessageReader()) }
                else -> r.skipField(wireType)
            }
        }
        return key to value
    }

    private fun readGameEvent(r: ProtoReader): GameEvent {
        var timeUs = 0L
        var name = ""
        val fields = linkedMapOf<String, Value>()
        var outcome: String? = null
        var spanName = ""
        var spanMarker: SpanMarker = SpanMarker.SPAN_MARKER_UNSPECIFIED
        var spanNumber = 0
        var order = 0

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "GameEvent.time_us"); timeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "GameEvent.name"); name = r.readString() }
                3 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "GameEvent.fields")
                    val (k, v) = readValueMapEntry(r.readSubMessageReader())
                    fields[k] = v
                }
                4 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "GameEvent.outcome"); outcome = r.readString() }
                5 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "GameEvent.span_name"); spanName = r.readString() }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "GameEvent.span_marker"); spanMarker = SpanMarker.fromProtoNumber(r.readEnum()) }
                7 -> { r.expectWireType(wireType, WireType.VARINT, "GameEvent.span_number"); spanNumber = r.readUInt32() }
                8 -> { r.expectWireType(wireType, WireType.VARINT, "GameEvent.order"); order = r.readUInt32() }
                else -> r.skipField(wireType)
            }
        }

        return GameEvent(
            timeUs = timeUs,
            name = name,
            fields = fields,
            outcome = outcome,
            spanName = spanName,
            spanMarker = spanMarker,
            spanNumber = spanNumber,
            order = order,
        )
    }

    private fun readStateRecord(r: ProtoReader): StateRecord {
        var timeUs = 0L
        var kind: StateKind = StateKind.STATE_KIND_UNSPECIFIED
        val values = linkedMapOf<String, Value>()
        val removedKeys = mutableListOf<String>()
        var order = 0
        var version = 0

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "StateRecord.time_us"); timeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "StateRecord.kind"); kind = StateKind.fromProtoNumber(r.readEnum()) }
                3 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "StateRecord.values")
                    val (k, v) = readValueMapEntry(r.readSubMessageReader())
                    values[k] = v
                }
                4 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "StateRecord.removed_keys"); removedKeys.add(r.readString()) }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "StateRecord.order"); order = r.readUInt32() }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "StateRecord.version"); version = r.readUInt32() }
                else -> r.skipField(wireType)
            }
        }

        return StateRecord(
            timeUs = timeUs,
            kind = kind,
            values = values,
            removedKeys = removedKeys,
            order = order,
            version = version,
        )
    }

    private fun readCarried(r: ProtoReader): Carried {
        var state: State? = null
        var openSpans: OpenSpans? = null
        var lastEventTimeUs: Long? = null

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Carried.state"); state = readState(r.readSubMessageReader()) }
                2 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Carried.open_spans"); openSpans = readOpenSpans(r.readSubMessageReader()) }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "Carried.last_event_time_us"); lastEventTimeUs = r.readSInt64() }
                else -> r.skipField(wireType)
            }
        }

        return Carried(
            state = state,
            openSpans = openSpans,
            lastEventTimeUs = lastEventTimeUs,
        )
    }

    private fun readState(r: ProtoReader): State {
        val values = linkedMapOf<String, Value>()
        var version = 0
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> {
                    r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "State.values")
                    val (k, v) = readValueMapEntry(r.readSubMessageReader())
                    values[k] = v
                }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "State.version"); version = r.readUInt32() }
                else -> r.skipField(wireType)
            }
        }
        return State(values = values, version = version)
    }

    private fun readOpenSpans(r: ProtoReader): OpenSpans {
        val spans = mutableListOf<OpenSpan>()
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "OpenSpans.spans"); spans.add(readOpenSpan(r.readSubMessageReader())) }
                else -> r.skipField(wireType)
            }
        }
        return OpenSpans(spans = spans)
    }

    private fun readOpenSpan(r: ProtoReader): OpenSpan {
        var name = ""
        var number = 0
        var openedTimeUs = 0L
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "OpenSpan.name"); name = r.readString() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "OpenSpan.number"); number = r.readUInt32() }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "OpenSpan.opened_time_us"); openedTimeUs = r.readSInt64() }
                else -> r.skipField(wireType)
            }
        }
        return OpenSpan(name = name, number = number, openedTimeUs = openedTimeUs)
    }

    private fun readLifecycleRecord(r: ProtoReader): LifecycleRecord {
        var timeUs = 0L
        var kind: LifecycleKind = LifecycleKind.LIFECYCLE_KIND_UNSPECIFIED
        var clockStepS = 0L
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "LifecycleRecord.time_us"); timeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "LifecycleRecord.kind"); kind = LifecycleKind.fromProtoNumber(r.readEnum()) }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "LifecycleRecord.clock_step_s"); clockStepS = r.readSInt64() }
                else -> r.skipField(wireType)
            }
        }
        return LifecycleRecord(timeUs = timeUs, kind = kind, clockStepS = clockStepS)
    }

    private fun readGapRecord(r: ProtoReader): GapRecord {
        var signal: GapSignal = GapSignal.GAP_SIGNAL_UNSPECIFIED
        var clock: Clock = Clock.CLOCK_UNSPECIFIED
        var startTimeUs = 0L
        var endTimeUs = 0L
        var cause: GapCause = GapCause.GAP_CAUSE_UNSPECIFIED
        var batchesDropped = 0
        var firstDroppedSequence = 0L

        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.signal"); signal = GapSignal.fromProtoNumber(r.readEnum()) }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.clock"); clock = Clock.fromProtoNumber(r.readEnum()) }
                3 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.start_time_us"); startTimeUs = r.readSInt64() }
                4 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.end_time_us"); endTimeUs = r.readSInt64() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.cause"); cause = GapCause.fromProtoNumber(r.readEnum()) }
                6 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.batches_dropped"); batchesDropped = r.readUInt32() }
                7 -> { r.expectWireType(wireType, WireType.VARINT, "GapRecord.first_dropped_sequence"); firstDroppedSequence = r.readUInt64() }
                else -> r.skipField(wireType)
            }
        }

        return GapRecord(
            signal = signal,
            clock = clock,
            startTimeUs = startTimeUs,
            endTimeUs = endTimeUs,
            cause = cause,
            batchesDropped = batchesDropped,
            firstDroppedSequence = firstDroppedSequence,
        )
    }

    private fun readDiagnostic(r: ProtoReader): Diagnostic {
        var timeUs = 0L
        var severity: Severity = Severity.SEVERITY_UNSPECIFIED
        var code = ""
        var detail = ""
        var count = 0
        while (!r.isAtEnd) {
            val tag = r.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7
            when (fieldNumber) {
                1 -> { r.expectWireType(wireType, WireType.VARINT, "Diagnostic.time_us"); timeUs = r.readSInt64() }
                2 -> { r.expectWireType(wireType, WireType.VARINT, "Diagnostic.severity"); severity = Severity.fromProtoNumber(r.readEnum()) }
                3 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Diagnostic.code"); code = r.readString() }
                4 -> { r.expectWireType(wireType, WireType.LENGTH_DELIMITED, "Diagnostic.detail"); detail = r.readString() }
                5 -> { r.expectWireType(wireType, WireType.VARINT, "Diagnostic.count"); count = r.readUInt32() }
                else -> r.skipField(wireType)
            }
        }
        return Diagnostic(
            timeUs = timeUs,
            severity = severity,
            code = code,
            detail = detail,
            count = count,
        )
    }

    // endregion
}
