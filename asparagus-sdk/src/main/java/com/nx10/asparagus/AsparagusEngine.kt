package com.nx10.asparagus

import com.nx10.asparagus.batch.BatchAssembler
import com.nx10.asparagus.batch.BatchBoundsAndHeaderValidator
import com.nx10.asparagus.batch.WindowCheckResult
import com.nx10.asparagus.capture.motion.MotionWindowCapture
import com.nx10.asparagus.capture.touch.RawTouchSample
import com.nx10.asparagus.capture.touch.ScreenChangeTracker
import com.nx10.asparagus.capture.touch.TouchWindowTracker
import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.device.DEFAULT_SDK_VERSION
import com.nx10.asparagus.device.DeviceInfoSource
import com.nx10.asparagus.device.SystemStateCallback
import com.nx10.asparagus.device.SystemStateController
import com.nx10.asparagus.device.SystemStateControllerFactory
import com.nx10.asparagus.events.CoreEvents
import com.nx10.asparagus.events.CoreEventsCatalog
import com.nx10.asparagus.events.EventSink
import com.nx10.asparagus.events.SdkStateKeys
import com.nx10.asparagus.events.SessionEventStateTracker
import com.nx10.asparagus.internal.net.HttpTransport
import com.nx10.asparagus.internal.net.RetryBackoff
import com.nx10.asparagus.internal.net.UrlConnectionHttpTransport
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.lifecycle.LifecycleAndTelemetryTracker
import com.nx10.asparagus.queue.DiskBatchQueue
import com.nx10.asparagus.queue.EnqueueResult
import com.nx10.asparagus.session.ActiveSession
import com.nx10.asparagus.session.CollectionHistoryStore
import com.nx10.asparagus.session.ComplianceResult
import com.nx10.asparagus.session.InMemoryCollectionHistoryStore
import com.nx10.asparagus.session.SessionStartClient
import com.nx10.asparagus.session.SessionStartResult
import com.nx10.asparagus.time.AndroidTimeSource
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.ResumeDecision
import com.nx10.asparagus.time.SessionClock
import com.nx10.asparagus.upload.BatchUploader
import com.nx10.asparagus.upload.DrainSummary
import com.nx10.asparagus.upload.SessionRefreshOutcome
import com.nx10.asparagus.upload.SessionRefresher
import com.nx10.asparagus.upload.UploadEvent
import com.nx10.asparagus.upload.UploadEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Platform motion controller hook (implemented by `AndroidMotionSensorCapture` on Android).
 */
interface MotionController {
    fun start(): Boolean
    fun onPaused()
    fun onResumed()
    fun stop()
}

fun interface MotionControllerFactory {
    fun create(capture: MotionWindowCapture, clock: SessionClock): MotionController?
}

/**
 * Pure, fault-isolated Asparagus SDK orchestrator wiring session start, 4-clock timekeeping,
 * motion & touch capture, 17-rule events/spans/state, W8-26 batch assembly, disk queue, and uploader.
 *
 * All Protobuf encoding, Gzip compression, disk I/O, and network requests run off the caller's thread.
 */
