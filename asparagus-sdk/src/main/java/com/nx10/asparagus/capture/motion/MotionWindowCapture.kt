package com.nx10.asparagus.capture.motion

import com.nx10.asparagus.capture.CaptureMath
import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.CaptureSource
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.FallbackReason
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapRecord
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.SensorBlock
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.ValueUnit

data class MotionFlushResult(
    val sensorBlocks: List<SensorBlock>,
    val gaps: List<GapRecord>,
    val diagnostics: List<Diagnostic>,
)

/**
 * Coordinates the 4 motion sensor encoders (Accelerometer, optional Gyroscope, optional Gravity,
 * optional Magnetometer), drains [SensorRingBuffer], converts buffer overflows into
 * `GAP_CAUSE_BUFFER_OVERFLOWED`, and runs `native_start_limit_ms` / `native_stall_limit_ms` watchdogs.
 */
class MotionWindowCapture(
    val settings: SdkSettings,
    val hasGyroscope: Boolean,
    val hasGravity: Boolean = true,
    val hasMagnetometer: Boolean = false,
    source: CaptureSource = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
    fallbackReason: FallbackReason = FallbackReason.FALLBACK_REASON_UNSPECIFIED,
    clock: Clock = Clock.CLOCK_SENSOR,
    scale: Double = CaptureMath.DEFAULT_MOTION_SCALE,
    magnetometerScale: Double = CaptureMath.DEFAULT_MAGNETOMETER_SCALE,
    val ringBuffer: SensorRingBuffer = SensorRingBuffer(
        capacity = SensorRingBuffer.computeCapacity(
            accelerometerHz = settings.accelerometerHz,
            gyroscopeHz = if (hasGyroscope) settings.gyroscopeHz else 0,
            gravityHz = if (hasGravity) settings.gravityHz else 0,
            nativeBufferS = settings.nativeBufferS,
            magnetometerHz = if (hasMagnetometer) settings.magnetometerHz else 0,
        ),
        nativeBufferS = settings.nativeBufferS,
    ),
) {
    private val lock = Any()

    private class TrackedStream(
        val encoder: SensorStreamEncoder,
        var hasEverReceivedSample: Boolean = false,
        var lastReceivedUnityUs: Long = 0L,
        var lastSampleClockUs: Long = 0L,
        var pendingUnityAnchor: Boolean = false,
        var startLimitReported: Boolean = false,
        var stallReportedSinceLastSample: Boolean = false,
    )

    private val streamsByProtoNumber: Map<Int, TrackedStream>
    private val overflowGaps = mutableListOf<GapRecord>()
    private val pendingDiagnostics = mutableListOf<Diagnostic>()

    private var captureStarted: Boolean = false
    private var startedUnityUs: Long = 0L

    @Volatile
    private var _isNativeSensorRunning: Boolean = false

    val isNativeSensorRunning: Boolean
        get() = _isNativeSensorRunning

    val enabledSensors: Set<Sensor>
        get() = streamsByProtoNumber.values.map { it.encoder.sensor }.toSet()

    init {
        val map = linkedMapOf<Int, TrackedStream>()

        if (settings.accelerometerHz > 0) {
            val hz = settings.accelerometerHz.toFloat()
            map[Sensor.SENSOR_ACCELEROMETER.protoNumber] = TrackedStream(
                SensorStreamEncoder(
                    sensor = Sensor.SENSOR_ACCELEROMETER,
                    source = source,
                    fallbackReason = fallbackReason,
                    clock = clock,
                    unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
                    scale = scale,
                    requestedHz = hz,
                    holdTolerance = settings.accelerometerHoldTolerance,
                    holdRunLength = settings.holdRunLength,
                    gapThresholdUs = CaptureMath.computeSampleGapThresholdUs(
                        settings.gapThresholdMs,
                        settings.gapThresholdPeriods,
                        hz,
                    ),
                ),
            )
        }

        // Leave out gyroscope when device lacks it or settings switch it off (`batch.proto` / `BUILDING_AN_SDK.md` line 194).
        if (hasGyroscope && settings.gyroscopeHz > 0) {
            val hz = settings.gyroscopeHz.toFloat()
            map[Sensor.SENSOR_GYROSCOPE.protoNumber] = TrackedStream(
                SensorStreamEncoder(
                    sensor = Sensor.SENSOR_GYROSCOPE,
                    source = source,
                    fallbackReason = fallbackReason,
                    clock = clock,
                    unit = ValueUnit.VALUE_UNIT_RADIANS_PER_SECOND,
                    scale = scale,
                    requestedHz = hz,
                    holdTolerance = settings.gyroscopeHoldTolerance,
                    holdRunLength = settings.holdRunLength,
                    gapThresholdUs = CaptureMath.computeSampleGapThresholdUs(
                        settings.gapThresholdMs,
                        settings.gapThresholdPeriods,
                        hz,
                    ),
                ),
            )
        }

        if (hasGravity && settings.gravityHz > 0) {
            val hz = settings.gravityHz.toFloat()
            map[Sensor.SENSOR_GRAVITY.protoNumber] = TrackedStream(
                SensorStreamEncoder(
                    sensor = Sensor.SENSOR_GRAVITY,
                    source = source,
                    fallbackReason = fallbackReason,
                    clock = clock,
                    unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
                    scale = scale,
                    requestedHz = hz,
                    holdTolerance = settings.gravityHoldTolerance,
                    holdRunLength = settings.holdRunLength,
                    gapThresholdUs = CaptureMath.computeSampleGapThresholdUs(
                        settings.gapThresholdMs,
                        settings.gapThresholdPeriods,
                        hz,
                    ),
                ),
            )
        }

        if (hasMagnetometer && settings.magnetometerHz > 0) {
            val hz = settings.magnetometerHz.toFloat()
            map[Sensor.SENSOR_MAGNETOMETER.protoNumber] = TrackedStream(
                SensorStreamEncoder(
                    sensor = Sensor.SENSOR_MAGNETOMETER,
                    source = source,
                    fallbackReason = fallbackReason,
                    clock = clock,
                    unit = ValueUnit.VALUE_UNIT_MICROTESLA,
                    scale = magnetometerScale,
                    requestedHz = hz,
                    holdTolerance = settings.magnetometerHoldTolerance,
                    holdRunLength = settings.holdRunLength,
                    gapThresholdUs = CaptureMath.computeSampleGapThresholdUs(
                        settings.gapThresholdMs,
                        settings.gapThresholdPeriods,
                        hz,
                    ),
                ),
            )
        }

        streamsByProtoNumber = map
    }

    fun markStarted(nowUnityUs: Long, nativeRunning: Boolean) = synchronized(lock) {
        captureStarted = true
        startedUnityUs = nowUnityUs
        _isNativeSensorRunning = nativeRunning && streamsByProtoNumber.isNotEmpty()
        for (stream in streamsByProtoNumber.values) {
            stream.lastReceivedUnityUs = nowUnityUs
            stream.lastSampleClockUs = 0L
            stream.pendingUnityAnchor = false
            stream.startLimitReported = false
            stream.stallReportedSinceLastSample = false
        }
    }

    fun markStopped() = synchronized(lock) {
        captureStarted = false
        _isNativeSensorRunning = false
    }

    fun setNativeSensorRunning(running: Boolean) {
        _isNativeSensorRunning = running && streamsByProtoNumber.isNotEmpty()
    }

    fun recordDiagnostic(diagnostic: Diagnostic) = synchronized(lock) {
        pendingDiagnostics.add(diagnostic)
    }

    /**
     * Drains queued samples from [ringBuffer] into per-sensor encoders.
     */
    fun drainRingBuffer(nowUnityUs: Long? = null) = synchronized(lock) {
        val overflow = ringBuffer.drainTo { sensorNum, timeUs, x, y, z ->
            val stream = streamsByProtoNumber[sensorNum] ?: return@drainTo
            stream.encoder.addSample(timeUs, x, y, z)
            if (nowUnityUs != null) {
                stream.lastReceivedUnityUs = nowUnityUs
                stream.pendingUnityAnchor = false
            } else if (stream.hasEverReceivedSample && !stream.pendingUnityAnchor) {
                stream.lastReceivedUnityUs += (timeUs - stream.lastSampleClockUs).coerceAtLeast(0L)
            } else {
                stream.pendingUnityAnchor = true
            }
            stream.lastSampleClockUs = timeUs
            stream.hasEverReceivedSample = true
            stream.stallReportedSinceLastSample = false
        }

        if (overflow != null) {
            overflowGaps.add(
                GapRecord(
                    signal = GapSignal.GAP_SIGNAL_EVERYTHING,
                    clock = Clock.CLOCK_SENSOR,
                    startTimeUs = overflow.startTimeUs,
                    endTimeUs = overflow.endTimeUs,
                    cause = GapCause.GAP_CAUSE_BUFFER_OVERFLOWED,
                ),
            )
        }
    }

    /**
     * Drains pending samples and checks `native_start_limit_ms` and `native_stall_limit_ms` watchdogs.
     */
    fun evaluateHealth(nowUnityUs: Long, isForeground: Boolean) = synchronized(lock) {
        drainRingBuffer(nowUnityUs)
        if (!captureStarted) return@synchronized

        val startLimitUs = settings.nativeStartLimitMs.toLong() * 1_000L
        val stallLimitUs = settings.nativeStallLimitMs.toLong() * 1_000L

        for (stream in streamsByProtoNumber.values) {
            if (stream.pendingUnityAnchor) {
                stream.lastReceivedUnityUs = nowUnityUs
                stream.pendingUnityAnchor = false
            }
            if (!stream.hasEverReceivedSample) {
                if (startLimitUs > 0L && !stream.startLimitReported && (nowUnityUs - startedUnityUs) >= startLimitUs) {
                    stream.startLimitReported = true
                    pendingDiagnostics.add(
                        Diagnostic(
                            timeUs = nowUnityUs,
                            severity = Severity.SEVERITY_WARNING,
                            code = DIAGNOSTIC_NO_FIRST_SAMPLE,
                            detail = "${stream.encoder.sensor.protoName}: no sample after ${settings.nativeStartLimitMs} ms",
                            count = 1,
                        ),
                    )
                }
            } else if (isForeground) {
                if (stallLimitUs > 0L && !stream.stallReportedSinceLastSample && (nowUnityUs - stream.lastReceivedUnityUs) >= stallLimitUs) {
                    stream.stallReportedSinceLastSample = true
                    pendingDiagnostics.add(
                        Diagnostic(
                            timeUs = nowUnityUs,
                            severity = Severity.SEVERITY_WARNING,
                            code = DIAGNOSTIC_SENSOR_STALLED,
                            detail = "${stream.encoder.sensor.protoName}: no sample for ${settings.nativeStallLimitMs} ms",
                            count = 1,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Live W8-26 counter of motion samples (sent in full + held) delivered in the current window.
     */
    fun currentMotionSampleCount(): Int = synchronized(lock) {
        drainRingBuffer()
        streamsByProtoNumber.values.sumOf { it.encoder.totalDeliveredSamples }
    }

    /**
     * Live W8-26 counter of gap records accumulated in the current window.
     */
    fun currentGapCount(): Int = synchronized(lock) {
        drainRingBuffer()
        overflowGaps.size + streamsByProtoNumber.values.sumOf { it.encoder.gapCount }
    }

    /**
     * Drains remaining samples, closes all active sensor blocks for this window, and resets for the next window.
     */
    fun flushWindow(nowUnityUs: Long? = null): MotionFlushResult = synchronized(lock) {
        drainRingBuffer(nowUnityUs)

        val blocks = mutableListOf<SensorBlock>()
        val gaps = mutableListOf<GapRecord>()
        gaps.addAll(overflowGaps)
        overflowGaps.clear()

        for (stream in streamsByProtoNumber.values) {
            val result = stream.encoder.closeWindow()
            blocks.addAll(result.blocks)
            gaps.addAll(result.gaps)
            stream.encoder.resetForNextWindow()
        }

        val diagnostics = pendingDiagnostics.toList()
        pendingDiagnostics.clear()

        MotionFlushResult(
            sensorBlocks = blocks,
            gaps = gaps,
            diagnostics = diagnostics,
        )
    }

    companion object {
        const val DIAGNOSTIC_NO_FIRST_SAMPLE = "sensor_no_first_sample"
        const val DIAGNOSTIC_SENSOR_STALLED = "sensor_stalled"
        const val DIAGNOSTIC_SENSOR_REFUSED = "sensor_refused"
    }
}
