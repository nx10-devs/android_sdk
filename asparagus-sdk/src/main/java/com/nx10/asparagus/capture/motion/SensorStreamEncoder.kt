package com.nx10.asparagus.capture.motion

import com.nx10.asparagus.capture.CaptureMath
import com.nx10.asparagus.contract.CaptureSource
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.FallbackReason
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapRecord
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.Hold
import com.nx10.asparagus.contract.HoldStats
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.SensorBlock
import com.nx10.asparagus.contract.ValueUnit
import kotlin.math.abs

data class SensorWindowResult(
    val blocks: List<SensorBlock>,
    val gaps: List<GapRecord>,
)

internal class AxisStatsAccumulator {
    var sum: Long = 0L
        private set
    var sumOfSquares: Double = 0.0
        private set
    var minimum: Int = Int.MAX_VALUE
        private set
    var maximum: Int = Int.MIN_VALUE
        private set

    fun reset(initialValue: Int) {
        sum = initialValue.toLong()
        val d = initialValue.toDouble()
        sumOfSquares = d * d
        minimum = initialValue
        maximum = initialValue
    }

    fun add(value: Int) {
        sum += value.toLong()
        val d = value.toDouble()
        sumOfSquares += d * d
        if (value < minimum) minimum = value
        if (value > maximum) maximum = value
    }

    fun toHoldStats(): HoldStats = HoldStats(
        sum = sum,
        sumOfSquares = sumOfSquares,
        minimum = minimum,
        maximum = maximum,
    )
}

/**
 * Encodes one motion sensor stream into collector-valid [SensorBlock]s and `GAP_CAUSE_SENSOR_SILENT` [GapRecord]s.
 *
 * Guarantees:
 * - `scale > 0.0`
 * - `x.size == y.size == z.size`
 * - `timeStepsUs.size == max(0, x.size - 1)` and `firstTimeUs == 0L` when `x` is empty
 * - All `timeStepsUs >= 0` and `<= Int.MAX_VALUE` (splits into a new block if exceeded)
 * - Delta values are always relative to the previous sample sent in full (even across a [Hold])
 * - A [Hold] never spans across a silence gap
 * - [Hold.stats] is populated iff `holdTolerance > 0`
 */
