package com.nx10.asparagus

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import com.nx10.asparagus.capture.motion.AndroidMotionSensorCapture
import com.nx10.asparagus.capture.touch.AndroidTouchEventBridge
import com.nx10.asparagus.capture.touch.RawTouchSample
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.device.AndroidDeviceInfoProvider
import com.nx10.asparagus.device.AndroidSystemStateMonitor
import com.nx10.asparagus.device.SystemStateControllerFactory
import com.nx10.asparagus.events.CoreEvents
import com.nx10.asparagus.events.EventSink
import com.nx10.asparagus.lifecycle.AndroidAppLifecycleObserver
import com.nx10.asparagus.session.AndroidCollectionHistoryStore
import com.nx10.asparagus.session.ComplianceResult
import com.nx10.asparagus.session.SessionStartClient
import com.nx10.asparagus.session.SessionStartResult
import com.nx10.asparagus.upload.DrainSummary
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Public Android facade for the Asparagus Telemetry SDK (`com.nx10.asparagus`).
 *
 * Wraps [AsparagusEngine] with Android device metadata, native `SensorManager` capture,
 * observer-only `MotionEvent` multi-touch capture, and `Application.ActivityLifecycleCallbacks`.
 * Every call is fault-contained so an SDK error can never crash the host app (Priority 2).
 */
class AsparagusSdk(context: Context) : EventSink {
    private val appContext: Context = context.applicationContext
    private val deviceInfo = AndroidDeviceInfoProvider(appContext)
    private val queueDir = File(appContext.filesDir, QUEUE_DIR_NAME)

    private val engine = AsparagusEngine(
        deviceInfo = deviceInfo,
        queueDir = queueDir,
        motionControllerFactory = MotionControllerFactory { capture, clock ->
            val androidCapture = AndroidMotionSensorCapture(appContext, capture, clock)
            object : MotionController {
                override fun start(): Boolean = androidCapture.start()
                override fun onPaused() = androidCapture.onPaused()
                override fun onResumed() = androidCapture.onResumed()
                override fun stop() = androidCapture.stop()
            }
        },
        systemStateControllerFactory = SystemStateControllerFactory { callback ->
            AndroidSystemStateMonitor(appContext, callback)
        },
        historyStore = AndroidCollectionHistoryStore(appContext),
        developerWarningLogger = { msg -> runCatching { Log.w(LOG_TAG, msg) } },
    )

    private val lifecycleObserver = AndroidAppLifecycleObserver(
        onAppBackgrounded = { engine.onAppBackgrounded() },
        onAppForegrounded = { engine.onAppForegrounded() },
    )

    @Volatile
    private var cachedBridgeRuntime: AsparagusEngine.SessionRuntime? = null

    @Volatile
    private var cachedTouchBridge: AndroidTouchEventBridge? = null

    var useProduction: Boolean
        get() = engine.useProduction
        set(value) {
            runCatching { engine.useProduction = value }
        }

    var appVersionOverride: String?
        get() = engine.appVersionOverride
        set(value) {
            runCatching { engine.appVersionOverride = value }
        }

    var buildNumberOverride: String?
        get() = engine.buildNumberOverride
        set(value) {
            runCatching { engine.buildNumberOverride = value }
        }

    val statusFlow: StateFlow<SdkTelemetrySnapshot>
        get() = engine.statusFlow

    val coreEvents: CoreEvents
        get() = engine.coreEvents

    init {
        (appContext as? Application)?.let { app ->
            lifecycleObserver.register(app)
        }
    }

    fun startSession(
        apiKey: String,
        sessionProperties: Map<String, Value> = emptyMap(),
        demo: Boolean = false,
        useProduction: Boolean = this.useProduction,
    ) {
        runCatching { engine.startSession(apiKey, sessionProperties, demo, useProduction) }
    }

    suspend fun startSessionAwait(
        apiKey: String,
        sessionProperties: Map<String, Value> = emptyMap(),
        demo: Boolean = false,
        useProduction: Boolean = this.useProduction,
    ): SessionStartResult = engine.startSessionAwait(apiKey, sessionProperties, demo, useProduction)

    fun stopSession() {
        runCatching { engine.stopSession() }
    }

    suspend fun stopSessionAwait() {
        engine.stopSessionAwait()
    }