class AsparagusEngine(
    private val deviceInfo: DeviceInfoSource,
    queueDir: File,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeSource: MonotonicTimeSource = AndroidTimeSource,
    private val sdkVersion: SdkVersion = DEFAULT_SDK_VERSION,
    private val defaults: SdkSettings = SdkSettings.builtInDefaults(),
    private val catalog: CoreEventsCatalog = CoreEventsCatalog.loadDefault(),
    private val retryBackoff: RetryBackoff = RetryBackoff(),
    private val motionControllerFactory: MotionControllerFactory = MotionControllerFactory { _, _ -> null },
    private val systemStateControllerFactory: SystemStateControllerFactory = SystemStateControllerFactory { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val enablePeriodicTicker: Boolean = true,
    private val sleeper: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val historyStore: CollectionHistoryStore = InMemoryCollectionHistoryStore(),
    private val developerWarningLogger: (String) -> Unit = {},
) : EventSink {

    private data class ControlPlaneCredentials(
        val apiKey: String,
        val useProduction: Boolean,
        val sessionProperties: Map<String, Value>,
        val demo: Boolean,
    )

    internal class SessionRuntime(
        val session: ActiveSession,
        val diagnostics: DiagnosticsAggregator,
        val motionCapture: MotionWindowCapture,
        val touchTracker: TouchWindowTracker,
        val screenTracker: ScreenChangeTracker,
        val eventStateTracker: SessionEventStateTracker,
        val lifecycleTracker: LifecycleAndTelemetryTracker,
        val assembler: BatchAssembler,
        val motionController: MotionController?,
        val systemStateController: SystemStateController?,
    ) {
        val brightnessLock = Any()
        var lastSentBrightness: Double? = null
        var lastSentBrightnessElapsedNanos: Long = 0L
        var pendingBrightness: Double? = null
    }

    private val stateLock = Any()
    private val flushMutex = Mutex()
    private val drainMutex = Mutex()
    private val appStartElapsedNanos: Long = timeSource.elapsedRealtimeNanos()

    val queue: DiskBatchQueue = DiskBatchQueue(queueDir = queueDir, timeSource = timeSource)

    private val sessionStartClient = SessionStartClient(
        deviceInfo = deviceInfo,
        transport = transport,
        timeSource = timeSource,
        defaults = defaults,
        retryBackoff = retryBackoff,
        sdkVersion = sdkVersion,
        appStartElapsedNanos = appStartElapsedNanos,
        ioDispatcher = ioDispatcher,
        delayFn = sleeper,
        historyStore = historyStore,
    )

    @Volatile
    private var runtime: SessionRuntime? = null

    @Volatile
    private var sessionState: SdkSessionState = SdkSessionState.IDLE

    @Volatile
    private var _useProduction: Boolean = false

    var useProduction: Boolean
        get() = _useProduction
        set(value) {
            _useProduction = value
            refreshSnapshot()
        }

    @Volatile
    var appVersionOverride: String? = null

    @Volatile
    var buildNumberOverride: String? = null

    @Volatile
    private var lastCredentials: ControlPlaneCredentials? = null

    @Volatile
    private var keyboardVisible: Boolean = false

    @Volatile
    private var sensitiveTouchPaused: Boolean = false

    @Volatile
    private var tickerJob: Job? = null

    @Volatile
    private var drainRequestedAgain: Boolean = false

    private var uploadedBatchesTotal: Int = 0
    private var refusedBatchesTotal: Int = 0
    private val recentLogs = ArrayDeque<String>(MAX_LOG_ENTRIES)

    private val _statusFlow = MutableStateFlow(SdkTelemetrySnapshot())
    val statusFlow: StateFlow<SdkTelemetrySnapshot> = _statusFlow.asStateFlow()

    val coreEvents: CoreEvents = CoreEvents(this)

    init {
        refreshSnapshot()
    }

    internal fun activeRuntime(): SessionRuntime? = runtime

    fun activeSession(): ActiveSession? = runtime?.session

    fun activeMotionCapture(): MotionWindowCapture? = runtime?.motionCapture

    fun activeTouchTracker(): TouchWindowTracker? = runtime?.touchTracker

    fun activeSessionClock(): SessionClock? = runtime?.session?.clock

    // region Session Lifecycle

    fun startSession(
        apiKey: String,
        sessionProperties: Map<String, Value> = emptyMap(),
        demo: Boolean = false,
        useProduction: Boolean = this.useProduction,
    ) {
        scope.launch(ioDispatcher) {
            runCatching {
                startSessionAwait(apiKey, sessionProperties, demo, useProduction)
            }.onFailure { ex ->
                if (ex is CancellationException) throw ex
                appendLog("startSession error: ${ex.message}")
            }
        }
    }

    suspend fun startSessionAwait(
        apiKey: String,
        sessionProperties: Map<String, Value> = emptyMap(),
        demo: Boolean = false,
        useProduction: Boolean = this.useProduction,
        maxAttempts: Int = Int.MAX_VALUE,
    ): SessionStartResult {
        return try {
            if (runtime != null) {
                closeAndTeardownCurrentSession(CloseReason.CLOSE_REASON_SESSION_ENDED)
            }

            _useProduction = useProduction
            lastCredentials = ControlPlaneCredentials(
                apiKey = apiKey,
                useProduction = useProduction,
                sessionProperties = LinkedHashMap(sessionProperties),
                demo = demo,
            )

            val envLabel = if (useProduction) "Production" else "Stage"
            val targetEndpoint = SessionStartClient.sessionStartEndpoint(useProduction)
            sessionState = SdkSessionState.STARTING
            appendLog("Starting $envLabel session against $targetEndpoint")
            refreshSnapshot()

            val result = sessionStartClient.startSession(
                apiKey = apiKey,
                useProduction = useProduction,
                sessionProperties = sessionProperties,
                demo = demo,
                maxAttempts = maxAttempts,
                appVersionOverride = appVersionOverride,
                buildNumberOverride = buildNumberOverride,
            )

            when (result) {
                is SessionStartResult.Started -> {
                    installActiveSession(result.session, label = "Control Plane ($envLabel)")
                    triggerDrainQueue()
                }
                is SessionStartResult.Refused -> {
                    sessionState = SdkSessionState.REFUSED
                    appendLog("Session start refused (HTTP ${result.statusCode ?: "N/A"}): ${result.message}")
                    refreshSnapshot()
                }
            }
            result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            sessionState = SdkSessionState.REFUSED
            appendLog("Session start fault contained: ${t.message}")
            refreshSnapshot()
            SessionStartResult.Refused(statusCode = null, message = t.message ?: "unexpected error")
        }
    }

    fun stopSession() {
        scope.launch(ioDispatcher) {
            runCatching { stopSessionAwait() }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("stopSession error: ${ex.message}")
                }
        }
    }

    suspend fun stopSessionAwait() {
        try {
            val current = runtime ?: run {
                sessionState = SdkSessionState.STOPPED
                refreshSnapshot()
                return
            }
            closeAndTeardownCurrentSession(CloseReason.CLOSE_REASON_SESSION_ENDED)
            sessionState = SdkSessionState.STOPPED
            appendLog("Session stopped (${current.session.idSession})")
            refreshSnapshot()
            // Best-effort drain of the final session-ended batch
            if (current.session.collectionEnabled && current.session.batchEndpoint.isNotBlank()) {
                drainWithSession(current.session, current.diagnostics)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("stopSession fault contained: ${t.message}")
            refreshSnapshot()
        }
    }

    // endregion

    // region Window Flush & Queue Drain

    fun requestFlushWindowNow(
        closeReason: CloseReason = CloseReason.CLOSE_REASON_WINDOW_FULL,
        isFullWindowDuration: Boolean = false,
    ) {
        scope.launch(ioDispatcher) {
            runCatching { flushWindowNow(closeReason, isFullWindowDuration) }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("flushWindowNow error: ${ex.message}")
                }
        }
    }

    suspend fun flushWindowNow(
        closeReason: CloseReason = CloseReason.CLOSE_REASON_WINDOW_FULL,
        isFullWindowDuration: Boolean = false,
    ) {
        try {
            flushMutex.withLock {
                val current = runtime ?: return
                if (!current.session.collectionEnabled) return

                flushPendingBrightnessIfDue(current)

                val preparedBatches = withContext(ioDispatcher) {
                    val dropped = queue.consumePendingDroppedSummary()
                    val batches = current.assembler.closeWindow(
                        closeReason = closeReason,
                        droppedSummary = dropped,
                        sdkVersion = sdkVersion,
                        isFullWindowDuration = isFullWindowDuration,
                    )
                    if (batches.isEmpty() && dropped != null) {
                        queue.restoreDroppedSummary(dropped)
                    }
                    batches
                }

                if (preparedBatches.isNotEmpty()) {
                    val first = preparedBatches.first()
                    val windowDurationUs = (first.closedUs - first.windowStartUs).coerceAtLeast(0L)
                    historyStore.onWindowCollected(windowDurationUs)
                }

                val settings = current.session.settings
                var enqueuedAny = false

                for (prepared in preparedBatches) {
                    if (settings.queueMaxBytes <= 0 || settings.queueMaxAgeS <= 0) {
                        val uploader = createUploader(current.diagnostics)
                        val ok = uploader.uploadImmediateWhenQueueDisabled(prepared, current.session)
                        if (ok) {
                            synchronized(stateLock) { uploadedBatchesTotal++ }
                            appendLog("Immediate upload seq=${prepared.sequence} OK (${prepared.wireBytes.size} B)")
                        } else {
                            appendLog("Immediate upload seq=${prepared.sequence} dropped (queue disabled)")
                        }
                    } else {
                        when (val enq = withContext(ioDispatcher) {
                            queue.enqueue(prepared, settings.queueMaxBytes, settings.queueMaxAgeS)
                        }) {
                            is EnqueueResult.Queued -> {
                                enqueuedAny = true
                                appendLog(
                                    "Queued seq=${prepared.sequence} (${prepared.wireBytes.size} B, " +
                                        "raw=${prepared.rawProtoBytes.size} B, reason=${closeReason.protoName})",
                                )
                            }
                            EnqueueResult.DroppedImmediately -> {
                                appendLog("Batch seq=${prepared.sequence} dropped immediately (queue limit/disk)")
                            }
                            EnqueueResult.QueueDisabled -> {
                                queue.recordUnqueuedDrop(prepared)
                            }
                        }
                    }
                }

                refreshSnapshot()
                if (enqueuedAny) {
                    triggerDrainQueue()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("flushWindowNow fault contained: ${t.message}")
            refreshSnapshot()
        }
    }

    fun triggerDrainQueue() {
        drainRequestedAgain = true
        scope.launch(ioDispatcher) {
            runCatching { drainQueueNow() }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("drainQueue error: ${ex.message}")
                }
        }
    }

    suspend fun drainQueueNow(): DrainSummary? {
        return try {
            drainMutex.withLock {
                var lastSummary: DrainSummary? = null
                do {
                    drainRequestedAgain = false
                    val current = runtime ?: break
                    val summary = drainWithSession(current.session, current.diagnostics)
                    lastSummary = summary

                    // If 401 or token expiry rotated to a new session during drain, adopt it for future batches.
                    if (summary.activeSession.idSession != current.session.idSession &&
                        runtime === current
                    ) {
                        stopTickerAndMotion(current)
                        installActiveSession(summary.activeSession, label = "Token Refresh")
                    }
                } while (drainRequestedAgain && runtime != null)
                refreshSnapshot()
                lastSummary
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("drainQueue fault contained: ${t.message}")
            refreshSnapshot()
            null
        }
    }

    // endregion

    // region Events, State, Touch, Screen & Lifecycle

    override fun sendEvent(
        name: String,
        fields: Map<String, Value>,
        span: String?,
    ) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.sendEvent(name = name, fields = fields, span = span)
            refreshSnapshot()
            if (current.eventStateTracker.currentEventCount() >= BatchBoundsAndHeaderValidator.MAX_GAME_EVENTS) {
                requestFlushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
            }
        }.onFailure { appendLog("sendEvent fault contained: ${it.message}") }
    }

    override fun sendOutcomeEvent(
        name: String,
        outcome: String,
        fields: Map<String, Value>,
        span: String?,
    ) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.sendOutcomeEvent(name = name, outcome = outcome, fields = fields, span = span)
            refreshSnapshot()
            if (current.eventStateTracker.currentEventCount() >= BatchBoundsAndHeaderValidator.MAX_GAME_EVENTS) {
                requestFlushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
            }
        }.onFailure { appendLog("sendOutcomeEvent fault contained: ${it.message}") }
    }

    fun setAttributes(attributes: Map<String, Value>) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.setAttributes(attributes)
            refreshSnapshot()
        }.onFailure { appendLog("setAttributes fault contained: ${it.message}") }
    }

    fun setAttribute(key: String, value: Value) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.setAttribute(key, value)
            refreshSnapshot()
        }.onFailure { appendLog("setAttribute fault contained: ${it.message}") }
    }

    fun patchAttributes(
        values: Map<String, Value> = emptyMap(),
        removedKeys: Collection<String> = emptyList(),
    ) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.patchAttributes(values, removedKeys)
            refreshSnapshot()
        }.onFailure { appendLog("patchAttributes fault contained: ${it.message}") }
    }

    fun removeAttribute(key: String) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.removeAttribute(key)
            refreshSnapshot()
        }.onFailure { appendLog("removeAttribute fault contained: ${it.message}") }
    }

    fun clearAttributes() {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            current.eventStateTracker.clearAttributes()
            refreshSnapshot()
        }.onFailure { appendLog("clearAttributes fault contained: ${it.message}") }
    }

    /**
     * Records deduplicated SDK-managed system state (`nx10_screen_brightness` and `nx10_screen_locked`).
     *
     * Enforces W15-04 brightness reporting rules (`events/README.md` line 153):
     * - First brightness reading in a session emits immediately.
     * - Subsequent brightness readings emit only when `|delta| >= 0.05` AND at least `1s` has elapsed
     *   since the previous brightness record; readings with `|delta| >= 0.05` inside the 1s cooldown
     *   are held in `pendingBrightness` and flushed once the cooldown expires.
     * - `nx10_screen_locked` always emits immediately on change.
     */
    fun updateSystemState(
        screenBrightness: Double? = null,
        screenLocked: Boolean? = null,
    ) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled || !current.lifecycleTracker.isTelemetryEnabled) return
            val updates = LinkedHashMap<String, Value>(2)
            val nowNanos = timeSource.elapsedRealtimeNanos()

            synchronized(current.brightnessLock) {
                if (screenBrightness != null && screenBrightness.isFinite()) {
                    val clamped = screenBrightness.coerceIn(0.0, 1.0)
                    val last = current.lastSentBrightness
                    if (last == null) {
                        updates[SdkStateKeys.SCREEN_BRIGHTNESS] = Value.Number(clamped)
                        current.lastSentBrightness = clamped
                        current.lastSentBrightnessElapsedNanos = nowNanos
                        current.pendingBrightness = null
                    } else {
                        val delta = abs(clamped - last)
                        if (delta < BRIGHTNESS_MIN_DELTA - BRIGHTNESS_DELTA_EPSILON) {
                            current.pendingBrightness = null
                        } else if ((nowNanos - current.lastSentBrightnessElapsedNanos) >= BRIGHTNESS_MIN_INTERVAL_NANOS) {
                            updates[SdkStateKeys.SCREEN_BRIGHTNESS] = Value.Number(clamped)
                            current.lastSentBrightness = clamped
                            current.lastSentBrightnessElapsedNanos = nowNanos
                            current.pendingBrightness = null
                        } else {
                            current.pendingBrightness = clamped
                        }
                    }
                } else {
                    val pending = current.pendingBrightness
                    if (pending != null &&
                        (nowNanos - current.lastSentBrightnessElapsedNanos) >= BRIGHTNESS_MIN_INTERVAL_NANOS
                    ) {
                        updates[SdkStateKeys.SCREEN_BRIGHTNESS] = Value.Number(pending)
                        current.lastSentBrightness = pending
                        current.lastSentBrightnessElapsedNanos = nowNanos
                        current.pendingBrightness = null
                    }
                }
            }

            if (screenLocked != null) {
                updates[SdkStateKeys.SCREEN_LOCKED] = Value.Flag(screenLocked)
            }
            if (updates.isNotEmpty() && current.eventStateTracker.updateSdkState(updates)) {
                refreshSnapshot()
            }
        }.onFailure { appendLog("updateSystemState fault contained: ${it.message}") }
    }

    /**
     * Emits any held brightness change once its 1-second cooldown has elapsed.
     */
    fun flushPendingBrightnessIfDue() {
        val current = runtime ?: return
        flushPendingBrightnessIfDue(current)
    }

    private fun flushPendingBrightnessIfDue(target: SessionRuntime) {
        runCatching {
            if (!target.session.collectionEnabled || !target.lifecycleTracker.isTelemetryEnabled) return
            val dueValue: Double = synchronized(target.brightnessLock) {
                val pending = target.pendingBrightness ?: return
                val nowNanos = timeSource.elapsedRealtimeNanos()
                if ((nowNanos - target.lastSentBrightnessElapsedNanos) < BRIGHTNESS_MIN_INTERVAL_NANOS) {
                    return
                }
                target.lastSentBrightness = pending
                target.lastSentBrightnessElapsedNanos = nowNanos
                target.pendingBrightness = null
                pending
            }
            if (target.eventStateTracker.updateSdkState(mapOf(SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(dueValue)))) {
                refreshSnapshot()
            }
        }
    }

    fun recordRawTouchSample(sample: RawTouchSample) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled || !current.lifecycleTracker.isTelemetryEnabled) return
            current.touchTracker.onTouchSample(sample)
            if (current.touchTracker.currentTouchSampleCount() >= BatchBoundsAndHeaderValidator.PROACTIVE_TOUCH_SAMPLE_LIMIT ||
                current.touchTracker.currentContactCount() >= BatchBoundsAndHeaderValidator.MAX_TOUCH_CONTACTS
            ) {
                requestFlushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
            }
        }.onFailure { appendLog("touch sample fault contained: ${it.message}") }
    }

    fun onScreenChanged(screen: ScreenRecord? = null) {
        runCatching {
            val current = runtime ?: return
            if (!current.session.collectionEnabled) return
            val observed = screen ?: deviceInfo.screenRecord(0L)
            current.screenTracker.onScreenObserved(observed, current.session.clock.unityUs())
            if (current.screenTracker.currentScreenChangeCount() >= BatchBoundsAndHeaderValidator.MAX_SCREEN_CHANGES) {
                requestFlushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
            }
            refreshSnapshot()
        }.onFailure { appendLog("onScreenChanged fault contained: ${it.message}") }
    }

    fun setKeyboardVisible(visible: Boolean) {
        runCatching {
            keyboardVisible = visible
            runtime?.touchTracker?.setKeyboardVisible(visible)
            refreshSnapshot()
        }.onFailure { appendLog("setKeyboardVisible fault contained: ${it.message}") }
    }

    fun setSensitiveTouchPaused(paused: Boolean) {
        runCatching {
            sensitiveTouchPaused = paused
            runtime?.touchTracker?.setSensitiveScreenPaused(paused)
            refreshSnapshot()
        }.onFailure { appendLog("setSensitiveTouchPaused fault contained: ${it.message}") }
    }

    fun stopTelemetry() {
        scope.launch(ioDispatcher) {
            runCatching { stopTelemetryAwait() }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("stopTelemetry error: ${ex.message}")
                }
        }
    }

    suspend fun stopTelemetryAwait() {
        try {
            val current = runtime ?: return
            if (!current.lifecycleTracker.stopTelemetry()) return
            runCatching { current.motionController?.stop() }
            appendLog("Telemetry stopped by game")
            if (current.session.collectionEnabled) {
                flushWindowNow(CloseReason.CLOSE_REASON_CAPTURE_STOPPED, isFullWindowDuration = false)
            }
            refreshSnapshot()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("stopTelemetry fault contained: ${t.message}")
            refreshSnapshot()
        }
    }

    fun startTelemetry() {
        runCatching {
            val current = runtime ?: return
            if (!current.lifecycleTracker.startTelemetry()) return
            if (current.session.collectionEnabled) {
                runCatching { current.motionController?.start() }
                runCatching { current.systemStateController?.onResumed() }
            }
            appendLog("Telemetry resumed by game")
            refreshSnapshot()
        }.onFailure { appendLog("startTelemetry fault contained: ${it.message}") }
    }

    fun onAppBackgrounded() {
        scope.launch(ioDispatcher) {
            runCatching { onAppBackgroundedAwait() }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("onAppBackgrounded error: ${ex.message}")
                }
        }
    }

    suspend fun onAppBackgroundedAwait() {
        try {
            val current = runtime ?: return
            if (!current.lifecycleTracker.onPaused()) return
            runCatching { current.motionController?.onPaused() }
            appendLog("App backgrounded -> closing batch")
            if (current.session.collectionEnabled) {
                flushWindowNow(CloseReason.CLOSE_REASON_PAUSED, isFullWindowDuration = false)
            }
            refreshSnapshot()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("onAppBackgrounded fault contained: ${t.message}")
            refreshSnapshot()
        }
    }

    fun onAppForegrounded() {
        scope.launch(ioDispatcher) {
            runCatching { onAppForegroundedAwait() }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("onAppForegrounded error: ${ex.message}")
                }
        }
    }

    suspend fun onAppForegroundedAwait(): ResumeDecision? {
        return try {
            val current = runtime ?: return null
            val decision = current.lifecycleTracker.onResumed(current.session.settings.newSessionAfterS)
            when (decision) {
                is ResumeDecision.ContinueSession -> {
                    runCatching { current.motionController?.onResumed() }
                    runCatching { current.systemStateController?.onResumed() }
                    val stepInfo = decision.clockStepRecord?.let { " (clock stepped +${it.clockStepS}s)" }.orEmpty()
                    appendLog("App foregrounded after ${decision.awaySeconds}s$stepInfo")
                    refreshSnapshot()
                }
                is ResumeDecision.RotateSession -> {
                    appendLog("Away ${decision.awaySeconds}s > new_session_after_s; rotating session")
                    stopTickerAndMotion(current)
                    runtime = null
                    historyStore.onSessionClosed()
                    val creds = lastCredentials
                    if (creds != null) {
                        startSessionAwait(
                            apiKey = creds.apiKey,
                            sessionProperties = creds.sessionProperties,
                            demo = creds.demo,
                            useProduction = creds.useProduction,
                        )
                    } else {
                        sessionState = SdkSessionState.STOPPED
                        refreshSnapshot()
                    }
                }
            }
            decision
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("onAppForegrounded fault contained: ${t.message}")
            refreshSnapshot()
            null
        }
    }

    fun onWindowFocusChanged(focused: Boolean) {
        runCatching {
            runtime?.lifecycleTracker?.onFocusChanged(focused)
            refreshSnapshot()
        }.onFailure { appendLog("onWindowFocusChanged fault contained: ${it.message}") }
    }

    fun onAppQuitting() {
        scope.launch(ioDispatcher) {
            runCatching { onAppQuittingAwait() }
                .onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    appendLog("onAppQuitting error: ${ex.message}")
                }
        }
    }

    suspend fun onAppQuittingAwait() {
        try {
            val current = runtime ?: return
            current.lifecycleTracker.onQuitting()
            closeAndTeardownCurrentSession(CloseReason.CLOSE_REASON_QUIT)
            sessionState = SdkSessionState.STOPPED
            appendLog("App quitting -> final batch persisted")
            refreshSnapshot()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            appendLog("onAppQuitting fault contained: ${t.message}")
            refreshSnapshot()
        }
    }

    // endregion

    // region Compliance & Obsolete Live Output Calls

    suspend fun personalDataRequested(payloadJson: String = "{}"): ComplianceResult {
        return try {
            val session = runtime?.session
                ?: return ComplianceResult.Unavailable("No active session")
            val res = sessionStartClient.requestPersonalDataAccess(session, payloadJson)
            appendLog("Personal data access request -> $res")
            refreshSnapshot()
            res
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ComplianceResult.Failure(null, t.message ?: "unexpected error")
        }
    }

    suspend fun personalDataDeletionRequested(payloadJson: String = "{}"): ComplianceResult {
        return try {
            val session = runtime?.session
                ?: return ComplianceResult.Unavailable("No active session")
            val res = sessionStartClient.requestPersonalDataDeletion(session, payloadJson)
            appendLog("Personal data deletion request -> $res")
            refreshSnapshot()
            res
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ComplianceResult.Failure(null, t.message ?: "unexpected error")
        }
    }

    @Deprecated("Live outputs are not served by Asparagus (Decision 79)")
    fun requestActivity(): ObsoleteLiveOutputResult = ObsoleteLiveOutputResult.NotAvailable()

    @Deprecated("Live outputs are not served by Asparagus (Decision 79)")
    fun requestAffect(): ObsoleteLiveOutputResult = ObsoleteLiveOutputResult.NotAvailable()

    // endregion

    fun refreshSnapshot() {
        val current = runtime
        val sess = current?.session
        val logsSnapshot = synchronized(stateLock) { recentLogs.toList() }
        val uploaded = synchronized(stateLock) { uploadedBatchesTotal }
        val refused = synchronized(stateLock) { refusedBatchesTotal }
        val stateSnapshot = current?.eventStateTracker?.currentStateSnapshot()
        val hasGyro = current?.motionCapture?.hasGyroscope
            ?: runCatching { deviceInfo.hasGyroscope() }.getOrDefault(false)
        val hasMag = current?.motionCapture?.hasMagnetometer
            ?: runCatching { deviceInfo.hasMagnetometer() }.getOrDefault(false)
        val historySnapshot = historyStore.peekCurrentHistory()

        _statusFlow.value = SdkTelemetrySnapshot(
            sessionState = sessionState,
            useProduction = _useProduction,
            sessionStartEndpoint = SessionStartClient.sessionStartEndpoint(_useProduction),
            schemaVersion = ASPARAGUS_SCHEMA_VERSION,
            hasGyroscope = hasGyro,
            hasMagnetometer = hasMag,
            screenBrightness = (stateSnapshot?.values?.get(SdkStateKeys.SCREEN_BRIGHTNESS) as? Value.Number)?.value,
            screenLocked = (stateSnapshot?.values?.get(SdkStateKeys.SCREEN_LOCKED) as? Value.Flag)?.value,
            idOrg = sess?.idOrg.orEmpty(),
            idDeployment = sess?.idDeployment.orEmpty(),
            idSource = sess?.idSource.orEmpty(),
            idSession = sess?.idSession.orEmpty(),
            batchEndpoint = sess?.batchEndpoint.orEmpty(),
            settingsVersion = sess?.settings?.version.orEmpty(),
            settingsLabel = sess?.settingsLabel.orEmpty(),
            historySessionsBefore = historySnapshot.sessionsBefore,
            historyCollectedSecondsBefore = historySnapshot.collectedSecondsBefore,
            historyLongestSessionSecondsBefore = historySnapshot.longestSessionSecondsBefore,
            batchLengthS = sess?.settings?.batchLengthS ?: 0,
            compress = sess?.settings?.compress ?: true,
            collectionEnabled = sess?.collectionEnabled ?: false,
            telemetryEnabled = current?.lifecycleTracker?.isTelemetryEnabled ?: false,
            touchPaused = current?.touchTracker?.isTouchPaused ?: (sensitiveTouchPaused || keyboardVisible),
            nextSequence = current?.assembler?.peekNextSequence() ?: 1L,
            motionSamplesInWindow = current?.motionCapture?.currentMotionSampleCount() ?: 0,
            touchContactsInWindow = current?.touchTracker?.currentContactCount() ?: 0,
            touchSamplesInWindow = current?.touchTracker?.currentTouchSampleCount() ?: 0,
            eventsInWindow = current?.eventStateTracker?.currentEventCount() ?: 0,
            stateRecordsInWindow = current?.eventStateTracker?.currentStateRecordCount() ?: 0,
            stateVersion = stateSnapshot?.version ?: 0,
            openSpans = current?.eventStateTracker?.currentOpenSpans()?.map { "${it.name}#${it.number}" } ?: emptyList(),
            queuedBatchesOnDisk = queue.size(),
            queuedBytesOnDisk = queue.totalBytes(),
            pendingDroppedBatches = queue.peekPendingDroppedSummary()?.count ?: 0,
            uploadedBatchesTotal = uploaded,
            refusedBatchesTotal = refused,
            recentLogs = logsSnapshot,
        )
    }

    // region Internals

    private fun installActiveSession(session: ActiveSession, label: String) {
        val diagnostics = DiagnosticsAggregator(session.clock)
        val hasGyro = runCatching { deviceInfo.hasGyroscope() }.getOrDefault(false)
        val hasMag = runCatching { deviceInfo.hasMagnetometer() }.getOrDefault(false)
        val motionCapture = MotionWindowCapture(
            settings = session.settings,
            hasGyroscope = hasGyro,
            hasGravity = true,
            hasMagnetometer = hasMag,
        )
        val touchTracker = TouchWindowTracker(settings = session.settings).apply {
            setKeyboardVisible(keyboardVisible)
            setSensitiveScreenPaused(sensitiveTouchPaused)
        }
        val openingScreen = runCatching { deviceInfo.screenRecord(0L) }.getOrDefault(ScreenRecord())
        val screenTracker = ScreenChangeTracker(openingScreen)
        val eventStateTracker = SessionEventStateTracker(
            sessionClock = session.clock,
            catalog = catalog,
            diagnostics = diagnostics,
            developerWarningLogger = { msg ->
                appendLog(msg)
                developerWarningLogger(msg)
            },
        )
        val lifecycleTracker = LifecycleAndTelemetryTracker(session.clock)
        val motionController = if (session.collectionEnabled) {
            runCatching { motionControllerFactory.create(motionCapture, session.clock) }.getOrNull()
        } else {
            null
        }
        val systemStateController = if (session.collectionEnabled) {
            runCatching {
                systemStateControllerFactory.create(
                    SystemStateCallback { brightness, locked ->
                        updateSystemState(screenBrightness = brightness, screenLocked = locked)
                    },
                )
            }.getOrNull()
        } else {
            null
        }

        if (session.collectionEnabled) {
            runCatching { motionController?.start() }
        }

        val assembler = BatchAssembler(
            session = session,
            deviceInfo = deviceInfo,
            motionCapture = motionCapture,
            touchTracker = touchTracker,
            screenTracker = screenTracker,
            eventStateTracker = eventStateTracker,
            lifecycleTracker = lifecycleTracker,
            diagnosticsAggregator = diagnostics,
        )

        val newRuntime = SessionRuntime(
            session = session,
            diagnostics = diagnostics,
            motionCapture = motionCapture,
            touchTracker = touchTracker,
            screenTracker = screenTracker,
            eventStateTracker = eventStateTracker,
            lifecycleTracker = lifecycleTracker,
            assembler = assembler,
            motionController = motionController,
            systemStateController = systemStateController,
        )

        runtime = newRuntime
        sessionState = if (session.collectionEnabled) {
            SdkSessionState.ACTIVE
        } else {
            SdkSessionState.COLLECTION_DISABLED
        }

        if (session.collectionEnabled) {
            runCatching { systemStateController?.start() }
        }

        appendLog(
            "$label session active: id=${session.idSession}, collect=${session.collectionEnabled}, " +
                "batch=${session.settings.batchLengthS}s, gzip=${session.settings.compress}",
        )

        if (enablePeriodicTicker && session.collectionEnabled) {
            startPeriodicTicker(newRuntime)
        }
        refreshSnapshot()
    }

    private fun startPeriodicTicker(targetRuntime: SessionRuntime) {
        tickerJob?.cancel()
        tickerJob = scope.launch(ioDispatcher) {
            while (isActive && runtime === targetRuntime) {
                sleeper(TICK_INTERVAL_MS)
                if (runtime !== targetRuntime) break
                runCatching {
                    flushPendingBrightnessIfDue(targetRuntime)
                    targetRuntime.motionCapture.evaluateHealth(
                        nowUnityUs = targetRuntime.session.clock.unityUs(),
                        isForeground = !targetRuntime.lifecycleTracker.isPaused,
                    )
                    when (val check = targetRuntime.assembler.shouldCloseWindowEarly(queue.hasPendingDroppedSummary())) {
                        WindowCheckResult.KeepOpen -> Unit
                        WindowCheckResult.CloseFullWindow -> {
                            flushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = true)
                        }
                        is WindowCheckResult.CloseEarlyForBounds -> {
                            appendLog("Early window close for W8-26 bound: ${check.reason}")
                            flushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
                        }
                    }
                    refreshSnapshot()
                }
            }
        }
    }

    private suspend fun closeAndTeardownCurrentSession(closeReason: CloseReason) {
        val current = runtime ?: return
        stopTickerAndMotion(current)
        if (current.session.collectionEnabled) {
            flushWindowNow(closeReason = closeReason, isFullWindowDuration = false)
        }
        runtime = null
        historyStore.onSessionClosed()
    }

    private fun stopTickerAndMotion(target: SessionRuntime) {
        tickerJob?.cancel()
        tickerJob = null
        runCatching { target.motionController?.stop() }
        runCatching { target.systemStateController?.stop() }
    }

    private fun createUploader(diagnostics: DiagnosticsAggregator): BatchUploader {
        val uploadListener = UploadEventListener { event ->
            when (event) {
                is UploadEvent.Succeeded -> {
                    synchronized(stateLock) { uploadedBatchesTotal++ }
                    appendLog("Uploaded seq=${event.entry.sequence} -> HTTP ${event.statusCode} (${event.uploadStatus.protoName})")
                }
                is UploadEvent.RetryScheduled -> {
                    appendLog("Retry seq=${event.entry.sequence} in ${event.waitMs}ms (${event.reason})")
                }
                is UploadEvent.RefusedDropped -> {
                    synchronized(stateLock) { refusedBatchesTotal++ }
                    appendLog("Refused seq=${event.entry.sequence} dropped (${event.code})")
                }
                is UploadEvent.TokenRefreshed -> {
                    appendLog("Session refreshed: ${event.previousSessionId} -> ${event.newSessionId}")
                }
            }
            refreshSnapshot()
        }

        return BatchUploader(
            queue = queue,
            transport = transport,
            retryBackoff = retryBackoff,
            diagnostics = diagnostics,
            listener = uploadListener,
            ioDispatcher = ioDispatcher,
            sleeper = sleeper,
        )
    }

    private suspend fun drainWithSession(
        session: ActiveSession,
        diagnostics: DiagnosticsAggregator,
    ): DrainSummary {
        val uploader = createUploader(diagnostics)
        val refresher = SessionRefresher {
            val creds = lastCredentials ?: return@SessionRefresher SessionRefreshOutcome.PermanentlyRefused
            when (val res = sessionStartClient.startSession(
                apiKey = creds.apiKey,
                useProduction = creds.useProduction,
                sessionProperties = creds.sessionProperties,
                demo = creds.demo,
                maxAttempts = 1,
                appVersionOverride = appVersionOverride,
                buildNumberOverride = buildNumberOverride,
            )) {
                is SessionStartResult.Started -> SessionRefreshOutcome.Refreshed(res.session)
                is SessionStartResult.Refused -> {
                    if (sessionStartClient.isRefusedInThisAppRun) {
                        SessionRefreshOutcome.PermanentlyRefused
                    } else {
                        SessionRefreshOutcome.TransientFailure
                    }
                }
            }
        }
        return uploader.drainQueue(initialSession = session, sessionRefresher = refresher)
    }

    private fun appendLog(line: String) {
        synchronized(stateLock) {
            if (recentLogs.size >= MAX_LOG_ENTRIES) {
                recentLogs.removeFirst()
            }
            recentLogs.addLast(line)
        }
    }

    // endregion

    companion object {
        private const val TICK_INTERVAL_MS: Long = 250L
        private const val MAX_LOG_ENTRIES: Int = 50
        const val BRIGHTNESS_MIN_DELTA: Double = 0.05
        private const val BRIGHTNESS_DELTA_EPSILON: Double = 1e-9
        const val BRIGHTNESS_MIN_INTERVAL_NANOS: Long = 1_000_000_000L
    }
}
