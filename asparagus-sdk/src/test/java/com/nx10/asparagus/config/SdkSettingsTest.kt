package com.nx10.asparagus.config

import com.nx10.asparagus.contract.MotionSource
import com.nx10.asparagus.contract.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkSettingsTest {

    @Test
    fun `loadDefaults loads all 24 settings from vendored defaults json`() {
        val defaults = SdkSettings.loadDefaults()

        assertEquals("", defaults.version)
        assertEquals(100, defaults.accelerometerHz)
        assertEquals(100, defaults.gyroscopeHz)
        assertEquals(10, defaults.gravityHz)
        assertEquals(60, defaults.touchHz)
        assertEquals(MotionSource.MOTION_SOURCE_NATIVE, defaults.motionSource)
        assertEquals(120, defaults.nativeBufferS)
        assertEquals(1000, defaults.nativeStartLimitMs)
        assertEquals(5000, defaults.nativeStallLimitMs)
        assertEquals(0, defaults.accelerometerHoldTolerance)
        assertEquals(0, defaults.gyroscopeHoldTolerance)
        assertEquals(0, defaults.gravityHoldTolerance)
        assertEquals(0, defaults.touchHoldTolerance)
        assertEquals(10, defaults.holdRunLength)
        assertEquals(250, defaults.gapThresholdMs)
        assertEquals(5, defaults.gapThresholdPeriods)
        assertEquals(10, defaults.batchLengthS)
        assertEquals(true, defaults.compress)
        assertEquals(50_000_000, defaults.queueMaxBytes)
        assertEquals(900, defaults.queueMaxAgeS)
        assertEquals(2000, defaults.retryFirstWaitMs)
        assertEquals(300_000, defaults.retryLongestWaitMs)
        assertEquals(false, defaults.pauseTouchOnKeyboard)
        assertEquals(86_400, defaults.tokenLifeS)
        assertEquals(1800, defaults.newSessionAfterS)
        assertEquals(10, defaults.magnetometerHz)
        assertEquals(0, defaults.magnetometerHoldTolerance)
    }

    @Test
    fun `merge preserves explicit zeros and false while falling back only on null`() {
        val base = SdkSettings.loadDefaults()
        val merged = base.merge(
            Settings(
                version = "v42",
                accelerometerHz = 0,
                gyroscopeHz = 0,
                gravityHz = 0,
                touchHz = 0,
                motionSource = MotionSource.MOTION_SOURCE_UNITY,
                nativeBufferS = 0,
                nativeStartLimitMs = 0,
                nativeStallLimitMs = 0,
                holdRunLength = 0,
                gapThresholdMs = 0,
                gapThresholdPeriods = 0,
                compress = false,
                queueMaxBytes = 0,
                queueMaxAgeS = 0,
                pauseTouchOnKeyboard = true,
                magnetometerHz = 0,
                magnetometerHoldTolerance = 5,
            ),
        )

        assertEquals("v42", merged.version)
        assertEquals(0, merged.accelerometerHz)
        assertEquals(0, merged.gyroscopeHz)
        assertEquals(0, merged.gravityHz)
        assertEquals(0, merged.touchHz)
        assertEquals(MotionSource.MOTION_SOURCE_UNITY, merged.motionSource)
        assertEquals(0, merged.nativeBufferS)
        assertEquals(0, merged.nativeStartLimitMs)
        assertEquals(0, merged.nativeStallLimitMs)
        assertEquals(0, merged.holdRunLength)
        assertEquals(0, merged.gapThresholdMs)
        assertEquals(0, merged.gapThresholdPeriods)
        assertEquals(false, merged.compress)
        assertEquals(0, merged.queueMaxBytes)
        assertEquals(0, merged.queueMaxAgeS)
        assertEquals(true, merged.pauseTouchOnKeyboard)
        assertEquals(0, merged.magnetometerHz)
        assertEquals(5, merged.magnetometerHoldTolerance)

        // Omitted fields keep defaults
        assertEquals(10, merged.batchLengthS)
        assertEquals(2000, merged.retryFirstWaitMs)
        assertEquals(300_000, merged.retryLongestWaitMs)
        assertEquals(86_400, merged.tokenLifeS)
        assertEquals(1800, merged.newSessionAfterS)
    }

    @Test
    fun `merge enforces at-least-1 fallbacks, retry wait cap, motion source fallback, and 86400 upper bound`() {
        val base = SdkSettings.loadDefaults()

        val merged = base.merge(
            Settings(
                motionSource = MotionSource.MOTION_SOURCE_UNSPECIFIED,
                batchLengthS = 0,
                retryFirstWaitMs = 0,
                retryLongestWaitMs = 0,
                tokenLifeS = 0,
                newSessionAfterS = 0,
            ),
        )
        assertEquals(MotionSource.MOTION_SOURCE_NATIVE, merged.motionSource)
        assertEquals(10, merged.batchLengthS)
        assertEquals(2000, merged.retryFirstWaitMs)
        assertEquals(300_000, merged.retryLongestWaitMs)
        assertEquals(86_400, merged.tokenLifeS)
        assertEquals(1800, merged.newSessionAfterS)

        val capped = base.merge(
            Settings(
                motionSource = MotionSource.Unknown(99),
                retryFirstWaitMs = 10_000,
                retryLongestWaitMs = 4_000,
                newSessionAfterS = 200_000,
            ),
        )
        assertEquals(MotionSource.MOTION_SOURCE_NATIVE, capped.motionSource)
        assertEquals(4_000, capped.retryFirstWaitMs)
        assertEquals(4_000, capped.retryLongestWaitMs)
        assertEquals(86_400, capped.newSessionAfterS)

        // Even when retryFirstWaitMs is omitted (default 2000), lowering retryLongestWaitMs caps first wait
        val loweredLongest = base.merge(Settings(retryLongestWaitMs = 500))
        assertEquals(500, loweredLongest.retryFirstWaitMs)
        assertEquals(500, loweredLongest.retryLongestWaitMs)
    }
}