class SensorStreamEncoder(
    val sensor: Sensor,
    source: CaptureSource,
    fallbackReason: FallbackReason = FallbackReason.FALLBACK_REASON_UNSPECIFIED,
    clock: Clock = Clock.CLOCK_SENSOR,
    unit: ValueUnit,
    scale: Double = CaptureMath.DEFAULT_MOTION_SCALE,
    val requestedHz: Float,
    val holdTolerance: Int,
    val holdRunLength: Int,
    val gapThresholdUs: Long?,
) {
    init {
        require(scale > 0.0 && scale.isFinite()) { "SensorBlock scale must be > 0: $scale" }
        require(holdTolerance >= 0) { "holdTolerance must be >= 0: $holdTolerance" }
        require(holdRunLength >= 0) { "holdRunLength must be >= 0: $holdRunLength" }
    }

    private val effectiveHoldRunLength: Int = if (holdRunLength <= 0) 0 else maxOf(2, holdRunLength)

    private var currentSource: CaptureSource = source
    private var currentFallbackReason: FallbackReason = fallbackReason
    private var currentClock: Clock = clock
    private var currentUnit: ValueUnit = unit
    private var currentScale: Double = scale

    private val gapSignal: GapSignal = when (sensor) {
        Sensor.SENSOR_ACCELEROMETER -> GapSignal.GAP_SIGNAL_ACCELEROMETER
        Sensor.SENSOR_GYROSCOPE -> GapSignal.GAP_SIGNAL_GYROSCOPE
        Sensor.SENSOR_GRAVITY -> GapSignal.GAP_SIGNAL_GRAVITY
        Sensor.SENSOR_MAGNETOMETER -> GapSignal.GAP_SIGNAL_MAGNETOMETER
        else -> GapSignal.GAP_SIGNAL_UNSPECIFIED
    }

    // Completed blocks & gaps within the current window
    private val completedBlocks = mutableListOf<SensorBlock>()
    private val windowGaps = mutableListOf<GapRecord>()

    // Active block accumulators
    private var blockSampleCount: Int = 0
    private var blockLongestGapUs: Int = 0
    private var firstFullTimeUs: Long = 0L
    private var hasLastFullSample: Boolean = false
    private var lastFullTimeUs: Long = 0L
    private var lastFullX: Int = 0
    private var lastFullY: Int = 0
    private var lastFullZ: Int = 0
    private val timeStepsUs = mutableListOf<Int>()
    private val xDeltas = mutableListOf<Int>()
    private val yDeltas = mutableListOf<Int>()
    private val zDeltas = mutableListOf<Int>()
    private val blockHolds = mutableListOf<Hold>()

    // Monotonic timestamp across samples (preserved across windows for cross-window silence detection)
    private var hasLastDeliveredTime: Boolean = false
    private var lastDeliveredTimeUs: Long = 0L

    // Hold / candidate run state
    private data class QuantizedSample(val timeUs: Long, val x: Int, val y: Int, val z: Int)

    private val pendingCandidates = mutableListOf<QuantizedSample>()
    private var activeHold: Boolean = false
    private var holdStartTimeUs: Long = 0L
    private var holdEndTimeUs: Long = 0L
    private var holdSampleCount: Int = 0
    private var anchorX: Int = 0
    private var anchorY: Int = 0
    private var anchorZ: Int = 0
    private val statsX = AxisStatsAccumulator()
    private val statsY = AxisStatsAccumulator()
    private val statsZ = AxisStatsAccumulator()

    var totalDeliveredSamples: Int = 0
        private set

    val gapCount: Int
        get() = windowGaps.size

    /**
     * Switches stream metadata mid-window (starting a new [SensorBlock] per `batch.proto` lines 189-191).
     */
    fun updateStreamConfig(
        source: CaptureSource = currentSource,
        fallbackReason: FallbackReason = currentFallbackReason,
        clock: Clock = currentClock,
        unit: ValueUnit = currentUnit,
        scale: Double = currentScale,
    ) {
        require(scale > 0.0 && scale.isFinite()) { "SensorBlock scale must be > 0: $scale" }
        val changed = source != currentSource ||
            fallbackReason != currentFallbackReason ||
            clock != currentClock ||
            unit != currentUnit ||
            scale != currentScale
        if (changed) {
            finalizeCurrentBlockIfNonEmpty()
            currentSource = source
            currentFallbackReason = fallbackReason
            currentClock = clock
            currentUnit = unit
            currentScale = scale
        }
    }

    /**
     * Appends one raw sample on the stream's clock (microseconds).
     */
    fun addSample(rawTimeUs: Long, rawX: Float, rawY: Float, rawZ: Float) {
        val qx = CaptureMath.quantize(rawX.toDouble(), currentScale)
        val qy = CaptureMath.quantize(rawY.toDouble(), currentScale)
        val qz = CaptureMath.quantize(rawZ.toDouble(), currentScale)

        val timeUs = if (hasLastDeliveredTime) maxOf(lastDeliveredTimeUs, rawTimeUs) else rawTimeUs

        if (hasLastDeliveredTime) {
            val deltaUs = timeUs - lastDeliveredTimeUs
            val isGap = gapThresholdUs != null && deltaUs > gapThresholdUs

            if (isGap) {
                // A hold never runs across a silence gap.
                flushPendingOrActiveHold()
                windowGaps.add(
                    GapRecord(
                        signal = gapSignal,
                        clock = currentClock,
                        startTimeUs = lastDeliveredTimeUs,
                        endTimeUs = timeUs,
                        cause = GapCause.GAP_CAUSE_SENSOR_SILENT,
                    ),
                )
            }

            if (blockSampleCount > 0) {
                val exceedsIntStep = deltaUs > Int.MAX_VALUE.toLong() ||
                    (hasLastFullSample && (timeUs - lastFullTimeUs) > Int.MAX_VALUE.toLong())
                if (exceedsIntStep) {
                    finalizeCurrentBlockIfNonEmpty()
                } else {
                    val deltaInt = deltaUs.toInt()
                    if (deltaInt > blockLongestGapUs) {
                        blockLongestGapUs = deltaInt
                    }
                }
            }
        }

        lastDeliveredTimeUs = timeUs
        hasLastDeliveredTime = true
        blockSampleCount++
        totalDeliveredSamples++

        if (effectiveHoldRunLength <= 0) {
            emitFullSample(timeUs, qx, qy, qz)
            return
        }

        if (activeHold) {
            if (withinTolerance(qx, qy, qz)) {
                holdEndTimeUs = timeUs
                holdSampleCount++
                statsX.add(qx)
                statsY.add(qy)
                statsZ.add(qz)
            } else {
                // Close active hold and emit the breaking sample in full.
                commitActiveHold()
                emitFullSample(timeUs, qx, qy, qz)
            }
            return
        }

        if (pendingCandidates.isEmpty()) {
            startCandidateRun(timeUs, qx, qy, qz)
        } else if (withinTolerance(qx, qy, qz)) {
            pendingCandidates.add(QuantizedSample(timeUs, qx, qy, qz))
            statsX.add(qx)
            statsY.add(qy)
            statsZ.add(qz)
            if (pendingCandidates.size >= effectiveHoldRunLength) {
                promoteCandidatesToActiveHold()
            }
        } else {
            flushCandidatesAsFull()
            startCandidateRun(timeUs, qx, qy, qz)
        }
    }

    /**
     * Closes the current window, finalizing any open hold and flushing shorter candidate runs in full.
     */
    fun closeWindow(): SensorWindowResult {
        finalizeCurrentBlockIfNonEmpty()
        return SensorWindowResult(
            blocks = completedBlocks.toList(),
            gaps = windowGaps.toList(),
        )
    }

    /**
     * Resets window-local accumulators for the next batch window while retaining `lastDeliveredTimeUs`
     * for monotonic clamping and cross-window silence detection.
     */
    fun resetForNextWindow() {
        completedBlocks.clear()
        windowGaps.clear()
        resetActiveBlockState()
        totalDeliveredSamples = 0
    }

    private fun startCandidateRun(timeUs: Long, qx: Int, qy: Int, qz: Int) {
        anchorX = qx
        anchorY = qy
        anchorZ = qz
        statsX.reset(qx)
        statsY.reset(qy)
        statsZ.reset(qz)
        pendingCandidates.add(QuantizedSample(timeUs, qx, qy, qz))
        if (pendingCandidates.size >= effectiveHoldRunLength) {
            promoteCandidatesToActiveHold()
        }
    }

    private fun promoteCandidatesToActiveHold() {
        val first = pendingCandidates.first()
        val last = pendingCandidates.last()
        activeHold = true
        holdStartTimeUs = first.timeUs
        holdEndTimeUs = last.timeUs
        holdSampleCount = pendingCandidates.size
        pendingCandidates.clear()
    }

    private fun withinTolerance(qx: Int, qy: Int, qz: Int): Boolean {
        val tol = holdTolerance.toLong()
        return abs(qx.toLong() - anchorX.toLong()) <= tol &&
            abs(qy.toLong() - anchorY.toLong()) <= tol &&
            abs(qz.toLong() - anchorZ.toLong()) <= tol
    }

    private fun flushPendingOrActiveHold() {
        if (activeHold) {
            commitActiveHold()
        } else if (pendingCandidates.isNotEmpty()) {
            flushCandidatesAsFull()
        }
    }

    private fun commitActiveHold() {
        val stats = if (holdTolerance > 0) {
            listOf(statsX.toHoldStats(), statsY.toHoldStats(), statsZ.toHoldStats())
        } else {
            emptyList()
        }
        blockHolds.add(
            Hold(
                startTimeUs = holdStartTimeUs,
                endTimeUs = holdEndTimeUs,
                sampleCount = holdSampleCount,
                x = anchorX,
                y = anchorY,
                z = anchorZ,
                stats = stats,
            ),
        )
        activeHold = false
        holdStartTimeUs = 0L
        holdEndTimeUs = 0L
        holdSampleCount = 0
    }

    private fun flushCandidatesAsFull() {
        for (sample in pendingCandidates) {
            emitFullSample(sample.timeUs, sample.x, sample.y, sample.z)
        }
        pendingCandidates.clear()
    }

    private fun emitFullSample(timeUs: Long, qx: Int, qy: Int, qz: Int) {
        if (!hasLastFullSample) {
            firstFullTimeUs = timeUs
            xDeltas.add(qx)
            yDeltas.add(qy)
            zDeltas.add(qz)
            hasLastFullSample = true
        } else {
            val stepUs = (timeUs - lastFullTimeUs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            timeStepsUs.add(stepUs)
            xDeltas.add(qx - lastFullX)
            yDeltas.add(qy - lastFullY)
            zDeltas.add(qz - lastFullZ)
        }
        lastFullTimeUs = timeUs
        lastFullX = qx
        lastFullY = qy
        lastFullZ = qz
    }

    private fun finalizeCurrentBlockIfNonEmpty() {
        flushPendingOrActiveHold()
        if (blockSampleCount == 0) return

        completedBlocks.add(
            SensorBlock(
                sensor = sensor,
                source = currentSource,
                fallbackReason = currentFallbackReason,
                clock = currentClock,
                unit = currentUnit,
                scale = currentScale,
                requestedHz = requestedHz,
                sampleCount = blockSampleCount,
                longestGapUs = blockLongestGapUs,
                firstTimeUs = if (xDeltas.isEmpty()) 0L else firstFullTimeUs,
                timeStepsUs = timeStepsUs.toList(),
                x = xDeltas.toList(),
                y = yDeltas.toList(),
                z = zDeltas.toList(),
                holds = blockHolds.toList(),
            ),
        )
        resetActiveBlockState()
    }

    private fun resetActiveBlockState() {
        blockSampleCount = 0
        blockLongestGapUs = 0
        firstFullTimeUs = 0L
        hasLastFullSample = false
        lastFullTimeUs = 0L
        lastFullX = 0
        lastFullY = 0
        lastFullZ = 0
        timeStepsUs.clear()
        xDeltas.clear()
        yDeltas.clear()
        zDeltas.clear()
        blockHolds.clear()
        pendingCandidates.clear()
        activeHold = false
        holdStartTimeUs = 0L
        holdEndTimeUs = 0L
        holdSampleCount = 0
    }
}
