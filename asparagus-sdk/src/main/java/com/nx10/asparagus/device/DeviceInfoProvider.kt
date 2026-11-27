package com.nx10.asparagus.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Surface
import androidx.core.content.pm.PackageInfoCompat
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.DeviceProperties
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.internal.util.Utf8Bounds
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

val DEFAULT_SDK_VERSION: SdkVersion = SdkVersion(major = 0, minor = 2, patch = 0)

data class AppVersionInfo(
    val appVersion: String,
    val buildNumber: String,
)

/**
 * Provides device identity, hardware/OS properties, screen geometry, and host app version metadata.
 */
interface DeviceInfoSource {
    fun deviceIdentifier(): String
    fun deviceProperties(): DeviceProperties
    fun screenRecord(timeUs: Long = 0L): ScreenRecord
    fun hasGyroscope(): Boolean
    fun hasMagnetometer(): Boolean = false
    fun appVersionInfo(): AppVersionInfo
}

/**
 * Android implementation of [DeviceInfoSource].
 *
 * Uses `Settings.Secure.ANDROID_ID` for [deviceIdentifier] (with a persisted anonymous UUID fallback
 * if `ANDROID_ID` is unavailable) and never reads personal device names, emails, or phone numbers.
 */
class AndroidDeviceInfoProvider(context: Context) : DeviceInfoSource {
    private val appContext: Context = context.applicationContext
    private val idLock = Any()

    @Volatile
    private var cachedDeviceId: String? = null

    @SuppressLint("HardwareIds")
    override fun deviceIdentifier(): String {
        cachedDeviceId?.let { return it }
        synchronized(idLock) {
            cachedDeviceId?.let { return it }
            val androidId = runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()

            val resolved = if (!androidId.isNullOrBlank()) {
                androidId
            } else {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existing = prefs.getString(KEY_FALLBACK_DEVICE_ID, null)
                if (!existing.isNullOrBlank()) {
                    existing
                } else {
                    val generated = UUID.randomUUID().toString()
                    prefs.edit().putString(KEY_FALLBACK_DEVICE_ID, generated).apply()
                    generated
                }
            }

            val bounded = Utf8Bounds.truncateUtf8(resolved, Utf8Bounds.MAX_HEADER_TEXT_BYTES)
            cachedDeviceId = bounded
            return bounded
        }
    }

    override fun deviceProperties(): DeviceProperties {
        val dm = appContext.resources.displayMetrics
        val osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotEmpty() }
            ?: Build.VERSION.SDK_INT.toString()

        return DeviceProperties(
            maker = Utf8Bounds.truncateUtf8(Build.MANUFACTURER.orEmpty()),
            model = Utf8Bounds.truncateUtf8(Build.MODEL.orEmpty()),
            osVersion = Utf8Bounds.truncateUtf8(osVersion),
            deviceType = DEVICE_TYPE_HANDHELD,
            screenWidthPx = dm.widthPixels.coerceAtLeast(0),
            screenHeightPx = dm.heightPixels.coerceAtLeast(0),
            screenDensityDpi = dm.densityDpi.toFloat(),
            hasGyroscope = hasGyroscope(),
            timezone = Utf8Bounds.truncateUtf8(TimeZone.getDefault().id.orEmpty()),
            locale = Utf8Bounds.truncateUtf8(Locale.getDefault().toLanguageTag()),
        )
    }

    override fun screenRecord(timeUs: Long): ScreenRecord {
        val dm = appContext.resources.displayMetrics
        return ScreenRecord(
            timeUs = timeUs,
            widthPx = dm.widthPixels.coerceAtLeast(0),
            heightPx = dm.heightPixels.coerceAtLeast(0),
            densityDpi = dm.densityDpi.toFloat(),
            orientation = currentOrientation(),
            origin = CoordinateOrigin.COORDINATE_ORIGIN_TOP_LEFT,
        )
    }

    override fun hasGyroscope(): Boolean {
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        return runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        }.getOrDefault(false)
    }

    override fun hasMagnetometer(): Boolean {
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        return runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        }.getOrDefault(false)
    }

    override fun appVersionInfo(): AppVersionInfo {
        return runCatching {
            val pm = appContext.packageManager
            val pkgName = appContext.packageName
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, 0)
            }
            val rawVersionName = pkgInfo.versionName?.trim().orEmpty()
            val buildNum = PackageInfoCompat.getLongVersionCode(pkgInfo).toString()
            val resolvedAppVersion = rawVersionName.ifEmpty { buildNum.ifEmpty { FALLBACK_APP_VERSION } }
            AppVersionInfo(
                appVersion = Utf8Bounds.truncateUtf8(resolvedAppVersion),
                buildNumber = Utf8Bounds.truncateUtf8(buildNum),
            )
        }.getOrDefault(AppVersionInfo(appVersion = FALLBACK_APP_VERSION, buildNumber = FALLBACK_BUILD_NUMBER))
    }

    private fun currentOrientation(): Orientation {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val rotation = runCatching {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull()

        return when (rotation) {
            Surface.ROTATION_0 -> Orientation.ORIENTATION_PORTRAIT
            Surface.ROTATION_180 -> Orientation.ORIENTATION_PORTRAIT_UPSIDE_DOWN
            Surface.ROTATION_90 -> Orientation.ORIENTATION_LANDSCAPE_LEFT
            Surface.ROTATION_270 -> Orientation.ORIENTATION_LANDSCAPE_RIGHT
            else -> when (appContext.resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> Orientation.ORIENTATION_PORTRAIT
                Configuration.ORIENTATION_LANDSCAPE -> Orientation.ORIENTATION_LANDSCAPE_LEFT
                else -> Orientation.ORIENTATION_UNSPECIFIED
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "com.nx10.asparagus.device"
        private const val KEY_FALLBACK_DEVICE_ID = "fallback_device_id"
        private const val DEVICE_TYPE_HANDHELD = "Handheld"
        const val FALLBACK_APP_VERSION = "0.0.0"
        const val FALLBACK_BUILD_NUMBER = "0"
    }
}
