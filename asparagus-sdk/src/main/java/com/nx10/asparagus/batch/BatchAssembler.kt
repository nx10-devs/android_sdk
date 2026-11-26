package com.nx10.asparagus.batch

import com.nx10.asparagus.capture.motion.MotionWindowCapture
import com.nx10.asparagus.capture.touch.ScreenChangeTracker
import com.nx10.asparagus.capture.touch.TouchWindowTracker
import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.ClockReading
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapRecord
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.contract.Platform
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.device.DEFAULT_SDK_VERSION
import com.nx10.asparagus.device.DeviceInfoSource
import com.nx10.asparagus.events.SessionEventStateTracker
import com.nx10.asparagus.internal.util.Utf8Bounds
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.lifecycle.LifecycleAndTelemetryTracker
import com.nx10.asparagus.session.ActiveSession
import com.nx10.asparagus.time.SessionClock

sealed interface WindowCheckResult {
    data object KeepOpen : WindowCheckResult
    data object CloseFullWindow : WindowCheckResult
    data class CloseEarlyForBounds(val reason: String) : WindowCheckResult
}

/**
 * Assembles validated, sequenced [PreparedBatch]es for one [ActiveSession] from all capture,
 * event/state, lifecycle, and diagnostics subsystems.
 */
class BatchAssembler(
    private val session: ActiveSession,
    private val deviceInfo: DeviceInfoSource,
    private val motionCapture: MotionWindowCapture,
    private val touchTracker: TouchWindowTracker,
    private val screenTracker: ScreenChangeTracker,
    private val eventStateTracker: SessionEventStateTracker,
    private val lifecycleTracker: LifecycleAndTelemetryTracker,
    private val diagnosticsAggregator: DiagnosticsAggregator,
) {
    private val lock = Any()

    private var nextSequence: Long = 1L
    private var openingWindowStartUs: Long = session.clock.firstWindowStartUs
    private var openingClocks: ClockReading = session.clock.captureClockReading(motionCapture.isNativeSensorRunning)
    private var openingUtcOffsetMinutes: Int = session.clock.currentUtcOffsetMinutes()

    fun currentOpeningWindowStartUs(): Long = synchronized(lock) { openingWindowStartUs }

    fun peekNextSequence(): Long = synchronized(lock) { nextSequence }

    /**
     * Checks whether the current window has reached its full target length or must close early
     * to stay inside W8-26 bounds.
     */
    fun shouldCloseWindowEarly(pendingDroppedBatchGap: Boolean = false): WindowCheckResult = synchronized(lock) {
        val elapsedUs = (session.clock.sessionUs() - openingWindowStartUs).coerceAtLeast(0L)
        val targetLengthS = session.settings.batchLengthS.coerceAtLeast(1)
        val cappedLengthS = minOf(targetLengthS, BatchBoundsAndHeaderValidator.MAX_WINDOW_LENGTH_S)
        if (elapsedUs >= cappedLengthS.toLong() * SessionClock.MICROS_PER_SECOND) {
            return if (targetLengthS > BatchBoundsAndHeaderValidator.MAX_WINDOW_LENGTH_S) {
                WindowCheckResult.CloseEarlyForBounds("window_length_150s")
            } else {
                WindowCheckResult.CloseFullWindow
            }
        }

        if (motionCapture.currentMotionSampleCount() >= BatchBoundsAndHeaderValidator.PROACTIVE_MOTION_SAMPLE_LIMIT) {
            return WindowCheckResult.CloseEarlyForBounds("motion_samples")
        }
        if (touchTracker.currentTouchSampleCount() >= BatchBoundsAndHeaderValidator.PROACTIVE_TOUCH_SAMPLE_LIMIT) {
            return WindowCheckResult.CloseEarlyForBounds("touch_samples")
        }
        if (eventStateTracker.currentEventCount() >= BatchBoundsAndHeaderValidator.MAX_GAME_EVENTS) {
            return WindowCheckResult.CloseEarlyForBounds("game_events")
        }
        val totalGaps = motionCapture.currentGapCount() +
            lifecycleTracker.currentGapCount() +
            (if (pendingDroppedBatchGap) 1 else 0)
        if (totalGaps >= BatchBoundsAndHeaderValidator.MAX_GAP_RECORDS) {
            return WindowCheckResult.CloseEarlyForBounds("gap_records")
        }
        if (touchTracker.currentContactCount() >= BatchBoundsAndHeaderValidator.MAX_TOUCH_CONTACTS) {
            return WindowCheckResult.CloseEarlyForBounds("touch_contacts")
        }
        if (screenTracker.currentScreenChangeCount() >= BatchBoundsAndHeaderValidator.MAX_SCREEN_CHANGES) {
            return WindowCheckResult.CloseEarlyForBounds("screen_changes")
        }

        WindowCheckResult.KeepOpen
    }

    /**
     * Closes the current window across all subsystems, validates header and W8-26 bounds (splitting
     * into 2+ sequenced batches if needed), and advances window-opening state for the next window.
     */
    fun closeWindow(
        closeReason: CloseReason,
        droppedSummary: DroppedBatchesSummary? = null,
        sdkVersion: SdkVersion = DEFAULT_SDK_VERSION,
        isFullWindowDuration: Boolean = (closeReason == CloseReason.CLOSE_REASON_WINDOW_FULL),
    ): List<PreparedBatch> = synchronized(lock) {
        val closedSessionUs = session.clock.sessionUs()
        val nowUnityUs = session.clock.unityUs()

        val motionFlush = motionCapture.flushWindow(nowUnityUs)
        val touchFlush = touchTracker.flushWindow()
        val screenFlush = screenTracker.flushWindow(deviceInfo.screenRecord(0L))
        val eventStateFlush = eventStateTracker.flushWindow()
        val lifecycleFlush = lifecycleTracker.flushWindow()

        diagnosticsAggregator.recordAll(motionFlush.diagnostics)
        val diagnosticsFlush = diagnosticsAggregator.flushWindow()

        val validDropped = droppedSummary?.takeIf { it.count > 0 }
        val allGaps = ArrayList<GapRecord>(
            (if (validDropped != null) 1 else 0) + motionFlush.gaps.size + lifecycleFlush.gaps.size,
        )
        if (validDropped != null) {
            allGaps.add(
                GapRecord(
                    signal = GapSignal.GAP_SIGNAL_EVERYTHING,
                    clock = Clock.CLOCK_SESSION,
                    startTimeUs = validDropped.startTimeUs,
                    endTimeUs = validDropped.endTimeUs,
                    cause = GapCause.GAP_CAUSE_BATCHES_DROPPED,
                    batchesDropped = validDropped.count,
                    firstDroppedSequence = validDropped.firstDroppedSequence,
                ),
            )
        }
        allGaps.addAll(motionFlush.gaps)
        allGaps.addAll(lifecycleFlush.gaps)

        val usesSensorClock = motionFlush.sensorBlocks.any { it.clock == Clock.CLOCK_SENSOR } ||
            allGaps.any { it.clock == Clock.CLOCK_SENSOR }
        val headerClocks = if (usesSensorClock && openingClocks.sensorUs == null) {
            openingClocks.copy(sensorUs = session.clock.sensorUs(true))
        } else {
            openingClocks
        }

        val targetLengthS = minOf(
            session.settings.batchLengthS.coerceAtLeast(1),
            BatchBoundsAndHeaderValidator.MAX_WINDOW_LENGTH_S,
        )
        val bounds = SessionClock.computeWindowBounds(
            windowStartUs = openingWindowStartUs,
            targetLengthS = targetLengthS,
            closedSessionUs = closedSessionUs,
            isFullWindow = isFullWindowDuration,
        )
        val clampedWindowLengthS = bounds.windowLengthS.coerceIn(1, BatchBoundsAndHeaderValidator.MAX_WINDOW_LENGTH_S)

        val deviceProps = deviceInfo.deviceProperties()
        val header = Header(
            idOrg = Utf8Bounds.truncateUtf8(session.idOrg),
            idDeployment = Utf8Bounds.truncateUtf8(session.idDeployment),
            idSource = Utf8Bounds.truncateUtf8(session.idSource),
            idSession = Utf8Bounds.truncateUtf8(session.idSession),
            sequence = 0L, // allocated inside encodeAndSplitToFitBounds
            schemaVersion = ASPARAGUS_SCHEMA_VERSION,
            sdkVersion = sdkVersion,
            platform = Platform.PLATFORM_ANDROID,
            osVersion = Utf8Bounds.truncateUtf8(deviceProps.osVersion),
            deviceModel = Utf8Bounds.truncateUtf8(deviceProps.model),
            settingsVersion = Utf8Bounds.truncateUtf8(session.settings.version),
            windowStartUs = bounds.windowStartUs,
            windowLengthS = clampedWindowLengthS,
            closedUs = bounds.closedUs,
            closeReason = closeReason,
            clocks = headerClocks,
            serverAnchor = session.serverAnchor,
            screen = screenFlush.openingScreen,
            hasGyroscope = deviceProps.hasGyroscope,
            batchesDropped = validDropped?.count ?: 0,
            utcOffsetMinutes = openingUtcOffsetMinutes,
        )

        val prototypeBatch = Batch(
            header = header,
            sensorBlocks = motionFlush.sensorBlocks,
            touch = touchFlush,
            events = eventStateFlush.events,
            stateRecords = eventStateFlush.stateRecords,
            lifecycle = lifecycleFlush.lifecycle,
            gaps = allGaps,
            screenChanges = screenFlush.screenChanges,
            diagnostics = diagnosticsFlush,
            carried = eventStateFlush.carried,
        )

        val prepared = BatchBoundsAndHeaderValidator.encodeAndSplitToFitBounds(
            prototypeBatch = prototypeBatch,
            compress = session.settings.compress,
            nextSequence = { nextSequence++ },
            diagnosticsSink = { diag -> diagnosticsAggregator.recordAll(listOf(diag)) },
            carriedDroppedSummary = validDropped,
        )

        // Advance opening clock/window state for the next window
        openingWindowStartUs = if (isFullWindowDuration) {
            bounds.closedUs
        } else {
            SessionClock.wholeSecondFloorUs(closedSessionUs)
        }
        openingClocks = session.clock.captureClockReading(motionCapture.isNativeSensorRunning)
        openingUtcOffsetMinutes = session.clock.currentUtcOffsetMinutes()

        prepared
    }
}
