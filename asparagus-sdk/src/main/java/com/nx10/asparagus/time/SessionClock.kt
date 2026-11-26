package com.nx10.asparagus.time

import com.nx10.asparagus.contract.ClockReading
import com.nx10.asparagus.contract.LifecycleKind
import com.nx10.asparagus.contract.LifecycleRecord
import com.nx10.asparagus.contract.ServerAnchor

data class WindowBounds(
    val windowStartUs: Long,
    val windowLengthS: Int,
    val closedUs: Long,
)

sealed interface ResumeDecision {
    /**
     * Player returned within `new_session_after_s`. If the steady clock lagged behind wall time
     * by >= 1s during deep sleep, [clockStepRecord] holds the `LIFECYCLE_KIND_CLOCK_STEPPED` record
     * to include in the next batch.
     */
    data class ContinueSession(
        val awaySeconds: Long,
        val clockStepRecord: LifecycleRecord? = null,
    ) : ResumeDecision

    /**
     * Player was away for strictly more than `new_session_after_s`; the SDK must start a new session.
     */
    data class RotateSession(
        val awaySeconds: Long,
    ) : ResumeDecision
}

/**
 * Four-clock timekeeper (`session_us`, `unity_us`, `sensor_us`, `wall_utc_ms`) per `batch.proto`
 * and `02-unity-sdk.md` ("Time (S6)").
 */
