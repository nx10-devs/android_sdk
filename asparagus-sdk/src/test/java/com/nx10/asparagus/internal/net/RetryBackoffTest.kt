package com.nx10.asparagus.internal.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {

    @Test
    fun `doubling sequence caps at longestWaitMs and avoids overflow at high attempts`() {
        val noJitter = RetryBackoff(jitterSource = { 0.0 })

        assertEquals(2_000L, noJitter.computeDelayMs(0, 2_000, 300_000))
        assertEquals(4_000L, noJitter.computeDelayMs(1, 2_000, 300_000))
        assertEquals(8_000L, noJitter.computeDelayMs(2, 2_000, 300_000))
        assertEquals(16_000L, noJitter.computeDelayMs(3, 2_000, 300_000))
        assertEquals(32_000L, noJitter.computeDelayMs(4, 2_000, 300_000))
        assertEquals(64_000L, noJitter.computeDelayMs(5, 2_000, 300_000))
        assertEquals(128_000L, noJitter.computeDelayMs(6, 2_000, 300_000))
        assertEquals(256_000L, noJitter.computeDelayMs(7, 2_000, 300_000))
        assertEquals(300_000L, noJitter.computeDelayMs(8, 2_000, 300_000))
        assertEquals(300_000L, noJitter.computeDelayMs(50, 2_000, 300_000))
    }

    @Test
    fun `jitter shortens wait strictly within half to full base delay`() {
        val halfJitter = RetryBackoff(jitterSource = { 1.0 })
        val quarterJitter = RetryBackoff(jitterSource = { 0.5 })

        assertEquals(1_000L, halfJitter.computeDelayMs(0, 2_000, 300_000))
        assertEquals(2_000L, halfJitter.computeDelayMs(1, 2_000, 300_000))
        assertEquals(150_000L, halfJitter.computeDelayMs(10, 2_000, 300_000))

        assertEquals(1_500L, quarterJitter.computeDelayMs(0, 2_000, 300_000))

        // First wait above longest wait is clamped to longest wait
        val clamped = RetryBackoff(jitterSource = { 0.0 })
        assertEquals(500L, clamped.computeDelayMs(0, 5_000, 500))

        // Real random jitter always remains within [0.5 * base, 1.0 * base]
        val randomBackoff = RetryBackoff()
        repeat(200) {
            val d = randomBackoff.computeDelayMs(attempt = 2, firstWaitMs = 2_000, longestWaitMs = 300_000)
            assertTrue("Expected $d in [4000, 8000]", d in 4_000L..8_000L)
        }
    }
}
