package com.nx10.asparagus.lifecycle

import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.LifecycleKind
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.ResumeDecision
import com.nx10.asparagus.time.SessionClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleAndDiagnosticsTest {

    private class FakeTimeSource(
        var elapsedNanos: Long = 10_000_000_000L,
        var wallMs: Long = 1_795_000_000_000L,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = 0
    }

    @Test
    fun `diagnosticsAggregator deduplicates by severity, code, detail summing counts and keeping earliest timeUs`() {
        val fake = FakeTimeSource()
        val clock = SessionClock(timeSource = fake, appStartElapsedNanos = fake.elapsedNanos)
        val agg = DiagnosticsAggregator(sessionClock = clock, maxDistinctPerWindow = 2)

        agg.record(Severity.SEVERITY_WARNING, "upload_400", "bad header", timeUs = 5_000L)
        agg.record(Severity.SEVERITY_WARNING, "upload_400", "bad header", timeUs = 3_000L, increment = 2)
        agg.recordAll(
            listOf(
                Diagnostic(timeUs = 7_000L, severity = Severity.SEVERITY_WARNING, code = "upload_400", detail = "bad header", count = 4),
                Diagnostic(timeUs = 8_000L, severity = Severity.SEVERITY_ERROR, code = "disk_full", detail = "no space", count = 1),
                // Third distinct key dropped due to cap = 2
                Diagnostic(timeUs = 9_000L, severity = Severity.SEVERITY_WARNING, code = "extra", detail = "dropped", count = 1),
            ),
        )

        assertEquals(2, agg.currentCount())
        val flushed = agg.flushWindow()
        assertEquals(2, flushed.size)
        assertEquals(
            Diagnostic(timeUs = 3_000L, severity = Severity.SEVERITY_WARNING, code = "upload_400", detail = "bad header", count = 7),
            flushed[0],
        )
        assertEquals(
            Diagnostic(timeUs = 8_000L, severity = Severity.SEVERITY_ERROR, code = "disk_full", detail = "no space", count = 1),
            flushed[1],
        )
        assertTrue(agg.flushWindow().isEmpty())
    }

    @Test
    fun `lifecycleTracker records PAUSED, CLOCK_STEPPED before RESUMED, and returns RotateSession past newSessionAfterS`() {
        val fake = FakeTimeSource()
        val clock = SessionClock(timeSource = fake, appStartElapsedNanos = fake.elapsedNanos)
        clock.anchor(serverUtcMs = fake.wallMs, roundTripMs = 10)
        val tracker = LifecycleAndTelemetryTracker(clock)

        assertTrue(tracker.onPaused())
        assertFalse("Duplicate pause is a no-op", tracker.onPaused())
        assertTrue(tracker.isPaused)

        // Away 12s wall time, steady clock lagged 3.4s -> clockStepS = 3
        fake.elapsedNanos += 8_600_000_000L
        fake.wallMs += 12_000L

        val decision = tracker.onResumed(newSessionAfterS = 1800)
        assertTrue(decision is ResumeDecision.ContinueSession)
        assertFalse(tracker.isPaused)

        val flush1 = tracker.flushWindow()
        assertEquals(
            listOf(
                LifecycleKind.LIFECYCLE_KIND_PAUSED,
                LifecycleKind.LIFECYCLE_KIND_CLOCK_STEPPED,
                LifecycleKind.LIFECYCLE_KIND_RESUMED,
            ),
            flush1.lifecycle.map { it.kind },
        )
        assertEquals(3L, flush1.lifecycle[1].clockStepS)

        // Now pause and stay away > newSessionAfterS (1801s) -> RotateSession, no RESUMED record appended to expired session
        assertTrue(tracker.onPaused())
        tracker.flushWindow()
        fake.elapsedNanos += 1_801_000_000_000L
        fake.wallMs += 1_801_000L

        val rotate = tracker.onResumed(newSessionAfterS = 1800)
        assertEquals(ResumeDecision.RotateSession(awaySeconds = 1801L), rotate)
        assertTrue(tracker.flushWindow().lifecycle.isEmpty())
    }

    @Test
    fun `focus deduplication, quitting, and stopTelemetry to startTelemetry emit GAP_CAUSE_CAPTURE_STOPPED across windows`() {
        val fake = FakeTimeSource()
        val clock = SessionClock(timeSource = fake, appStartElapsedNanos = fake.elapsedNanos)
        val tracker = LifecycleAndTelemetryTracker(clock)

        // Initial focus is true -> duplicate true ignored, false -> FOCUS_LOST, duplicate false ignored, true -> FOCUS_GAINED
        tracker.onFocusChanged(true)
        fake.elapsedNanos += 1_000_000_000L
        tracker.onFocusChanged(false)
        tracker.onFocusChanged(false)
        fake.elapsedNanos += 500_000_000L
        tracker.onFocusChanged(true)

        // Stop telemetry at t = 2s, immediately close batch (no zero-length gap emitted)
        fake.elapsedNanos += 500_000_000L // unityUs = 2_000_000
        assertTrue(tracker.stopTelemetry())
        assertFalse(tracker.isTelemetryEnabled)
        assertFalse("Duplicate stop is a no-op", tracker.stopTelemetry())

        val flush1 = tracker.flushWindow()
        assertEquals(
            listOf(
                LifecycleKind.LIFECYCLE_KIND_FOCUS_LOST,
                LifecycleKind.LIFECYCLE_KIND_FOCUS_GAINED,
                LifecycleKind.LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME,
            ),
            flush1.lifecycle.map { it.kind },
        )
        assertTrue(flush1.gaps.isEmpty())

        // Window 2 elapses 10s while telemetry stays stopped -> emits GAP_CAUSE_CAPTURE_STOPPED [2s, 12s]
        fake.elapsedNanos += 10_000_000_000L // unityUs = 12_000_000
        assertEquals(1, tracker.currentGapCount())
        val flush2 = tracker.flushWindow()
        assertEquals(1, flush2.gaps.size)
        assertEquals(GapSignal.GAP_SIGNAL_EVERYTHING, flush2.gaps[0].signal)
        assertEquals(Clock.CLOCK_UNITY, flush2.gaps[0].clock)
        assertEquals(2_000_000L, flush2.gaps[0].startTimeUs)
        assertEquals(12_000_000L, flush2.gaps[0].endTimeUs)
        assertEquals(GapCause.GAP_CAUSE_CAPTURE_STOPPED, flush2.gaps[0].cause)

        // Window 3: startTelemetry at t = 15s -> emits GAP [12s, 15s] + CAPTURE_STARTED_BY_GAME, then quitting
        fake.elapsedNanos += 3_000_000_000L // unityUs = 15_000_000
        assertTrue(tracker.startTelemetry())
        assertTrue(tracker.isTelemetryEnabled)
        tracker.onQuitting()

        val flush3 = tracker.flushWindow()
        assertEquals(1, flush3.gaps.size)
        assertEquals(12_000_000L, flush3.gaps[0].startTimeUs)
        assertEquals(15_000_000L, flush3.gaps[0].endTimeUs)
        assertEquals(GapCause.GAP_CAUSE_CAPTURE_STOPPED, flush3.gaps[0].cause)
        assertEquals(
            listOf(
                LifecycleKind.LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME,
                LifecycleKind.LIFECYCLE_KIND_QUITTING,
            ),
            flush3.lifecycle.map { it.kind },
        )
    }
}
