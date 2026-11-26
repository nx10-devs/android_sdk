package com.nx10.asparagus.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Thin Android [Application.ActivityLifecycleCallbacks] observer tracking process foreground/background
 * transitions (`startedCount` `1 -> 0` and `0 -> 1`) while ignoring configuration-change rotations.
 */
class AndroidAppLifecycleObserver(
    private val onAppBackgrounded: () -> Unit,
    private val onAppForegrounded: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private val lock = Any()
    private var startedCount: Int = 0
    private var isChangingConfigurations: Boolean = false
    private var registered: Boolean = false

    fun register(application: Application): Boolean = synchronized(lock) {
        if (registered) return true
        val ok = runCatching {
            application.registerActivityLifecycleCallbacks(this)
            true
        }.getOrDefault(false)
        registered = ok
        ok
    }

    fun unregister(application: Application) = synchronized(lock) {
        if (!registered) return
        runCatching {
            application.unregisterActivityLifecycleCallbacks(this)
        }
        registered = false
        startedCount = 0
        isChangingConfigurations = false
    }

    override fun onActivityStarted(activity: Activity) {
        var shouldNotifyForeground = false
        synchronized(lock) {
            val prev = startedCount
            startedCount++
            if (prev == 0) {
                if (isChangingConfigurations) {
                    isChangingConfigurations = false
                } else {
                    shouldNotifyForeground = true
                }
            }
        }
        if (shouldNotifyForeground) {
            runCatching { onAppForegrounded() }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        var shouldNotifyBackground = false
        synchronized(lock) {
            val changing = runCatching { activity.isChangingConfigurations }.getOrDefault(false)
            if (startedCount > 0) {
                startedCount--
            }
            if (startedCount == 0) {
                if (changing) {
                    isChangingConfigurations = true
                } else {
                    shouldNotifyBackground = true
                }
            }
        }
        if (shouldNotifyBackground) {
            runCatching { onAppBackgrounded() }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
