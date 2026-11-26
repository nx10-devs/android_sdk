package com.nx10.asparagus.time

import com.nx10.asparagus.contract.LifecycleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClockTest {

    private class FakeTimeSource(
        var elapsedNanos: Long,
        var wallMs: Long,
        var offsetMinutes: Int = 60,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = offsetMinutes
    }

    @Test
    fun `anchor sets ServerAnchor, whole-second window alignment, unityUs from app start, and sensorUs optionality`() {
        val fake = FakeTimeSource(
            elapsedNanos = 5_000_000_000_000L, // 5000s since boot
            wallMs = 1_795_380_913_450L,
            offsetMinutes = -300,
        )
        val clock = SessionClock(timeSource = fake)
        assertEquals(0L, clock.unityUs())

        // Advance 250ms before session start response arrives
        fake.elapsedNanos += 250_000_000L
        fake.wallMs += 250L

        val serverUtcMs = 1_795_380_913_789L
        val anchor = clock.anchor(serverUtcMs = serverUtcMs, roundTripMs = 84, nativeSensorRunning = false)

        assertEquals(serverUtcMs, anchor.serverUtcMs)
        assertEquals(84, anchor.roundTripMs)
        assertEquals(serverUtcMs * 1_000L, anchor.atReceipt?.sessionUs)
        assertEquals(250_000L, anchor.atReceipt?.unityUs)
        assertNull("sensorUs must be null when native sensor is not running", anchor.atReceipt?.sensorUs)
        assertEquals(fake.wallMs, anchor.atReceipt?.wallUtcMs)

        // First window starts on a whole second
        assertEquals(0L, clock.firstWindowStartUs % 1_000_000L)
        assertEquals(1_795_380_913_000_000L, clock.firstWindowStartUs)

        // Advance 1.5s and check reading with native sensor running
        fake.elapsedNanos += 1_500_000_000L
        fake.wallMs += 1_500L
        val reading = clock.captureClockReading(nativeSensorRunning = true)
        assertEquals(serverUtcMs * 1_000L + 1_500_000L, reading.sessionUs)
        assertEquals(1_750_000L, reading.unityUs)
        assertEquals(fake.elapsedNanos / 1_000L, reading.sensorUs)
        assertEquals(-300, clock.currentUtcOffsetMinutes())
    }

    @Test
    fun `isTokenExpired judges strictly on session clock and ignores skewed device wall clock`() {
        val fake = FakeTimeSource(
            elapsedNanos = 1_000_000_000L,
            wallMs = 1_700_000_000_000L,
        )
        val clock = SessionClock(timeSource = fake)
        val serverNowMs = 1_795_000_000_000L
        val tokenExpiresMs = serverNowMs + 60_000L // expires in 60s
        clock.anchor(serverUtcMs = serverNowMs, roundTripMs = 20)

        // Device wall clock jumps +10 hours into the future — token is STILL valid on session clock!
        fake.wallMs = tokenExpiresMs + 36_000_000L
        assertFalse(clock.isTokenExpired(tokenExpiresMs))

        // Advance monotonic steady clock by 59.999s -> still valid
        fake.elapsedNanos += 59_999_000_000L
        assertFalse(clock.isTokenExpired(tokenExpiresMs))

        // Advance monotonic steady clock by 1ms -> now at expiry
        fake.elapsedNanos += 1_000_000L
        assertTrue(clock.isTokenExpired(tokenExpiresMs))
    }

    @Test
    fun `computeWindowBounds enforces whole-second start and windowLengthS at least 1`() {
        val full = SessionClock.computeWindowBounds(
            windowStartUs = 1_795_380_913_500_000L,
            targetLengthS = 10,
            closedSessionUs = 1_795_380_923_500_000L,
            isFullWindow = true,
        )
        assertEquals(1_795_380_913_000_000L, full.windowStartUs)
        assertEquals(10, full.windowLengthS)
        assertEquals(1_795_380_923_000_000L, full.closedUs)

        // Early close after 3.2s -> windowLengthS = 4
        val early = SessionClock.computeWindowBounds(
            windowStartUs = 1_795_380_913_000_000L,
            targetLengthS = 10,
            closedSessionUs = 1_795_380_916_200_000L,
            isFullWindow = false,
        )
        assertEquals(1_795_380_913_000_000L, early.windowStartUs)
        assertEquals(4, early.windowLengthS)
        assertEquals(1_795_380_916_200_000L, early.closedUs)

        // Immediate close (0us elapsed) -> windowLengthS still >= 1
        val immediate = SessionClock.computeWindowBounds(
            windowStartUs = 1_795_380_913_000_000L,
            targetLengthS = 10,
            closedSessionUs = 1_795_380_913_000_000L,
            isFullWindow = false,
        )
        assertEquals(1, immediate.windowLengthS)
    }

    @Test
    fun `background resume steps session clock by whole seconds on steady-clock lag and rotates past newSessionAfterS`() {
        val fake = FakeTimeSource(
            elapsedNanos = 10_000_000_000L,
            wallMs = 1_795_000_000_000L,
        )
        val clock = SessionClock(timeSource = fake)
        clock.anchor(serverUtcMs = 1_795_000_000_000L, roundTripMs = 10)

        // Background for 10s wall time, but steady clock only progressed 7.3s (lag = 2.7s -> step 2 whole seconds)
        clock.onBackgrounded()
        fake.elapsedNanos += 7_300_000_000L
        fake.wallMs += 10_000L

        val decision1 = clock.onForegrounded(newSessionAfterS = 1800)
        assertTrue(decision1 is ResumeDecision.ContinueSession)
        val cont1 = decision1 as ResumeDecision.ContinueSession
        assertEquals(10L, cont1.awaySeconds)
        val stepRecord = cont1.clockStepRecord
        assertNotNull(stepRecord)
        assertEquals(LifecycleKind.LIFECYCLE_KIND_CLOCK_STEPPED, stepRecord!!.kind)
        assertEquals(2L, stepRecord.clockStepS)
        assertEquals(7_300_000L, stepRecord.timeUs)
        // Session clock moved forward by steady delta (7.3s) + 2 whole seconds = 9.3s
        assertEquals(1_795_000_009_300_000L, clock.sessionUs())

        // Short background with no lag -> ContinueSession with null clockStepRecord
        clock.onBackgrounded()
        fake.elapsedNanos += 5_000_000_000L
        fake.wallMs += 5_400L // lag 400ms < 1000ms
        val decision2 = clock.onForegrounded(newSessionAfterS = 1800) as ResumeDecision.ContinueSession
        assertNull(decision2.clockStepRecord)

        // Exactly at newSessionAfterS (1800s) -> continues session ("more than" rotates)
        clock.onBackgrounded()
        fake.elapsedNanos += 1_800_000_000_000L
        fake.wallMs += 1_800_000L
        val decisionAtBoundary = clock.onForegrounded(newSessionAfterS = 1800)
        assertTrue(decisionAtBoundary is ResumeDecision.ContinueSession)

        // Away for 1801s (> 1800s) -> RotateSession
        clock.onBackgrounded()
        fake.elapsedNanos += 1_801_000_000_000L
        fake.wallMs += 1_801_000L
        val decisionRotate = clock.onForegrounded(newSessionAfterS = 1800)
        assertEquals(ResumeDecision.RotateSession(awaySeconds = 1801L), decisionRotate)
    }
}