    fun flushWindowNow() {
        runCatching { engine.requestFlushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false) }
    }

    fun drainQueue() {
        runCatching { engine.triggerDrainQueue() }
    }

    suspend fun drainQueueNow(): DrainSummary? = engine.drainQueueNow()

    override fun sendEvent(
        name: String,
        fields: Map<String, Value>,
        span: String?,
    ) {
        engine.sendEvent(name, fields, span)
    }

    override fun sendOutcomeEvent(
        name: String,
        outcome: String,
        fields: Map<String, Value>,
        span: String?,
    ) {
        engine.sendOutcomeEvent(name, outcome, fields, span)
    }

    fun setAttributes(attributes: Map<String, Value>) = engine.setAttributes(attributes)

    fun setAttribute(key: String, value: Value) = engine.setAttribute(key, value)

    fun patchAttributes(
        values: Map<String, Value> = emptyMap(),
        removedKeys: Collection<String> = emptyList(),
    ) = engine.patchAttributes(values, removedKeys)

    fun removeAttribute(key: String) = engine.removeAttribute(key)

    fun clearAttributes() = engine.clearAttributes()

    /**
     * Observer-only hook called from `Activity.dispatchTouchEvent(ev)`. Never consumes input or throws.
     */
    fun observeMotionEvent(event: MotionEvent?) {
        if (event == null) return
        runCatching {
            val current = engine.activeRuntime() ?: return
            if (!current.session.collectionEnabled || !current.lifecycleTracker.isTelemetryEnabled) return
            val bridge = if (cachedBridgeRuntime === current && cachedTouchBridge != null) {
                cachedTouchBridge!!
            } else {
                AndroidTouchEventBridge(current.touchTracker, current.session.clock).also {
                    cachedBridgeRuntime = current
                    cachedTouchBridge = it
                }
            }
            bridge.observeMotionEvent(event)
            engine.refreshSnapshot()
        }
    }

    fun recordRawTouchSample(sample: RawTouchSample) = engine.recordRawTouchSample(sample)

    fun onScreenChanged(screen: ScreenRecord? = null) = engine.onScreenChanged(screen)

    fun setKeyboardVisible(visible: Boolean) = engine.setKeyboardVisible(visible)

    fun setSensitiveTouchPaused(paused: Boolean) = engine.setSensitiveTouchPaused(paused)

    fun startTelemetry() = engine.startTelemetry()

    fun stopTelemetry() = engine.stopTelemetry()

    fun onWindowFocusChanged(focused: Boolean) = engine.onWindowFocusChanged(focused)

    fun onAppQuitting() = engine.onAppQuitting()

    suspend fun personalDataRequested(payloadJson: String = "{}"): ComplianceResult =
        engine.personalDataRequested(payloadJson)

    suspend fun personalDataDeletionRequested(payloadJson: String = "{}"): ComplianceResult =
        engine.personalDataDeletionRequested(payloadJson)

    @Suppress("DEPRECATION")
    @Deprecated("Live outputs are not served by Asparagus (Decision 79)")
    fun requestActivity(): ObsoleteLiveOutputResult = engine.requestActivity()

    @Suppress("DEPRECATION")
    @Deprecated("Live outputs are not served by Asparagus (Decision 79)")
    fun requestAffect(): ObsoleteLiveOutputResult = engine.requestAffect()

    fun shutdown() {
        runCatching {
            (appContext as? Application)?.let { app ->
                lifecycleObserver.unregister(app)
            }
            engine.stopSession()
        }
    }

    companion object {
        const val STAGE_SESSION_START_ENDPOINT: String = SessionStartClient.STAGE_SESSION_START_ENDPOINT
        const val PRODUCTION_SESSION_START_ENDPOINT: String = SessionStartClient.PRODUCTION_SESSION_START_ENDPOINT

        private const val LOG_TAG = "AsparagusSdk"
        private const val QUEUE_DIR_NAME = "asparagus_queue"

        @Volatile
        private var instance: AsparagusSdk? = null

        fun getOrCreate(context: Context): AsparagusSdk {
            return instance ?: synchronized(this) {
                instance ?: AsparagusSdk(context.applicationContext).also { instance = it }
            }
        }
    }
}
