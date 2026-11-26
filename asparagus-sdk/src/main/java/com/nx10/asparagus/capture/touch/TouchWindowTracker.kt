package com.nx10.asparagus.capture.touch

import com.nx10.asparagus.capture.CaptureMath
import com.nx10.asparagus.capture.motion.AxisStatsAccumulator
import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.ContactBefore
import com.nx10.asparagus.contract.Touch
import com.nx10.asparagus.contract.TouchContact
import com.nx10.asparagus.contract.TouchEnd
import com.nx10.asparagus.contract.TouchHold
import com.nx10.asparagus.contract.TouchMode
import kotlin.math.abs
import kotlin.math.round

enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}

/**
 * Pure-Kotlin representation of one touch event sample stamped on `CLOCK_UNITY` (`unityUs`).
 */
data class RawTouchSample(
    val fingerId: Int,
    val phase: TouchPhase,
    val timeUs: Long,
    val xPx: Float,
    val yPx: Float,
    val sizeMajor: Float = 0f,
    val sizeMinor: Float = 0f,
    val pressure: Float? = null,
)

/**
 * Pure-Kotlin event-driven multi-touch tracker enforcing thinning, delta encoding, [TouchHold],
 * cross-window [ContactBefore] continuation, keyboard/sensitive-screen pause, and live W8-26 counters.
 */
