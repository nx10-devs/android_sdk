package com.nx10.asparagus.events

import com.nx10.asparagus.contract.Carried
import com.nx10.asparagus.contract.GameEvent
import com.nx10.asparagus.contract.OpenSpan
import com.nx10.asparagus.contract.OpenSpans
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.SpanMarker
import com.nx10.asparagus.contract.State
import com.nx10.asparagus.contract.StateKind
import com.nx10.asparagus.contract.StateRecord
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.time.SessionClock

data class EventStateWindowFlush(
    val carried: Carried,
    val events: List<GameEvent>,
    val stateRecords: List<StateRecord>,
)

/**
 * Thread-safe, session-scoped game event, span, and state engine enforcing all 17 rules of
 * `asparagus_contract/events/README.md`, unified per-window `order` numbering, session-scoped
 * `StateRecord.version`, and window-opening [Carried] snapshots.
 */
class SessionEventStateTracker(
    private val sessionClock: SessionClock,
    private val catalog: CoreEventsCatalog = CoreEventsCatalog.loadDefault(),
    private val diagnostics: DiagnosticsAggregator? = null,
    private val maxStateKeys: Int = DEFAULT_MAX_STATE_KEYS,
) : EventSink {
    init {
        require(maxStateKeys >= 1) { "maxStateKeys must be >= 1: $maxStateKeys" }
    }

    private data class TrackedOpenSpan(
        val name: String,
        val number: Int,
        val openedTimeUs: Long,
        val openedByCoreRole: Boolean,
    )

    private val lock = Any()

    // Session-scoped span state (Rule 10 & 11: persists across window flushes, pauses, and telemetry stops)
    private var nextSpanNumber: Int = 1
    private val openSpansByName = LinkedHashMap<String, TrackedOpenSpan>()

    // Session-scoped game & SDK-managed state
    private val currentState = LinkedHashMap<String, Value>()
    private val sdkManagedState = LinkedHashMap<String, Value>()
    private var stateVersion: Int = 0
    private var lastEventTimeUs: Long? = null

    // Monotonic timestamp guard across events & state mutations
    private var hasLastRecordTimeUs: Boolean = false
    private var lastRecordTimeUs: Long = 0L

    // Window-scoped call order (shared between GameEvent and StateRecord, starting at 1)
    private var windowCallOrder: Int = 0
    private val windowEvents = mutableListOf<GameEvent>()
    private val windowStateRecords = mutableListOf<StateRecord>()

    // Snapshot of what was true when the current window opened (always non-null State and OpenSpans)
    private var windowOpeningCarried: Carried = Carried(
        state = State(values = emptyMap(), version = 0),
        openSpans = OpenSpans(spans = emptyList()),
        lastEventTimeUs = null,
    )

    val coreEvents: CoreEvents = CoreEvents(this)

    override fun sendEvent(
        name: String,
        fields: Map<String, Value>,
        span: String?,
    ) {
        recordEventInternal(name = name, fields = fields, outcome = null, span = span)
    }

    override fun sendOutcomeEvent(
        name: String,
        outcome: String,
        fields: Map<String, Value>,
        span: String?,
    ) {
        recordEventInternal(name = name, fields = fields, outcome = outcome, span = span)
    }

    /**
     * Returns `true` if [key] belongs to the SDK-managed `nx10_*` system state namespace.
     */
    fun isSdkManagedKey(key: String): Boolean =
        catalog.findSdkStateKey(key) != null || key.startsWith(SDK_STATE_PREFIX)

    /**
     * Records deduplicated SDK-managed system state updates (`STATE_KIND_PATCH`) sharing the
     * unified per-window `order` and session `stateVersion`. Exempt from the game's `maxStateKeys` cap.
     *
     * Returns `true` iff at least one value changed and a `StateRecord` was appended.
     */
    fun updateSdkState(values: Map<String, Value>): Boolean = synchronized(lock) {
        if (values.isEmpty()) return false
        val changed = LinkedHashMap<String, Value>(values.size)
        for ((k, v) in values) {
            if (currentState[k] != v) {
                changed[k] = v
            }
        }
        if (changed.isEmpty()) return false

        val timeUs = nextTimestampUsLocked()
        sdkManagedState.putAll(changed)
        currentState.putAll(changed)
        stateVersion++
        windowCallOrder++

        windowStateRecords.add(
            StateRecord(
                timeUs = timeUs,
                kind = StateKind.STATE_KIND_PATCH,
                values = changed,
                removedKeys = emptyList(),
                order = windowCallOrder,
                version = stateVersion,
            ),
        )
        true
    }

    /**
     * Replaces the entire game state dictionary (`STATE_KIND_PUT`) while preserving any active
     * SDK-managed (`nx10_*`) keys in both `currentState` and the emitted `StateRecord`.
     */
    fun setAttributes(attributes: Map<String, Value>) = synchronized(lock) {
        val timeUs = nextTimestampUsLocked()
        val acceptedGameKeys = LinkedHashMap<String, Value>(minOf(attributes.size, maxStateKeys))
        var trimmed = 0
        for ((k, v) in attributes) {
            if (isSdkManagedKey(k)) continue
            if (acceptedGameKeys.size < maxStateKeys) {
                acceptedGameKeys[k] = v
            } else {
                trimmed++
            }
        }
        if (trimmed > 0) {
            diagnostics?.record(
                severity = Severity.SEVERITY_WARNING,
                code = DIAGNOSTIC_STATE_KEY_LIMIT,
                detail = "PUT trimmed $trimmed key(s) past limit $maxStateKeys",
                timeUs = timeUs,
            )
        }

        val merged = LinkedHashMap<String, Value>(acceptedGameKeys.size + sdkManagedState.size)
        merged.putAll(acceptedGameKeys)
        merged.putAll(sdkManagedState)

        currentState.clear()
        currentState.putAll(merged)
        stateVersion++
        windowCallOrder++

        windowStateRecords.add(
            StateRecord(
                timeUs = timeUs,
                kind = StateKind.STATE_KIND_PUT,
                values = LinkedHashMap(merged),
                removedKeys = emptyList(),
                order = windowCallOrder,
                version = stateVersion,
            ),
        )
    }

    /**
     * Clears all game state attributes (`STATE_KIND_PUT` preserving any SDK-managed `nx10_*` keys).
     */
    fun clearAttributes() {
        setAttributes(emptyMap())
    }

    /**
     * Sets a single state attribute (`STATE_KIND_PATCH`).
     */
    fun setAttribute(key: String, value: Value) {
        patchAttributes(values = mapOf(key to value), removedKeys = emptyList())
    }

    /**
     * Removes a single state attribute (`STATE_KIND_PATCH` with `removedKeys = [key]`).
     */
    fun removeAttribute(key: String) {
        patchAttributes(values = emptyMap(), removedKeys = listOf(key))
    }

    /**
     * Applies partial game state updates and removals (`STATE_KIND_PATCH`), ignoring any
     * SDK-managed `nx10_*` keys supplied by game code.
     */
    fun patchAttributes(
        values: Map<String, Value> = emptyMap(),
        removedKeys: Collection<String> = emptyList(),
    ) = synchronized(lock) {
        val timeUs = nextTimestampUsLocked()
        val ignoredSdkKey = values.keys.any { isSdkManagedKey(it) } || removedKeys.any { isSdkManagedKey(it) }
        val effectiveRemovedKeys = removedKeys.distinct().filter { it !in values.keys && !isSdkManagedKey(it) }

        for (k in effectiveRemovedKeys) {
            currentState.remove(k)
        }

        val appliedValues = LinkedHashMap<String, Value>(values.size)
        var rejected = 0
        for ((k, v) in values) {
            if (isSdkManagedKey(k)) continue
            val gameKeyCount = currentState.size - sdkManagedState.size
            if (currentState.containsKey(k) || gameKeyCount < maxStateKeys) {
                currentState[k] = v
                appliedValues[k] = v
            } else {
                rejected++
            }
        }

        if (rejected > 0) {
            diagnostics?.record(
                severity = Severity.SEVERITY_WARNING,
                code = DIAGNOSTIC_STATE_KEY_LIMIT,
                detail = "PATCH ignored $rejected new key(s) past limit $maxStateKeys",
                timeUs = timeUs,
            )
        }

        // If every requested key was rejected due to maxStateKeys or ignored as SDK-managed, emit no no-op record.
        if (appliedValues.isEmpty() && effectiveRemovedKeys.isEmpty() && (rejected > 0 || ignoredSdkKey)) {
            return@synchronized
        }

        stateVersion++
        windowCallOrder++

        windowStateRecords.add(
            StateRecord(
                timeUs = timeUs,
                kind = StateKind.STATE_KIND_PATCH,
                values = appliedValues,
                removedKeys = effectiveRemovedKeys,
                order = windowCallOrder,
                version = stateVersion,
            ),
        )
    }

    /**
     * Live W8-26 counter of game events in the current window.
     */
    fun currentEventCount(): Int = synchronized(lock) { windowEvents.size }

    fun currentStateRecordCount(): Int = synchronized(lock) { windowStateRecords.size }

    fun currentOpenSpans(): List<OpenSpan> = synchronized(lock) {
        openSpansByName.values.map {
            OpenSpan(name = it.name, number = it.number, openedTimeUs = it.openedTimeUs)
        }
    }

    fun currentStateSnapshot(): State = synchronized(lock) {
        State(values = LinkedHashMap(currentState), version = stateVersion)
    }

    /**
     * Closes the current window, returning the window-opening [Carried] snapshot alongside the
     * window's events and state records, resets window-local lists & `order`, and captures the
     * post-window state as the next window's opening [Carried].
     */
    fun flushWindow(): EventStateWindowFlush = synchronized(lock) {
        val result = EventStateWindowFlush(
            carried = windowOpeningCarried,
            events = windowEvents.toList(),
            stateRecords = windowStateRecords.toList(),
        )
        windowEvents.clear()
        windowStateRecords.clear()
        windowCallOrder = 0
        windowOpeningCarried = Carried(
            state = State(values = LinkedHashMap(currentState), version = stateVersion),
            openSpans = OpenSpans(
                spans = openSpansByName.values.map {
                    OpenSpan(name = it.name, number = it.number, openedTimeUs = it.openedTimeUs)
                },
            ),
            lastEventTimeUs = lastEventTimeUs,
        )
        result
    }

    private fun recordEventInternal(
        name: String,
        fields: Map<String, Value>,
        outcome: String?,
        span: String?,
    ) = synchronized(lock) {
        val timeUs = nextTimestampUsLocked()
        val defensiveFields = LinkedHashMap<String, Value>(fields)

        // Rules 4 & 5: role belongs to event name; caller span wins, else core event default span.
        val coreDef = catalog.findEvent(name)
        val role = coreDef?.role ?: CoreEventRole.NONE
        val effectiveSpanName = resolveEffectiveSpanName(
            callerSpan = span,
            coreDef = coreDef,
            role = role,
            fields = defensiveFields,
        )

        // Rules 6, 7, 8, 9, 10, 14: phone-side span state machine.
        val marker: SpanMarker
        val spanNumber: Int
        if (effectiveSpanName.isEmpty()) {
            marker = SpanMarker.SPAN_MARKER_UNSPECIFIED
            spanNumber = 0
        } else {
            when (role) {
                CoreEventRole.OPENS -> {
                    // Rule 7: ends any currently open span of the same name ("no end seen") and opens a new one.
                    openSpansByName.remove(effectiveSpanName)
                    val allocated = nextSpanNumber++
                    openSpansByName[effectiveSpanName] = TrackedOpenSpan(
                        name = effectiveSpanName,
                        number = allocated,
                        openedTimeUs = timeUs,
                        openedByCoreRole = true,
                    )
                    marker = SpanMarker.SPAN_MARKER_OPEN
                    spanNumber = allocated
                }

                CoreEventRole.CLOSES -> {
                    // Rule 8: orphan close gets SPAN_MARKER_CLOSE with spanNumber = 0 (does not increment nextSpanNumber).
                    val existing = openSpansByName.remove(effectiveSpanName)
                    marker = SpanMarker.SPAN_MARKER_CLOSE
                    spanNumber = existing?.number ?: 0
                }

                CoreEventRole.NONE -> {
                    // Rule 6 & 14: roleless event opens if none open, closes if roleless-opened, or is markerless if core-opened.
                    val existing = openSpansByName[effectiveSpanName]
                    when {
                        existing == null -> {
                            val allocated = nextSpanNumber++
                            openSpansByName[effectiveSpanName] = TrackedOpenSpan(
                                name = effectiveSpanName,
                                number = allocated,
                                openedTimeUs = timeUs,
                                openedByCoreRole = false,
                            )
                            marker = SpanMarker.SPAN_MARKER_OPEN
                            spanNumber = allocated
                        }

                        !existing.openedByCoreRole -> {
                            openSpansByName.remove(effectiveSpanName)
                            marker = SpanMarker.SPAN_MARKER_CLOSE
                            spanNumber = existing.number
                        }

                        else -> {
                            marker = SpanMarker.SPAN_MARKER_UNSPECIFIED
                            spanNumber = 0
                        }
                    }
                }
            }
        }

        windowCallOrder++
        lastEventTimeUs = timeUs

        windowEvents.add(
            GameEvent(
                timeUs = timeUs,
                name = name,
                fields = defensiveFields,
                outcome = outcome,
                spanName = effectiveSpanName,
                spanMarker = marker,
                spanNumber = spanNumber,
                order = windowCallOrder,
            ),
        )
    }

    private fun resolveEffectiveSpanName(
        callerSpan: String?,
        coreDef: CoreEventDef?,
        role: CoreEventRole,
        fields: Map<String, Value>,
    ): String {
        if (!callerSpan.isNullOrEmpty()) return callerSpan
        if (role != CoreEventRole.OPENS && role != CoreEventRole.CLOSES) return ""
        val defaultSpan = coreDef?.span.orEmpty()
        if (defaultSpan == "ad") {
            val adType = (fields["type"] as? Value.Text)?.value
            return if (!adType.isNullOrEmpty()) "ad_$adType" else "ad"
        }
        return defaultSpan
    }

    private fun nextTimestampUsLocked(): Long {
        val raw = sessionClock.unityUs()
        val monotonic = if (hasLastRecordTimeUs) maxOf(lastRecordTimeUs, raw) else raw
        lastRecordTimeUs = monotonic
        hasLastRecordTimeUs = true
        return monotonic
    }

    companion object {
        const val DEFAULT_MAX_STATE_KEYS: Int = 256
        const val DIAGNOSTIC_STATE_KEY_LIMIT: String = "state_key_limit"
        const val SDK_STATE_PREFIX: String = "nx10_"
    }
}
