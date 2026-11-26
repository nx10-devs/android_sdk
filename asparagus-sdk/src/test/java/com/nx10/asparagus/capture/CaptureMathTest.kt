package com.nx10.asparagus.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMathTest {

    @Test
    fun `quantize rounds to nearest step, clamps to MAX_QUANTIZED_ABS, and handles non-finite values`() {
        assertEquals(980_665, CaptureMath.quantize(9.80665, CaptureMath.DEFAULT_MOTION_SCALE))
        assertEquals(-980_665, CaptureMath.quantize(-9.80665, CaptureMath.DEFAULT_MOTION_SCALE))
        assertEquals(5_400, CaptureMath.quantize(540.0, CaptureMath.DEFAULT_TOUCH_POSITION_SCALE))
        assertEquals(0, CaptureMath.quantize(Double.NaN, CaptureMath.DEFAULT_MOTION_SCALE))
        assertEquals(0, CaptureMath.quantize(Double.POSITIVE_INFINITY, CaptureMath.DEFAULT_MOTION_SCALE))
        assertEquals(0, CaptureMath.quantize(Double.NEGATIVE_INFINITY, CaptureMath.DEFAULT_MOTION_SCALE))

        // Extreme values clamp safely so any delta (q2 - q1) stays inside signed 32-bit sint32
        val qMax = CaptureMath.quantize(1e12, CaptureMath.DEFAULT_MOTION_SCALE)
        val qMin = CaptureMath.quantize(-1e12, CaptureMath.DEFAULT_MOTION_SCALE)
        assertEquals(CaptureMath.MAX_QUANTIZED_ABS, qMax)
        assertEquals(-CaptureMath.MAX_QUANTIZED_ABS, qMin)
        val delta: Long = qMax.toLong() - qMin.toLong()
        assertTrue(delta <= Int.MAX_VALUE.toLong())
        assertEquals(delta.toInt(), qMax - qMin)
        val negDelta: Long = qMin.toLong() - qMax.toLong()
        assertTrue(negDelta >= Int.MIN_VALUE.toLong())
        assertEquals(negDelta.toInt(), qMin - qMax)
    }

    @Test
    fun `computeSampleGapThresholdUs obeys session proto ms vs periods rules`() {
        // 100 Hz: 5 periods = 50_000 us, 250 ms = 250_000 us -> 250_000 us wins
        assertEquals(250_000L, CaptureMath.computeSampleGapThresholdUs(250, 5, 100f))

        // 10 Hz: 5 periods = 500_000 us, 250 ms = 250_000 us -> 500_000 us wins
        assertEquals(500_000L, CaptureMath.computeSampleGapThresholdUs(250, 5, 10f))

        // Single-zero cases: the non-zero threshold decides
        assertEquals(250_000L, CaptureMath.computeSampleGapThresholdUs(250, 0, 100f))
        assertEquals(50_000L, CaptureMath.computeSampleGapThresholdUs(0, 5, 100f))

        // No requested Hz: ms decides
        assertEquals(250_000L, CaptureMath.computeSampleGapThresholdUs(250, 5, 0f))

        // Both disabled -> null
        assertNull(CaptureMath.computeSampleGapThresholdUs(0, 0, 100f))
        assertNull(CaptureMath.computeSampleGapThresholdUs(0, 5, 0f))
    }
}