class SessionClock(
    private val timeSource: MonotonicTimeSource = AndroidTimeSource,
    private val appStartElapsedNanos: Long = timeSource.elapsedRealtimeNanos(),
) {
    private val lock = Any()

    private var anchored: Boolean = false
    private var anchorSessionUs: Long = 0L
    private var anchorElapsedNanos: Long = appStartElapsedNanos
    private var accumulatedClockStepUs: Long = 0L
    private var _serverAnchor: ServerAnchor? = null
    private var _firstWindowStartUs: Long = 0L

    private var backgroundedElapsedNanos: Long? = null
    private var backgroundedWallUtcMs: Long? = null

    val serverAnchor: ServerAnchor?
        get() = synchronized(lock) { _serverAnchor }

    val firstWindowStartUs: Long
        get() = synchronized(lock) { _firstWindowStartUs }

    /**
     * Anchors the session clock from the server time returned by session start.
     */
    fun anchor(
        serverUtcMs: Long,
        roundTripMs: Int,
        nativeSensorRunning: Boolean = false,
    ): ServerAnchor = synchronized(lock) {
        val nowNanos = timeSource.elapsedRealtimeNanos()
        val nowWallMs = timeSource.wallUtcMs()
        val effectiveServerUtcMs = if (serverUtcMs > 0L) serverUtcMs else nowWallMs

        anchorSessionUs = effectiveServerUtcMs * 1_000L
        anchorElapsedNanos = nowNanos
        accumulatedClockStepUs = 0L
        anchored = true
        _firstWindowStartUs = wholeSecondFloorUs(anchorSessionUs)

        val receiptReading = ClockReading(
            sessionUs = anchorSessionUs,
            unityUs = unityUsAt(nowNanos),
            sensorUs = if (nativeSensorRunning) nowNanos / 1_000L else null,
            wallUtcMs = nowWallMs,
        )
        val anchor = ServerAnchor(
            serverUtcMs = effectiveServerUtcMs,
            atReceipt = receiptReading,
            roundTripMs = roundTripMs.coerceAtLeast(0),
        )
        _serverAnchor = anchor
        anchor
    }

    /**
     * Current app event clock (`CLOCK_UNITY`) in microseconds since app/SDK start.
     */
    fun unityUs(): Long = unityUsAt(timeSource.elapsedRealtimeNanos())

    /**
     * Current native sensor clock (`CLOCK_SENSOR`) in microseconds, or `null` when no native source runs.
     */
    fun sensorUs(nativeSensorRunning: Boolean): Long? =
        if (nativeSensorRunning) timeSource.elapsedRealtimeNanos() / 1_000L else null

    /**
     * Current session clock (`CLOCK_SESSION`) in microseconds since 1970-01-01T00:00:00Z.
     * Monotonic; moves only forward by whole seconds on background resume when deep-sleep lag occurs.
     */
    fun sessionUs(): Long = synchronized(lock) {
        sessionUsAtLocked(timeSource.elapsedRealtimeNanos())
    }

    /**
     * Captures a single coherent reading of all four clocks together.
     */
    fun captureClockReading(nativeSensorRunning: Boolean): ClockReading = synchronized(lock) {
        val nowNanos = timeSource.elapsedRealtimeNanos()
        val nowWallMs = timeSource.wallUtcMs()
        ClockReading(
            sessionUs = sessionUsAtLocked(nowNanos),
            unityUs = unityUsAt(nowNanos),
            sensorUs = if (nativeSensorRunning) nowNanos / 1_000L else null,
            wallUtcMs = nowWallMs,
        )
    }

    fun currentUtcOffsetMinutes(): Int =
        timeSource.utcOffsetMinutes(timeSource.wallUtcMs())

    /**
     * Checks token expiry strictly on the session clock (never the phone's wall clock).
     */
    fun isTokenExpired(tokenExpiresUtcMs: Long): Boolean {
        if (tokenExpiresUtcMs <= 0L) return false
        return (sessionUs() / 1_000L) >= tokenExpiresUtcMs
    }

    /**
     * Records entry into background for resume duration and clock-step calculation.
     */
    fun onBackgrounded() = synchronized(lock) {
        backgroundedElapsedNanos = timeSource.elapsedRealtimeNanos()
        backgroundedWallUtcMs = timeSource.wallUtcMs()
    }

    /**
     * Evaluates return to foreground against [newSessionAfterS] and steps the session clock
     * forward by whole seconds if the steady clock lagged behind wall time during sleep.
     */
    fun onForegrounded(newSessionAfterS: Int): ResumeDecision = synchronized(lock) {
        val bgNanos = backgroundedElapsedNanos
        val bgWallMs = backgroundedWallUtcMs
        backgroundedElapsedNanos = null
        backgroundedWallUtcMs = null

        if (bgNanos == null || bgWallMs == null) {
            return ResumeDecision.ContinueSession(awaySeconds = 0L, clockStepRecord = null)
        }

        val nowNanos = timeSource.elapsedRealtimeNanos()
        val nowWallMs = timeSource.wallUtcMs()

        val steadyDeltaMs = ((nowNanos - bgNanos) / 1_000_000L).coerceAtLeast(0L)
        val wallDeltaMs = (nowWallMs - bgWallMs).coerceAtLeast(0L)
        val awaySeconds = maxOf(steadyDeltaMs, wallDeltaMs) / 1_000L

        if (awaySeconds > newSessionAfterS.toLong()) {
            return ResumeDecision.RotateSession(awaySeconds = awaySeconds)
        }

        val lagMs = wallDeltaMs - steadyDeltaMs
        if (lagMs >= 1_000L) {
            val stepS = lagMs / 1_000L
            accumulatedClockStepUs += stepS * 1_000_000L
            val record = LifecycleRecord(
                timeUs = unityUsAt(nowNanos),
                kind = LifecycleKind.LIFECYCLE_KIND_CLOCK_STEPPED,
                clockStepS = stepS,
            )
            return ResumeDecision.ContinueSession(awaySeconds = awaySeconds, clockStepRecord = record)
        }

        return ResumeDecision.ContinueSession(awaySeconds = awaySeconds, clockStepRecord = null)
    }

    private fun unityUsAt(nowNanos: Long): Long =
        ((nowNanos - appStartElapsedNanos) / 1_000L).coerceAtLeast(0L)

    private fun sessionUsAtLocked(nowNanos: Long): Long {
        if (!anchored) {
            return timeSource.wallUtcMs() * 1_000L
        }
        val elapsedUs = ((nowNanos - anchorElapsedNanos) / 1_000L).coerceAtLeast(0L)
        return anchorSessionUs + elapsedUs + accumulatedClockStepUs
    }

    companion object {
        const val MICROS_PER_SECOND: Long = 1_000_000L

        /**
         * Floors [sessionTimeUs] down to a whole second boundary (`result % 1_000_000L == 0L`).
         */
        fun wholeSecondFloorUs(sessionTimeUs: Long): Long {
            val rem = sessionTimeUs % MICROS_PER_SECOND
            return if (rem >= 0L) sessionTimeUs - rem else sessionTimeUs - rem - MICROS_PER_SECOND
        }

        /**
         * Computes valid header window bounds (`windowStartUs % 1_000_000 == 0` and `windowLengthS >= 1`).
         */
        fun computeWindowBounds(
            windowStartUs: Long,
            targetLengthS: Int,
            closedSessionUs: Long,
            isFullWindow: Boolean,
        ): WindowBounds {
            val alignedStartUs = wholeSecondFloorUs(windowStartUs)
            return if (isFullWindow) {
                val lenS = targetLengthS.coerceAtLeast(1)
                WindowBounds(
                    windowStartUs = alignedStartUs,
                    windowLengthS = lenS,
                    closedUs = alignedStartUs + lenS * MICROS_PER_SECOND,
                )
            } else {
                val closedUs = maxOf(alignedStartUs, closedSessionUs)
                val elapsedUs = closedUs - alignedStartUs
                val lenS = maxOf(1, ((elapsedUs + MICROS_PER_SECOND - 1L) / MICROS_PER_SECOND).toInt())
                WindowBounds(
                    windowStartUs = alignedStartUs,
                    windowLengthS = lenS,
                    closedUs = closedUs,
                )
            }
        }
    }
}