class TouchWindowTracker(
    val settings: SdkSettings,
    val mode: TouchMode = TouchMode.TOUCH_MODE_EVENTS,
    val positionScale: Double = CaptureMath.DEFAULT_TOUCH_POSITION_SCALE,
    val sizeScale: Double = CaptureMath.DEFAULT_TOUCH_SIZE_SCALE,
    val pressureScale: Double = CaptureMath.DEFAULT_TOUCH_PRESSURE_SCALE,
) {
    init {
        require(positionScale > 0.0 && positionScale.isFinite()) { "positionScale must be > 0" }
        require(sizeScale > 0.0 && sizeScale.isFinite()) { "sizeScale must be > 0" }
        require(pressureScale > 0.0 && pressureScale.isFinite()) { "pressureScale must be > 0" }
    }

    private val lock = Any()
    private val minMoveIntervalUs: Long = if (settings.touchHz > 0) {
        round(1_000_000.0 / settings.touchHz.toDouble()).toLong()
    } else {
        0L
    }

    private var keyboardVisible: Boolean = false
    private var sensitiveScreenPaused: Boolean = false

    private val activeFingers = linkedMapOf<Int, ActiveContact>()
    private val completedContacts = mutableListOf<TouchContact>()
    private var completedSampleCount: Int = 0

    val isTouchPaused: Boolean
        get() = synchronized(lock) { isPausedLocked() }

    fun setKeyboardVisible(visible: Boolean) = synchronized(lock) {
        val wasPaused = isPausedLocked()
        keyboardVisible = visible
        val nowPaused = isPausedLocked()
        if (!wasPaused && nowPaused) {
            cancelAllActiveFingersLocked()
        }
    }

    fun setSensitiveScreenPaused(paused: Boolean) = synchronized(lock) {
        val wasPaused = isPausedLocked()
        sensitiveScreenPaused = paused
        val nowPaused = isPausedLocked()
        if (!wasPaused && nowPaused) {
            cancelAllActiveFingersLocked()
        }
    }

    fun onTouchSample(sample: RawTouchSample) = synchronized(lock) {
        if (isPausedLocked()) return@synchronized

        val contact = when (sample.phase) {
            TouchPhase.DOWN -> {
                // If OS dropped a prior UP for this fingerId, close the previous contact as CANCELLED.
                activeFingers.remove(sample.fingerId)?.let { stale ->
                    if (stale.windowSampleCount > 0) {
                        val built = stale.finishWindow(TouchEnd.TOUCH_END_CANCELLED)
                        completedContacts.add(built)
                        completedSampleCount += stale.windowSampleCount
                    }
                }
                ActiveContact(
                    fingerId = sample.fingerId,
                    initialDownTimeUs = sample.timeUs,
                    continued = false,
                    before = null,
                    hasPressure = sample.pressure != null,
                    holdTolerance = settings.touchHoldTolerance,
                    holdRunLength = settings.holdRunLength,
                ).also { activeFingers[sample.fingerId] = it }
            }
            TouchPhase.MOVE, TouchPhase.UP, TouchPhase.CANCEL -> {
                activeFingers[sample.fingerId] ?: ActiveContact(
                    fingerId = sample.fingerId,
                    initialDownTimeUs = sample.timeUs,
                    continued = false,
                    before = null,
                    hasPressure = sample.pressure != null,
                    holdTolerance = settings.touchHoldTolerance,
                    holdRunLength = settings.holdRunLength,
                ).also { activeFingers[sample.fingerId] = it }
            }
        }

        contact.eventsSeen++

        val clampedTimeUs = contact.clampMonotonicTime(sample.timeUs)
        val shouldKeep = when (sample.phase) {
            TouchPhase.DOWN, TouchPhase.UP, TouchPhase.CANCEL -> true
            TouchPhase.MOVE -> {
                if (minMoveIntervalUs <= 0L) {
                    true
                } else {
                    val lastKept = contact.lastKeptTimeUs
                    lastKept == null || (clampedTimeUs - lastKept) >= minMoveIntervalUs
                }
            }
        }

        if (shouldKeep) {
            val qx = CaptureMath.quantize(sample.xPx.toDouble(), positionScale)
            val qy = CaptureMath.quantize(sample.yPx.toDouble(), positionScale)
            val qMaj = CaptureMath.quantize(sample.sizeMajor.toDouble(), sizeScale)
            val qMin = CaptureMath.quantize(sample.sizeMinor.toDouble(), sizeScale)
            val qPress = sample.pressure?.let { CaptureMath.quantize(it.toDouble(), pressureScale) }
            contact.addKeptSample(clampedTimeUs, qx, qy, qMaj, qMin, qPress)
        }

        when (sample.phase) {
            TouchPhase.UP -> {
                activeFingers.remove(sample.fingerId)
                val built = contact.finishWindow(TouchEnd.TOUCH_END_LIFTED)
                completedContacts.add(built)
                completedSampleCount += contact.windowSampleCount
            }
            TouchPhase.CANCEL -> {
                activeFingers.remove(sample.fingerId)
                val built = contact.finishWindow(TouchEnd.TOUCH_END_CANCELLED)
                completedContacts.add(built)
                completedSampleCount += contact.windowSampleCount
            }
            TouchPhase.DOWN, TouchPhase.MOVE -> Unit
        }
    }

    /**
     * Live W8-26 counter of contacts in the current window (completed + active with samples).
     */
    fun currentContactCount(): Int = synchronized(lock) {
        completedContacts.size + activeFingers.values.count { it.windowSampleCount > 0 }
    }

    /**
     * Live W8-26 counter of touch samples (full + held) in the current window.
     */
    fun currentTouchSampleCount(): Int = synchronized(lock) {
        completedSampleCount + activeFingers.values.sumOf { it.windowSampleCount }
    }

    /**
     * Closes the current window, returning `null` if no contacts occurred in the window,
     * and carries still-down fingers into the next window with updated [ContactBefore].
     */
    fun flushWindow(): Touch? = synchronized(lock) {
        val contacts = mutableListOf<TouchContact>()
        contacts.addAll(completedContacts)
        completedContacts.clear()
        completedSampleCount = 0

        for (contact in activeFingers.values) {
            if (contact.windowSampleCount > 0) {
                val built = contact.finishWindow(TouchEnd.TOUCH_END_STILL_DOWN)
                contacts.add(built)
                contact.advanceToNextWindow()
            }
        }

        if (contacts.isEmpty()) {
            return null
        }

        Touch(
            mode = mode,
            requestedHz = settings.touchHz.toFloat(),
            positionScale = positionScale,
            sizeScale = sizeScale,
            pressureScale = pressureScale,
            contacts = contacts,
        )
    }

    private fun isPausedLocked(): Boolean =
        sensitiveScreenPaused || (settings.pauseTouchOnKeyboard && keyboardVisible)

    private fun cancelAllActiveFingersLocked() {
        val snapshot = activeFingers.values.toList()
        activeFingers.clear()
        for (contact in snapshot) {
            if (contact.windowSampleCount > 0) {
                val built = contact.finishWindow(TouchEnd.TOUCH_END_CANCELLED)
                completedContacts.add(built)
                completedSampleCount += contact.windowSampleCount
            }
        }
    }

    private data class QuantizedTouchSample(
        val timeUs: Long,
        val x: Int,
        val y: Int,
        val sizeMajor: Int,
        val sizeMinor: Int,
        val pressure: Int,
    )

    private class ActiveContact(
        val fingerId: Int,
        val initialDownTimeUs: Long,
        var continued: Boolean,
        var before: ContactBefore?,
        private val hasPressure: Boolean,
        private val holdTolerance: Int,
        holdRunLength: Int,
    ) {
        private val effectiveHoldRunLength: Int = if (holdRunLength <= 0) 0 else maxOf(2, holdRunLength)

        var eventsSeen: Int = 0
        var lastKeptTimeUs: Long? = null
            private set
        var windowSampleCount: Int = 0
            private set

        private var lastTimeUs: Long? = null

        // Lifetime cumulative bounds for ContactBefore in subsequent windows
        private var hasCumulativeBounds: Boolean = false
        private var cumulativeMinX: Int = Int.MAX_VALUE
        private var cumulativeMaxX: Int = Int.MIN_VALUE
        private var cumulativeMinY: Int = Int.MAX_VALUE
        private var cumulativeMaxY: Int = Int.MIN_VALUE

        // Window-local full-sample accumulators
        private var firstFullTimeUs: Long = 0L
        private var hasLastFullSample: Boolean = false
        private var lastFullTimeUs: Long = 0L
        private var lastFullX: Int = 0
        private var lastFullY: Int = 0
        private var lastFullMajor: Int = 0
        private var lastFullMinor: Int = 0
        private var lastFullPressure: Int = 0

        private val timeStepsUs = mutableListOf<Int>()
        private val xDeltas = mutableListOf<Int>()
        private val yDeltas = mutableListOf<Int>()
        private val majorDeltas = mutableListOf<Int>()
        private val minorDeltas = mutableListOf<Int>()
        private val pressureDeltas = mutableListOf<Int>()
        private val holds = mutableListOf<TouchHold>()

        // Hold / candidate state
        private val pendingCandidates = mutableListOf<QuantizedTouchSample>()
        private var activeHold: Boolean = false
        private var holdStartTimeUs: Long = 0L
        private var holdEndTimeUs: Long = 0L
        private var holdSampleCount: Int = 0
        private var anchorX: Int = 0
        private var anchorY: Int = 0
        private var anchorMajor: Int = 0
        private var anchorMinor: Int = 0
        private var anchorPressure: Int = 0

        private val statsX = AxisStatsAccumulator()
        private val statsY = AxisStatsAccumulator()
        private val statsMajor = AxisStatsAccumulator()
        private val statsMinor = AxisStatsAccumulator()
        private val statsPressure = AxisStatsAccumulator()

        fun clampMonotonicTime(rawTimeUs: Long): Long {
            val prev = lastTimeUs
            val clamped = if (prev != null) maxOf(prev, rawTimeUs) else rawTimeUs
            lastTimeUs = clamped
            return clamped
        }

        fun addKeptSample(
            timeUs: Long,
            qx: Int,
            qy: Int,
            qMaj: Int,
            qMin: Int,
            qPress: Int?,
        ) {
            lastKeptTimeUs = timeUs
            windowSampleCount++

            if (!hasCumulativeBounds) {
                cumulativeMinX = qx
                cumulativeMaxX = qx
                cumulativeMinY = qy
                cumulativeMaxY = qy
                hasCumulativeBounds = true
            } else {
                if (qx < cumulativeMinX) cumulativeMinX = qx
                if (qx > cumulativeMaxX) cumulativeMaxX = qx
                if (qy < cumulativeMinY) cumulativeMinY = qy
                if (qy > cumulativeMaxY) cumulativeMaxY = qy
            }

            val effPress = if (hasPressure) (qPress ?: 0) else 0

            if (effectiveHoldRunLength <= 0) {
                emitFullSample(timeUs, qx, qy, qMaj, qMin, effPress)
                return
            }

            if (activeHold) {
                if (withinTolerance(qx, qy, qMaj, qMin, effPress)) {
                    holdEndTimeUs = timeUs
                    holdSampleCount++
                    statsX.add(qx)
                    statsY.add(qy)
                    statsMajor.add(qMaj)
                    statsMinor.add(qMin)
                    statsPressure.add(effPress)
                } else {
                    commitActiveHold()
                    emitFullSample(timeUs, qx, qy, qMaj, qMin, effPress)
                }
                return
            }

            if (pendingCandidates.isEmpty()) {
                startCandidateRun(timeUs, qx, qy, qMaj, qMin, effPress)
            } else if (withinTolerance(qx, qy, qMaj, qMin, effPress)) {
                pendingCandidates.add(QuantizedTouchSample(timeUs, qx, qy, qMaj, qMin, effPress))
                statsX.add(qx)
                statsY.add(qy)
                statsMajor.add(qMaj)
                statsMinor.add(qMin)
                statsPressure.add(effPress)
                if (pendingCandidates.size >= effectiveHoldRunLength) {
                    promoteCandidatesToActiveHold()
                }
            } else {
                flushCandidatesAsFull()
                startCandidateRun(timeUs, qx, qy, qMaj, qMin, effPress)
            }
        }

        fun finishWindow(end: TouchEnd): TouchContact {
            if (activeHold) {
                commitActiveHold()
            } else if (pendingCandidates.isNotEmpty()) {
                flushCandidatesAsFull()
            }

            return TouchContact(
                fingerId = fingerId,
                continued = continued,
                end = end,
                firstTimeUs = if (xDeltas.isEmpty()) 0L else firstFullTimeUs,
                timeStepsUs = timeStepsUs.toList(),
                x = xDeltas.toList(),
                y = yDeltas.toList(),
                sizeMajor = majorDeltas.toList(),
                sizeMinor = minorDeltas.toList(),
                pressure = if (hasPressure) pressureDeltas.toList() else emptyList(),
                holds = holds.toList(),
                eventsSeen = eventsSeen,
                before = before,
            )
        }

        fun advanceToNextWindow() {
            continued = true
            if (hasCumulativeBounds) {
                before = ContactBefore(
                    downTimeUs = initialDownTimeUs,
                    minX = cumulativeMinX,
                    maxX = cumulativeMaxX,
                    minY = cumulativeMinY,
                    maxY = cumulativeMaxY,
                )
            }
            eventsSeen = 0
            lastKeptTimeUs = null
            windowSampleCount = 0
            firstFullTimeUs = 0L
            hasLastFullSample = false
            lastFullTimeUs = 0L
            lastFullX = 0
            lastFullY = 0
            lastFullMajor = 0
            lastFullMinor = 0
            lastFullPressure = 0
            timeStepsUs.clear()
            xDeltas.clear()
            yDeltas.clear()
            majorDeltas.clear()
            minorDeltas.clear()
            pressureDeltas.clear()
            holds.clear()
            pendingCandidates.clear()
            activeHold = false
            holdStartTimeUs = 0L
            holdEndTimeUs = 0L
            holdSampleCount = 0
        }

        private fun startCandidateRun(
            timeUs: Long,
            qx: Int,
            qy: Int,
            qMaj: Int,
            qMin: Int,
            qPress: Int,
        ) {
            anchorX = qx
            anchorY = qy
            anchorMajor = qMaj
            anchorMinor = qMin
            anchorPressure = qPress
            statsX.reset(qx)
            statsY.reset(qy)
            statsMajor.reset(qMaj)
            statsMinor.reset(qMin)
            statsPressure.reset(qPress)
            pendingCandidates.add(QuantizedTouchSample(timeUs, qx, qy, qMaj, qMin, qPress))
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

        private fun withinTolerance(
            qx: Int,
            qy: Int,
            qMaj: Int,
            qMin: Int,
            qPress: Int,
        ): Boolean {
            val tol = holdTolerance.toLong()
            return abs(qx.toLong() - anchorX.toLong()) <= tol &&
                abs(qy.toLong() - anchorY.toLong()) <= tol &&
                abs(qMaj.toLong() - anchorMajor.toLong()) <= tol &&
                abs(qMin.toLong() - anchorMinor.toLong()) <= tol &&
                (!hasPressure || abs(qPress.toLong() - anchorPressure.toLong()) <= tol)
        }

        private fun commitActiveHold() {
            val stats = if (holdTolerance > 0) {
                if (hasPressure) {
                    listOf(
                        statsX.toHoldStats(),
                        statsY.toHoldStats(),
                        statsMajor.toHoldStats(),
                        statsMinor.toHoldStats(),
                        statsPressure.toHoldStats(),
                    )
                } else {
                    listOf(
                        statsX.toHoldStats(),
                        statsY.toHoldStats(),
                        statsMajor.toHoldStats(),
                        statsMinor.toHoldStats(),
                    )
                }
            } else {
                emptyList()
            }

            holds.add(
                TouchHold(
                    startTimeUs = holdStartTimeUs,
                    endTimeUs = holdEndTimeUs,
                    sampleCount = holdSampleCount,
                    x = anchorX,
                    y = anchorY,
                    sizeMajor = anchorMajor,
                    sizeMinor = anchorMinor,
                    pressure = if (hasPressure) anchorPressure else 0,
                    stats = stats,
                ),
            )
            activeHold = false
            holdStartTimeUs = 0L
            holdEndTimeUs = 0L
            holdSampleCount = 0
        }

        private fun flushCandidatesAsFull() {
            for (s in pendingCandidates) {
                emitFullSample(s.timeUs, s.x, s.y, s.sizeMajor, s.sizeMinor, s.pressure)
            }
            pendingCandidates.clear()
        }

        private fun emitFullSample(
            timeUs: Long,
            qx: Int,
            qy: Int,
            qMaj: Int,
            qMin: Int,
            qPress: Int,
        ) {
            if (!hasLastFullSample) {
                firstFullTimeUs = timeUs
                xDeltas.add(qx)
                yDeltas.add(qy)
                majorDeltas.add(qMaj)
                minorDeltas.add(qMin)
                if (hasPressure) {
                    pressureDeltas.add(qPress)
                }
                hasLastFullSample = true
            } else {
                val stepUs = (timeUs - lastFullTimeUs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                timeStepsUs.add(stepUs)
                xDeltas.add(qx - lastFullX)
                yDeltas.add(qy - lastFullY)
                majorDeltas.add(qMaj - lastFullMajor)
                minorDeltas.add(qMin - lastFullMinor)
                if (hasPressure) {
                    pressureDeltas.add(qPress - lastFullPressure)
                }
            }
            lastFullTimeUs = timeUs
            lastFullX = qx
            lastFullY = qy
            lastFullMajor = qMaj
            lastFullMinor = qMin
            lastFullPressure = qPress
        }
    }
}
