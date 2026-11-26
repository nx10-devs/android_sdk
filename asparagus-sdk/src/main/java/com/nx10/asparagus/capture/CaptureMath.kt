package com.nx10.asparagus.capture

import kotlin.math.round

/**
 * Quantization and sample-gap threshold math matching `batch.proto` and `session.proto`.
 */
object CaptureMath {
    /**
     * Maximum absolute quantized integer value (`Int.MAX_VALUE / 2`).
     *
     * Clamping every quantized sample `q` to `[-MAX_QUANTIZED_ABS, +MAX_QUANTIZED_ABS]`
     * mathematically guarantees that any delta `q2 - q1` fits inside signed 32-bit `sint32`
     * (`[-2_147_483_646, +2_147_483_646]`) without wraparound.
     */
    const val MAX_QUANTIZED_ABS: Int = 1_073_741_823

    const val DEFAULT_MOTION_SCALE: Double = 1e-5
    const val DEFAULT_MAGNETOMETER_SCALE: Double = 0.01
    const val DEFAULT_TOUCH_POSITION_SCALE: Double = 0.1
    const val DEFAULT_TOUCH_SIZE_SCALE: Double = 0.01
    const val DEFAULT_TOUCH_PRESSURE_SCALE: Double = 0.001

    /**
     * Rounds `value / scale` to the nearest whole step and clamps to `[-MAX_QUANTIZED_ABS, +MAX_QUANTIZED_ABS]`.
     * Non-finite inputs (`NaN` / `±Infinity`) safely map to `0` per Priority 2.
     */
    fun quantize(value: Double, scale: Double): Int {
        require(scale > 0.0 && scale.isFinite()) { "scale must be positive and finite: $scale" }
        if (!value.isFinite()) return 0
        val scaled = value / scale
        if (!scaled.isFinite()) {
            return when {
                value > 0.0 -> MAX_QUANTIZED_ABS
                value < 0.0 -> -MAX_QUANTIZED_ABS
                else -> 0
            }
        }
        val rounded = round(scaled)
        val clamped = rounded.coerceIn(-MAX_QUANTIZED_ABS.toDouble(), MAX_QUANTIZED_ABS.toDouble())
        return clamped.toLong().toInt()
    }

    /**
     * Computes the inter-sample silence threshold in microseconds per `session.proto` lines 195-200.
     *
     * A silence `deltaUs` is a gap iff `deltaUs > thresholdUs`.
     * Returns `null` when both thresholds are disabled (`0`).
     */
    fun computeSampleGapThresholdUs(
        gapThresholdMs: Int,
        gapThresholdPeriods: Int,
        requestedHz: Float,
    ): Long? {
        val msUs = if (gapThresholdMs > 0) gapThresholdMs.toLong() * 1_000L else 0L
        val periodsUs = if (gapThresholdPeriods > 0 && requestedHz > 0f && requestedHz.isFinite()) {
            round((gapThresholdPeriods.toDouble() * 1_000_000.0) / requestedHz.toDouble()).toLong()
        } else {
            0L
        }
        return if (msUs == 0L && periodsUs == 0L) null else maxOf(msUs, periodsUs)
    }
}
