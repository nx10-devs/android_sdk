package com.nx10.asparagus.config

import com.nx10.asparagus.contract.MotionSource
import com.nx10.asparagus.contract.Settings
import com.nx10.asparagus.internal.codec.JsonCodec

/**
 * Resolved, non-null runtime SDK settings built from vendored `asparagus_contract/defaults.json`
 * and optionally merged with server-delivered [Settings] from session start.
 *
 * Merge semantics strictly follow `session.proto`:
 * - Explicit `0` or `false` values sent by the server are preserved.
 * - Defaults are used only when a field is `null` (omitted), when `motion_source` is
 *   `MOTION_SOURCE_UNSPECIFIED` / unknown, or when an "at least 1" field is `< 1`.
 * - `new_session_after_s` is clamped to at most `86_400` (one day).
 * - If `retry_first_wait_ms > retry_longest_wait_ms`, `retry_first_wait_ms` is capped to `retry_longest_wait_ms`.
 */
data class SdkSettings(
    val version: String,
    val accelerometerHz: Int,
    val gyroscopeHz: Int,
    val gravityHz: Int,
    val touchHz: Int,
    val motionSource: MotionSource,
    val nativeBufferS: Int,
    val nativeStartLimitMs: Int,
    val nativeStallLimitMs: Int,
    val accelerometerHoldTolerance: Int,
    val gyroscopeHoldTolerance: Int,
    val gravityHoldTolerance: Int,
    val touchHoldTolerance: Int,
    val holdRunLength: Int,
    val gapThresholdMs: Int,
    val gapThresholdPeriods: Int,
    val batchLengthS: Int,
    val compress: Boolean,
    val queueMaxBytes: Int,
    val queueMaxAgeS: Int,
    val retryFirstWaitMs: Int,
    val retryLongestWaitMs: Int,
    val pauseTouchOnKeyboard: Boolean,
    val tokenLifeS: Int,
    val newSessionAfterS: Int,
    val magnetometerHz: Int = 10,
    val magnetometerHoldTolerance: Int = 0,
) {
    /**
     * Produces a new [SdkSettings] by applying [override] onto this base configuration.
     */
    fun merge(override: Settings?): SdkSettings {
        if (override == null) return this

        val resolvedMotionSource = when (val ms = override.motionSource) {
            null, MotionSource.MOTION_SOURCE_UNSPECIFIED, is MotionSource.Unknown -> motionSource
            else -> ms
        }

        val resolvedLongestWaitMs = resolveAtLeastOne(override.retryLongestWaitMs, retryLongestWaitMs)
        val rawFirstWaitMs = resolveAtLeastOne(override.retryFirstWaitMs, retryFirstWaitMs)
        val resolvedFirstWaitMs = minOf(rawFirstWaitMs, resolvedLongestWaitMs)

        val resolvedNewSessionAfterS = minOf(
            resolveAtLeastOne(override.newSessionAfterS, newSessionAfterS),
            MAX_NEW_SESSION_AFTER_S,
        )

        return SdkSettings(
            version = override.version.ifEmpty { version },
            accelerometerHz = resolveNonNegative(override.accelerometerHz, accelerometerHz),
            gyroscopeHz = resolveNonNegative(override.gyroscopeHz, gyroscopeHz),
            gravityHz = resolveNonNegative(override.gravityHz, gravityHz),
            touchHz = resolveNonNegative(override.touchHz, touchHz),
            motionSource = resolvedMotionSource,
            nativeBufferS = resolveNonNegative(override.nativeBufferS, nativeBufferS),
            nativeStartLimitMs = resolveNonNegative(override.nativeStartLimitMs, nativeStartLimitMs),
            nativeStallLimitMs = resolveNonNegative(override.nativeStallLimitMs, nativeStallLimitMs),
            accelerometerHoldTolerance = resolveNonNegative(override.accelerometerHoldTolerance, accelerometerHoldTolerance),
            gyroscopeHoldTolerance = resolveNonNegative(override.gyroscopeHoldTolerance, gyroscopeHoldTolerance),
            gravityHoldTolerance = resolveNonNegative(override.gravityHoldTolerance, gravityHoldTolerance),
            touchHoldTolerance = resolveNonNegative(override.touchHoldTolerance, touchHoldTolerance),
            holdRunLength = resolveNonNegative(override.holdRunLength, holdRunLength),
            gapThresholdMs = resolveNonNegative(override.gapThresholdMs, gapThresholdMs),
            gapThresholdPeriods = resolveNonNegative(override.gapThresholdPeriods, gapThresholdPeriods),
            batchLengthS = resolveAtLeastOne(override.batchLengthS, batchLengthS),
            compress = override.compress ?: compress,
            queueMaxBytes = resolveNonNegative(override.queueMaxBytes, queueMaxBytes),
            queueMaxAgeS = resolveNonNegative(override.queueMaxAgeS, queueMaxAgeS),
            retryFirstWaitMs = resolvedFirstWaitMs,
            retryLongestWaitMs = resolvedLongestWaitMs,
            pauseTouchOnKeyboard = override.pauseTouchOnKeyboard ?: pauseTouchOnKeyboard,
            tokenLifeS = resolveAtLeastOne(override.tokenLifeS, tokenLifeS),
            newSessionAfterS = resolvedNewSessionAfterS,
            magnetometerHz = resolveNonNegative(override.magnetometerHz, magnetometerHz),
            magnetometerHoldTolerance = resolveNonNegative(override.magnetometerHoldTolerance, magnetometerHoldTolerance),
        )
    }

    companion object {
        const val DEFAULTS_RESOURCE_PATH = "asparagus_contract/defaults.json"
        const val MAX_NEW_SESSION_AFTER_S = 86_400

        private fun resolveNonNegative(candidate: Int?, fallback: Int): Int =
            if (candidate != null && candidate >= 0) candidate else fallback

        private fun resolveAtLeastOne(candidate: Int?, fallback: Int): Int =
            if (candidate != null && candidate >= 1) candidate else fallback

        /**
         * Loads the 24 built-in defaults from the bundled `asparagus_contract/defaults.json`.
         */
        fun loadDefaults(classLoader: ClassLoader? = SdkSettings::class.java.classLoader): SdkSettings {
            val stream = classLoader?.getResourceAsStream(DEFAULTS_RESOURCE_PATH)
                ?: error("Missing bundled contract resource: $DEFAULTS_RESOURCE_PATH")
            val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromDefaultsJson(json)
        }

        fun fromDefaultsJson(json: String): SdkSettings {
            val raw = JsonCodec.decodeSettings(json)
            val motionSource = when (val ms = raw.motionSource) {
                null, MotionSource.MOTION_SOURCE_UNSPECIFIED, is MotionSource.Unknown ->
                    error("defaults.json missing valid motion_source")
                else -> ms
            }
            val retryLongest = requireNotNull(raw.retryLongestWaitMs) { "defaults.json missing retry_longest_wait_ms" }
            val retryFirst = minOf(
                requireNotNull(raw.retryFirstWaitMs) { "defaults.json missing retry_first_wait_ms" },
                retryLongest,
            )
            val newSessionAfter = minOf(
                requireNotNull(raw.newSessionAfterS) { "defaults.json missing new_session_after_s" },
                MAX_NEW_SESSION_AFTER_S,
            )

            return SdkSettings(
                version = raw.version,
                accelerometerHz = requireNotNull(raw.accelerometerHz) { "defaults.json missing accelerometer_hz" },
                gyroscopeHz = requireNotNull(raw.gyroscopeHz) { "defaults.json missing gyroscope_hz" },
                gravityHz = requireNotNull(raw.gravityHz) { "defaults.json missing gravity_hz" },
                touchHz = requireNotNull(raw.touchHz) { "defaults.json missing touch_hz" },
                motionSource = motionSource,
                nativeBufferS = requireNotNull(raw.nativeBufferS) { "defaults.json missing native_buffer_s" },
                nativeStartLimitMs = requireNotNull(raw.nativeStartLimitMs) { "defaults.json missing native_start_limit_ms" },
                nativeStallLimitMs = requireNotNull(raw.nativeStallLimitMs) { "defaults.json missing native_stall_limit_ms" },
                accelerometerHoldTolerance = requireNotNull(raw.accelerometerHoldTolerance) { "defaults.json missing accelerometer_hold_tolerance" },
                gyroscopeHoldTolerance = requireNotNull(raw.gyroscopeHoldTolerance) { "defaults.json missing gyroscope_hold_tolerance" },
                gravityHoldTolerance = requireNotNull(raw.gravityHoldTolerance) { "defaults.json missing gravity_hold_tolerance" },
                touchHoldTolerance = requireNotNull(raw.touchHoldTolerance) { "defaults.json missing touch_hold_tolerance" },
                holdRunLength = requireNotNull(raw.holdRunLength) { "defaults.json missing hold_run_length" },
                gapThresholdMs = requireNotNull(raw.gapThresholdMs) { "defaults.json missing gap_threshold_ms" },
                gapThresholdPeriods = requireNotNull(raw.gapThresholdPeriods) { "defaults.json missing gap_threshold_periods" },
                batchLengthS = requireNotNull(raw.batchLengthS) { "defaults.json missing batch_length_s" },
                compress = requireNotNull(raw.compress) { "defaults.json missing compress" },
                queueMaxBytes = requireNotNull(raw.queueMaxBytes) { "defaults.json missing queue_max_bytes" },
                queueMaxAgeS = requireNotNull(raw.queueMaxAgeS) { "defaults.json missing queue_max_age_s" },
                retryFirstWaitMs = retryFirst,
                retryLongestWaitMs = retryLongest,
                pauseTouchOnKeyboard = requireNotNull(raw.pauseTouchOnKeyboard) { "defaults.json missing pause_touch_on_keyboard" },
                tokenLifeS = requireNotNull(raw.tokenLifeS) { "defaults.json missing token_life_s" },
                newSessionAfterS = newSessionAfter,
                magnetometerHz = requireNotNull(raw.magnetometerHz) { "defaults.json missing magnetometer_hz" },
                magnetometerHoldTolerance = requireNotNull(raw.magnetometerHoldTolerance) { "defaults.json missing magnetometer_hold_tolerance" },
            )
        }
    }
}
