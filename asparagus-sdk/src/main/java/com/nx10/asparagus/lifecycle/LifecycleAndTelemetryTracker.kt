package com.nx10.asparagus.lifecycle

import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapRecord
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.LifecycleKind
import com.nx10.asparagus.contract.LifecycleRecord
import com.nx10.asparagus.time.ResumeDecision
import com.nx10.asparagus.time.SessionClock

data class LifecycleWindowFlush(
    val lifecycle: List<LifecycleRecord>,
    val gaps: List<GapRecord>,
)

/**
 * Thread-safe tracker for app lifecycle transitions (`PAUSED`, `RESUMED`, `CLOCK_STEPPED`,
 * `FOCUS_*`, `QUITTING`) and game-initiated `stopTelemetry()` / `startTelemetry()` pauses
 * (`GAP_CAUSE_CAPTURE_STOPPED`).
 */
class LifecycleAndTelemetryTracker(
    private val sessionClock: SessionClock,
) {
    private val lock = Any()

    private var _isPaused: Boolean = false
    private var _hasWindowFocus: Boolean = true
    private var _isTelemetryEnabled: Boolean = true
    private var telemetryStoppedSegmentStartUs: Long? = null

    private val windowLifecycle = mutableListOf<LifecycleRecord>()
    private val windowGaps = mutableListOf<GapRecord>()

    val isPaused: Boolean
        get() = synchronized(lock) { _isPaused }

    val hasWindowFocus: Boolean
        get() = synchronized(lock) { _hasWindowFocus }

    val isTelemetryEnabled: Boolean
        get() = synchronized(lock) { _isTelemetryEnabled }

    /**
     * Marks the app as backgrounded/paused. Returns `true` when transitioning into paused state
     * so the caller knows to close the current batch with `CLOSE_REASON_PAUSED`.
     */
    fun onPaused(): Boolean = synchronized(lock) {
        if (_isPaused) return false
        _isPaused = true
        sessionClock.onBackgrounded()
        windowLifecycle.add(
            LifecycleRecord(
                timeUs = sessionClock.unityUs(),
                kind = LifecycleKind.LIFECYCLE_KIND_PAUSED,
            ),
        )
        true
    }

    /**
     * Evaluates return to foreground against [newSessionAfterS].
     *
     * On [ResumeDecision.ContinueSession], records any `LIFECYCLE_KIND_CLOCK_STEPPED` record first,
     * followed by `LIFECYCLE_KIND_RESUMED`. On [ResumeDecision.RotateSession], appends nothing to
     * the expired session.
     */
    fun onResumed(newSessionAfterS: Int): ResumeDecision = synchronized(lock) {
        val wasPaused = _isPaused
        _isPaused = false
        val decision = sessionClock.onForegrounded(newSessionAfterS)

        if (!wasPaused) {
            return decision
        }

        when (decision) {
            is ResumeDecision.ContinueSession -> {
                decision.clockStepRecord?.let { step ->
                    windowLifecycle.add(step)
                }
                windowLifecycle.add(
                    LifecycleRecord(
                        timeUs = sessionClock.unityUs(),
                        kind = LifecycleKind.LIFECYCLE_KIND_RESUMED,
                    ),
                )
            }

            is ResumeDecision.RotateSession -> {
                // Do not append records to an expired session; caller starts a fresh session.
            }
        }
        decision
    }

    /**
     * Records window focus changes, deduplicating repeated identical states.
     */
    fun onFocusChanged(focused: Boolean) = synchronized(lock) {
        if (_hasWindowFocus == focused) return@synchronized
        _hasWindowFocus = focused
        windowLifecycle.add(
            LifecycleRecord(
                timeUs = sessionClock.unityUs(),
                kind = if (focused) {
                    LifecycleKind.LIFECYCLE_KIND_FOCUS_GAINED
                } else {
                    LifecycleKind.LIFECYCLE_KIND_FOCUS_LOST
                },
            ),
        )
    }

    /**
     * Records app quitting (`LIFECYCLE_KIND_QUITTING`).
     */
    fun onQuitting() = synchronized(lock) {
        windowLifecycle.add(
            LifecycleRecord(
                timeUs = sessionClock.unityUs(),
                kind = LifecycleKind.LIFECYCLE_KIND_QUITTING,
            ),
        )
    }

    /**
     * Game-requested stop of telemetry capture (`02-unity-sdk.md` line 401).
     * Returns `true` when transitioning from enabled -> stopped so the caller closes the batch
     * with `CLOSE_REASON_CAPTURE_STOPPED`.
     */
    fun stopTelemetry(): Boolean = synchronized(lock) {
        if (!_isTelemetryEnabled) return false
        val nowUs = sessionClock.unityUs()
        _isTelemetryEnabled = false
        telemetryStoppedSegmentStartUs = nowUs
        windowLifecycle.add(
            LifecycleRecord(
                timeUs = nowUs,
                kind = LifecycleKind.LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME,
            ),
        )
        true
    }

    /**
     * Game-requested resumption of telemetry capture.
     * Emits the final `GAP_CAUSE_CAPTURE_STOPPED` gap segment and `LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME`.
     */
    fun startTelemetry(): Boolean = synchronized(lock) {
        if (_isTelemetryEnabled) return false
        val nowUs = sessionClock.unityUs()
        _isTelemetryEnabled = true
        val segStart = telemetryStoppedSegmentStartUs
        telemetryStoppedSegmentStartUs = null
        if (segStart != null) {
            windowGaps.add(
                GapRecord(
                    signal = GapSignal.GAP_SIGNAL_EVERYTHING,
                    clock = Clock.CLOCK_UNITY,
                    startTimeUs = segStart,
                    endTimeUs = maxOf(segStart, nowUs),
                    cause = GapCause.GAP_CAUSE_CAPTURE_STOPPED,
                ),
            )
        }
        windowLifecycle.add(
            LifecycleRecord(
                timeUs = nowUs,
                kind = LifecycleKind.LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME,
            ),
        )
        true
    }

    /**
     * Live W8-26 counter of gap records accumulated in the current window.
     */
    fun currentGapCount(): Int = synchronized(lock) {
        val pendingStoppedGap = if (!_isTelemetryEnabled) {
            val segStart = telemetryStoppedSegmentStartUs
            if (segStart != null && sessionClock.unityUs() > segStart) 1 else 0
        } else {
            0
        }
        windowGaps.size + pendingStoppedGap
    }

    /**
     * Flushes the current window's lifecycle and gap records.
     * If telemetry remains stopped across a window boundary and time has elapsed, emits a
     * `GAP_CAUSE_CAPTURE_STOPPED` segment for `[segmentStart, nowUnityUs]` and advances the segment start.
     */
    fun flushWindow(): LifecycleWindowFlush = synchronized(lock) {
        if (!_isTelemetryEnabled) {
            val segStart = telemetryStoppedSegmentStartUs
            val nowUs = sessionClock.unityUs()
            if (segStart != null && nowUs > segStart) {
                windowGaps.add(
                    GapRecord(
                        signal = GapSignal.GAP_SIGNAL_EVERYTHING,
                        clock = Clock.CLOCK_UNITY,
                        startTimeUs = segStart,
                        endTimeUs = nowUs,
                        cause = GapCause.GAP_CAUSE_CAPTURE_STOPPED,
                    ),
                )
                telemetryStoppedSegmentStartUs = nowUs
            }
        }

        val result = LifecycleWindowFlush(
            lifecycle = windowLifecycle.toList(),
            gaps = windowGaps.toList(),
        )
        windowLifecycle.clear()
        windowGaps.clear()
        result
    }
}
