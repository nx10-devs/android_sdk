package com.nx10.asparagus.capture.motion

import com.nx10.asparagus.capture.CaptureMath
import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.CaptureSource
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.Settings
import com.nx10.asparagus.contract.ValueUnit
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorStreamEncoderAndRingBufferTest {

    @Test
    fun `delta encoding across holds and protobuf round-trip satisfy all collector invariants`() {
        val encoder = SensorStreamEncoder(
            sensor = Sensor.SENSOR_ACCELEROMETER,
            source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
            clock = Clock.CLOCK_SENSOR,
            unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
            scale = CaptureMath.DEFAULT_MOTION_SCALE,
            requestedHz = 100f,
            holdTolerance = 0,
            holdRunLength = 10,
            gapThresholdUs = 250_000L,
        )

        // 2 changing samples (1.0, 2.0) -> 10 identical samples (3.0) -> 1 breaking sample (4.5)
        encoder.addSample(1_000_000L, 1.0f, -1.0f, 9.8f)
        encoder.addSample(1_010_000L, 2.0f, -2.0f, 9.9f)
        for (i in 0 until 10) {
            encoder.addSample(1_020_000L + i * 10_000L, 3.0f, 0.0f, 9.8f)
        }
        encoder.addSample(1_120_000L, 4.5f, 1.5f, 10.0f)

        assertEquals(13, encoder.totalDeliveredSamples)
        val result = encoder.closeWindow()
        assertTrue(result.gaps.isEmpty())
        assertEquals(1, result.blocks.size)

        val block = result.blocks.single()
        assertEquals(13, block.sampleCount)
        assertEquals(10_000, block.longestGapUs)
        assertEquals(1_000_000L, block.firstTimeUs)

        // Full samples: sample 1 (1.0), sample 2 (2.0), and sample 13 (4.5)
        // Deltas across the hold are relative to sample 2 (2.0), NOT the hold anchor (3.0)!
        assertEquals(listOf(100_000, 100_000, 250_000), block.x)
        assertEquals(listOf(-100_000, -100_000, 350_000), block.y)
        assertEquals(listOf(980_000, 10_000, 10_000), block.z)
        assertEquals(listOf(10_000, 110_000), block.timeStepsUs)
        assertEquals(block.x.size - 1, block.timeStepsUs.size)

        // Hold covers samples 3..12 with empty stats because holdTolerance == 0
        assertEquals(1, block.holds.size)
        val hold = block.holds.single()
        assertEquals(1_020_000L, hold.startTimeUs)
        assertEquals(1_110_000L, hold.endTimeUs)
        assertEquals(10, hold.sampleCount)
        assertEquals(300_000, hold.x)
        assertEquals(0, hold.y)
        assertEquals(980_000, hold.z)
        assertTrue("HoldStats must be empty when tolerance == 0", hold.stats.isEmpty())

        // Verify binary Protobuf round-trip
        val batch = Batch(sensorBlocks = result.blocks)
        assertEquals(batch, BatchProtoCodec.decode(BatchProtoCodec.encode(batch)))
    }

    @Test
    fun `pure hold window with tolerance above zero populates exact HoldStats and leaves x empty`() {
        val encoder = SensorStreamEncoder(
            sensor = Sensor.SENSOR_GRAVITY,
            source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
            clock = Clock.CLOCK_SENSOR,
            unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
            scale = 1.0, // 1:1 scale for exact integer assertions
            requestedHz = 10f,
            holdTolerance = 5,
            holdRunLength = 4,
            gapThresholdUs = 500_000L,
        )

        // 4 samples within tolerance 5 of anchor (100, -50, 980)
        val samples = listOf(
            Triple(100f, -50f, 980f),
            Triple(102f, -48f, 983f),
            Triple(97f, -53f, 976f),
            Triple(101f, -51f, 981f),
        )
        samples.forEachIndexed { i, (x, y, z) ->
            encoder.addSample(2_000_000L + i * 100_000L, x, y, z)
        }

        val block = encoder.closeWindow().blocks.single()
        assertEquals(4, block.sampleCount)
        assertEquals(0L, block.firstTimeUs)
        assertTrue(block.x.isEmpty())
        assertTrue(block.y.isEmpty())
        assertTrue(block.z.isEmpty())
        assertTrue(block.timeStepsUs.isEmpty())

        val hold = block.holds.single()
        assertEquals(4, hold.sampleCount)
        assertEquals(100, hold.x)
        assertEquals(-50, hold.y)
        assertEquals(980, hold.z)
        assertEquals(3, hold.stats.size)

        // X: 100, 102, 97, 101 -> sum = 400, min = 97, max = 102, sumSq = 10000+10404+9409+10201 = 40014
        assertEquals(400L, hold.stats[0].sum)
        assertEquals(40014.0, hold.stats[0].sumOfSquares, 1e-6)
        assertEquals(97, hold.stats[0].minimum)
        assertEquals(102, hold.stats[0].maximum)

        // Y: -50, -48, -53, -51 -> sum = -202, min = -53, max = -48
        assertEquals(-202L, hold.stats[1].sum)
        assertEquals(-53, hold.stats[1].minimum)
        assertEquals(-48, hold.stats[1].maximum)

        // Z: 980, 983, 976, 981 -> sum = 3920, min = 976, max = 983
        assertEquals(3920L, hold.stats[2].sum)
        assertEquals(976, hold.stats[2].minimum)
        assertEquals(983, hold.stats[2].maximum)
    }

    @Test
    fun `silence gap closes active hold before gap and out-of-order timestamps clamp non-negatively`() {
        val encoder = SensorStreamEncoder(
            sensor = Sensor.SENSOR_GYROSCOPE,
            source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
            clock = Clock.CLOCK_SENSOR,
            unit = ValueUnit.VALUE_UNIT_RADIANS_PER_SECOND,
            scale = 1.0,
            requestedHz = 100f,
            holdTolerance = 0,
            holdRunLength = 3,
            gapThresholdUs = 250_000L,
        )

        // 4 identical samples forming a hold (t = 1_000_000..1_030_000)
        for (i in 0 until 4) {
            encoder.addSample(1_000_000L + i * 10_000L, 0f, 0f, 0f)
        }
        // Out-of-order timestamp before gap: clamped to 1_030_000L (still part of hold, no negative step)
        encoder.addSample(1_020_000L, 0f, 0f, 0f)

        // 500ms silence gap (> 250ms) with SAME value (0, 0, 0) -> must end first hold BEFORE gap!
        encoder.addSample(1_530_000L, 0f, 0f, 0f)
        encoder.addSample(1_540_000L, 1f, 0f, 0f)

        val result = encoder.closeWindow()
        assertEquals(1, result.gaps.size)
        val gap = result.gaps.single()
        assertEquals(GapSignal.GAP_SIGNAL_GYROSCOPE, gap.signal)
        assertEquals(Clock.CLOCK_SENSOR, gap.clock)
        assertEquals(1_030_000L, gap.startTimeUs)
        assertEquals(1_530_000L, gap.endTimeUs)
        assertEquals(GapCause.GAP_CAUSE_SENSOR_SILENT, gap.cause)

        val block = result.blocks.single()
        assertEquals(7, block.sampleCount)
        assertEquals(1, block.holds.size)
        assertEquals(1_000_000L, block.holds[0].startTimeUs)
        assertEquals(1_030_000L, block.holds[0].endTimeUs)
        assertEquals(5, block.holds[0].sampleCount)

        // Samples after gap were flushed in full (candidate length 1 < 3)
        assertEquals(1_530_000L, block.firstTimeUs)
        assertEquals(listOf(0, 1), block.x)
        assertEquals(listOf(10_000), block.timeStepsUs)
        assertTrue(block.timeStepsUs.all { it >= 0 })
    }

    @Test
    fun `ring buffer honors nativeBufferS zero on pause and reports overflow gap when full`() {
        // Pause drop when nativeBufferS == 0
        val noPauseBuffer = SensorRingBuffer(capacity = 8, nativeBufferS = 0)
        assertTrue(noPauseBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 100L, 1f, 2f, 3f))
        noPauseBuffer.setPaused(true)
        assertFalse(noPauseBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 200L, 1f, 2f, 3f))
        assertEquals(1, noPauseBuffer.size)

        // Overflow tracking on small ring buffer
        val smallRing = SensorRingBuffer(capacity = 3, nativeBufferS = 60)
        for (i in 1..5) {
            smallRing.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, i * 10_000L, i.toFloat(), 0f, 0f)
        }
        val drainedTimes = mutableListOf<Long>()
        val overflow = smallRing.drainTo { _, t, _, _, _ -> drainedTimes.add(t) }
        assertNotNull(overflow)
        assertEquals(10_000L, overflow!!.startTimeUs)
        assertEquals(20_000L, overflow.endTimeUs)
        assertEquals(2, overflow.droppedCount)
        assertEquals(listOf(30_000L, 40_000L, 50_000L), drainedTimes)
    }

    @Test
    fun `motionWindowCapture omits disabled gyroscope, surfaces overflow gap, and fires start and stall watchdogs`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(
                accelerometerHz = 100,
                gyroscopeHz = 100,
                gravityHz = 10,
                nativeStartLimitMs = 1_000,
                nativeStallLimitMs = 5_000,
            ),
        )
        val tinyRing = SensorRingBuffer(capacity = 3, nativeBufferS = 60)
        val capture = MotionWindowCapture(
            settings = settings,
            hasGyroscope = false, // phone without gyroscope
            hasGravity = true,
            ringBuffer = tinyRing,
        )

        assertFalse(Sensor.SENSOR_GYROSCOPE in capture.enabledSensors)
        assertTrue(Sensor.SENSOR_ACCELEROMETER in capture.enabledSensors)
        assertTrue(Sensor.SENSOR_GRAVITY in capture.enabledSensors)

        capture.markStarted(nowUnityUs = 1_000_000L, nativeRunning = true)
        assertTrue(capture.isNativeSensorRunning)

        // Push 3 accel samples + 1 gyro sample (4 total) into capacity-3 ring -> 1 oldest accel overflowed, 2 accel kept, gyro ignored
        tinyRing.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 5_000_000L, 1f, 0f, 9.8f)
        tinyRing.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 5_010_000L, 2f, 0f, 9.8f)
        tinyRing.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 5_020_000L, 3f, 0f, 9.8f)
        // Gyroscope sample pushed anyway is ignored because hasGyroscope == false
        tinyRing.push(Sensor.SENSOR_GYROSCOPE.protoNumber, 5_020_000L, 0.5f, 0.5f, 0.5f)

        // At +1.2s (> nativeStartLimitMs 1000ms): gravity fires sensor_no_first_sample
        capture.evaluateHealth(nowUnityUs = 2_200_000L, isForeground = true)
        // At +7.5s (+5.3s since last accel sample > nativeStallLimitMs 5000ms): accel fires sensor_stalled
        capture.evaluateHealth(nowUnityUs = 7_500_000L, isForeground = true)

        assertEquals(2, capture.currentMotionSampleCount())
        val flushed = capture.flushWindow(nowUnityUs = 7_500_000L)

        assertEquals(1, flushed.sensorBlocks.size)
        assertEquals(Sensor.SENSOR_ACCELEROMETER, flushed.sensorBlocks.single().sensor)
        assertTrue(flushed.gaps.any { it.cause == GapCause.GAP_CAUSE_BUFFER_OVERFLOWED })
        assertTrue(flushed.diagnostics.any { it.code == MotionWindowCapture.DIAGNOSTIC_NO_FIRST_SAMPLE && it.detail.contains("SENSOR_GRAVITY") })
        assertTrue(flushed.diagnostics.any { it.code == MotionWindowCapture.DIAGNOSTIC_SENSOR_STALLED && it.detail.contains("SENSOR_ACCELEROMETER") })
    }

    @Test
    fun `repeated W8-26 counter polling across nativeStallLimitMs does not trigger false sensor_stalled diagnostics`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(
                accelerometerHz = 100,
                gyroscopeHz = 0,
                gravityHz = 0,
                nativeStartLimitMs = 1_000,
                nativeStallLimitMs = 5_000,
            ),
        )
        val capture = MotionWindowCapture(
            settings = settings,
            hasGyroscope = false,
            hasGravity = false,
        )
        capture.markStarted(nowUnityUs = 1_000_000L, nativeRunning = true)

        // Stream steady samples over 7 seconds (> nativeStallLimitMs 5000ms)
        var sensorClockUs = 50_000_000L
        var unityUs = 1_000_000L
        // First batch anchored via evaluateHealth at t=2s
        for (step in 0 until 10) {
            sensorClockUs += 100_000L
            unityUs += 100_000L
            capture.ringBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, sensorClockUs, 0.1f * step, 0f, 9.8f)
        }
        capture.evaluateHealth(nowUnityUs = unityUs, isForeground = true)

        // Next 6 seconds (> 5s stall limit): ONLY W8-26 counter polling drains ringBuffer (nowUnityUs == null)
        for (sec in 0 until 6) {
            for (step in 0 until 10) {
                sensorClockUs += 100_000L
                unityUs += 100_000L
                capture.ringBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, sensorClockUs, 0.1f * step, 0f, 9.8f)
            }
            assertTrue(capture.currentMotionSampleCount() > 0)
            assertEquals(0, capture.currentGapCount())
        }

        // Health check at t=8s after 6s of counter-polled draining must NOT fire false sensor_stalled
        capture.evaluateHealth(nowUnityUs = unityUs, isForeground = true)

        val flushed = capture.flushWindow(nowUnityUs = unityUs)
        assertEquals(70, flushed.sensorBlocks.single().sampleCount)
        assertTrue(
            "Expected zero false sensor_stalled diagnostics, got: ${flushed.diagnostics}",
            flushed.diagnostics.none { it.code == MotionWindowCapture.DIAGNOSTIC_SENSOR_STALLED },
        )
    }

    @Test
    fun `holdRunLength of 1 requires at least 2 unchanged samples in SensorStreamEncoder`() {
        val encoder = SensorStreamEncoder(
            sensor = Sensor.SENSOR_ACCELEROMETER,
            source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
            clock = Clock.CLOCK_SENSOR,
            unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
            scale = 1.0,
            requestedHz = 100f,
            holdTolerance = 0,
            holdRunLength = 1,
            gapThresholdUs = 250_000L,
        )

        encoder.addSample(1_000_000L, 1f, 0f, 0f)
        encoder.addSample(1_010_000L, 2f, 0f, 0f)
        encoder.addSample(1_020_000L, 3f, 0f, 0f)

        val block = encoder.closeWindow().blocks.single()
        assertTrue("Isolated samples must not become 1-sample holds", block.holds.isEmpty())
        assertEquals(listOf(1, 1, 1), block.x)
    }

    @Test
    fun `magnetometer quantizes at 0_01 microtesla scale, emits GAP_SIGNAL_MAGNETOMETER on silence, and omits when disabled`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(
                accelerometerHz = 0,
                gyroscopeHz = 0,
                gravityHz = 0,
                magnetometerHz = 10,
                magnetometerHoldTolerance = 0,
                gapThresholdMs = 250,
                gapThresholdPeriods = 5, // at 10 Hz -> 500_000 us wins
            ),
        )

        val capture = MotionWindowCapture(
            settings = settings,
            hasGyroscope = false,
            hasGravity = false,
            hasMagnetometer = true,
        )
        assertEquals(setOf(Sensor.SENSOR_MAGNETOMETER), capture.enabledSensors)

        capture.ringBuffer.push(Sensor.SENSOR_MAGNETOMETER.protoNumber, 1_000_000L, 20.00f, -5.00f, -40.00f)
        capture.ringBuffer.push(Sensor.SENSOR_MAGNETOMETER.protoNumber, 1_100_000L, 20.03f, -5.01f, -39.98f)
        // 1.0s silence (> 500ms threshold) -> GAP_SIGNAL_MAGNETOMETER
        capture.ringBuffer.push(Sensor.SENSOR_MAGNETOMETER.protoNumber, 2_100_000L, 20.01f, -5.00f, -39.98f)

        val flushed = capture.flushWindow(nowUnityUs = 2_100_000L)
        assertEquals(1, flushed.sensorBlocks.size)
        val block = flushed.sensorBlocks.single()
        assertEquals(Sensor.SENSOR_MAGNETOMETER, block.sensor)
        assertEquals(ValueUnit.VALUE_UNIT_MICROTESLA, block.unit)
        assertEquals(CaptureMath.DEFAULT_MAGNETOMETER_SCALE, block.scale, 1e-9)
        assertEquals(10f, block.requestedHz, 0.001f)
        assertEquals(listOf(2000, 3, -2), block.x)
        assertEquals(listOf(-500, -1, 1), block.y)
        assertEquals(listOf(-4000, 2, 0), block.z)
        assertEquals(1, flushed.gaps.size)
        assertEquals(GapSignal.GAP_SIGNAL_MAGNETOMETER, flushed.gaps.single().signal)
        assertEquals(GapCause.GAP_CAUSE_SENSOR_SILENT, flushed.gaps.single().cause)

        // Omitted when hasMagnetometer == false
        val noMagDevice = MotionWindowCapture(settings = settings, hasGyroscope = false, hasGravity = false, hasMagnetometer = false)
        assertTrue(noMagDevice.enabledSensors.isEmpty())

        // Omitted when magnetometerHz == 0
        val disabledSettings = settings.merge(Settings(magnetometerHz = 0))
        val disabledMag = MotionWindowCapture(settings = disabledSettings, hasGyroscope = false, hasGravity = false, hasMagnetometer = true)
        assertTrue(disabledMag.enabledSensors.isEmpty())
    }
}
