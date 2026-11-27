package com.nx10.asparagus.config

import com.nx10.asparagus.contract.MotionSource
import com.nx10.asparagus.contract.Settings
import com.nx10.asparagus.internal.codec.JsonCodec

/**
 * Resolved, non-null runtime SDK settings initialized from the SDK's built-in starting safety-net
 * defaults (Decision 159) and merged with server-delivered [Settings] from session start.
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
        const val MAX_NEW_SESSION_AFTER_S = 86_400

        private val BUILT_IN_DEFAULTS = SdkSettings(
            version = "",
            accelerometerHz = 100,
            gyroscopeHz = 100,
            gravityHz = 10,
            touchHz = 60,
            motionSource = MotionSource.MOTION_SOURCE_NATIVE,
            nativeBufferS = 120,
            nativeStartLimitMs = 1_000,
            nativeStallLimitMs = 5_000,
            accelerometerHoldTolerance = 0,
            gyroscopeHoldTolerance = 0,
            gravityHoldTolerance = 0,
            touchHoldTolerance = 0,
            holdRunLength = 10,
            gapThresholdMs = 250,
            gapThresholdPeriods = 5,
            batchLengthS = 10,
            compress = true,
            queueMaxBytes = 52_428_800,
            queueMaxAgeS = 900,
            retryFirstWaitMs = 2_000,
            retryLongestWaitMs = 300_000,
            pauseTouchOnKeyboard = false,
            tokenLifeS = 86_400,
            newSessionAfterS = 1_800,
            magnetometerHz = 10,
            magnetometerHoldTolerance = 0,
        )

        private fun resolveNonNegative(candidate: Int?, fallback: Int): Int =
            if (candidate != null && candidate >= 0) candidate else fallback

        private fun resolveAtLeastOne(candidate: Int?, fallback: Int): Int =
            if (candidate != null && candidate >= 1) candidate else fallback

        /**
         * Returns the SDK's built-in starting safety-net settings (`asparagus-contract-v7`, Decision 159).
         */
        fun builtInDefaults(): SdkSettings = BUILT_IN_DEFAULTS

        /**
         * Alias for [builtInDefaults] preserved for source compatibility.
         */
        fun loadDefaults(): SdkSettings = builtInDefaults()

        /**
         * Parses a JSON [Settings] object and merges it onto [builtInDefaults].
         */
        fun fromSettingsJson(json: String): SdkSettings =
            builtInDefaults().merge(JsonCodec.decodeSettings(json))
    }
}
