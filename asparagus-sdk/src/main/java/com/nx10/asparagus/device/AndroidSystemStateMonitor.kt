package com.nx10.asparagus.device

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.math.round

/**
 * Callback receiving observed device system state (`nx10_screen_brightness` and `nx10_screen_locked`).
 */
fun interface SystemStateCallback {
    fun onSystemStateObserved(screenBrightness: Double?, screenLocked: Boolean?)
}

/**
 * Platform-neutral controller hook for observing SDK-managed system state.
 */
interface SystemStateController {
    fun start()
    fun onResumed()
    fun stop()
}

fun interface SystemStateControllerFactory {
    fun create(callback: SystemStateCallback): SystemStateController?
}

/**
 * Normalizes Android `Settings.System.SCREEN_BRIGHTNESS` (`0..255`) into `[0.0, 1.0]`
 * rounded to `0.01` steps so brightness slider drags don't flood `StateRecord`s.
 */
fun normalizeAndroidBrightness(rawBrightness: Int): Double =
    (round((rawBrightness.coerceIn(0, 255).toDouble() / 255.0) * 100.0) / 100.0).coerceIn(0.0, 1.0)

/**
 * Android observer for `nx10_screen_brightness` (`ContentObserver` on `Settings.System.SCREEN_BRIGHTNESS`)
 * and `nx10_screen_locked` (`KeyguardManager.isKeyguardLocked` + screen/unlock `BroadcastReceiver`).
 *
 * Uses `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` for Android 14+ (`targetSdk = 37`)
 * safety and wraps all OS calls in `runCatching` per Priority 2.
 */
class AndroidSystemStateMonitor(
    context: Context,
    private val callback: SystemStateCallback,
) : SystemStateController {

    private val appContext: Context = context.applicationContext
    private val lock = Any()

    private var started: Boolean = false
    private var brightnessObserver: ContentObserver? = null
    private var lockReceiver: BroadcastReceiver? = null

    fun readCurrentBrightness(): Double? = runCatching {
        val raw = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        normalizeAndroidBrightness(raw)
    }.getOrNull()

    fun readCurrentScreenLocked(): Boolean? = runCatching {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.isKeyguardLocked
    }.getOrNull()

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true

            runCatching {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        emitCurrentState()
                    }
                }
                val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                if (uri != null) {
                    appContext.contentResolver.registerContentObserver(uri, false, observer)
                    brightnessObserver = observer
                }
            }

            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        emitCurrentState()
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                lockReceiver = receiver
            }
        }

        emitCurrentState()
    }

    override fun onResumed() {
        emitCurrentState()
    }

    override fun stop() = synchronized(lock) {
        if (!started) return
        started = false

        val observer = brightnessObserver
        brightnessObserver = null
        if (observer != null) {
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }

        val receiver = lockReceiver
        lockReceiver = null
        if (receiver != null) {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun emitCurrentState() {
        runCatching {
            callback.onSystemStateObserved(readCurrentBrightness(), readCurrentScreenLocked())
        }
    }
}
