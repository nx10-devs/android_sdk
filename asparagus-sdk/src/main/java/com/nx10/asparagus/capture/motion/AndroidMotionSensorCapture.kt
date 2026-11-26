package com.nx10.asparagus.capture.motion

import android.content.Context
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.Sensor as ContractSensor
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.time.SessionClock

/**
 * Thin Android [SensorEventListener] adapter capturing Accelerometer, Gyroscope (if present),
 * and Gravity on a dedicated [HandlerThread] into [MotionWindowCapture.ringBuffer].
 *
 * All errors are caught and converted into [Diagnostic] records per Priority 2.
 */
class AndroidMotionSensorCapture(
    context: Context,
    private val capture: MotionWindowCapture,
    private val clock: SessionClock,
) : SensorEventListener {

    private val appContext: Context = context.applicationContext
    private val lock = Any()

    private var sensorManager: SensorManager? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var started: Boolean = false

    fun start(): Boolean = synchronized(lock) {
        if (started) return true

        val manager = runCatching {
            appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }.getOrNull()

        if (manager == null) {
            capture.recordDiagnostic(
                Diagnostic(
                    timeUs = clock.unityUs(),
                    severity = Severity.SEVERITY_WARNING,
                    code = MotionWindowCapture.DIAGNOSTIC_SENSOR_REFUSED,
                    detail = "SensorManager service unavailable",
                    count = 1,
                ),
            )
            capture.markStarted(clock.unityUs(), nativeRunning = false)
            return false
        }

        val thread = HandlerThread("nx10-asparagus-motion").apply { start() }
        val threadHandler = Handler(thread.looper)

        sensorManager = manager
        handlerThread = thread
        handler = threadHandler

        val settings = capture.settings
        var registeredCount = 0

        if (settings.accelerometerHz > 0) {
            if (registerSensor(manager, AndroidSensor.TYPE_ACCELEROMETER, settings.accelerometerHz, threadHandler, "accelerometer")) {
                registeredCount++
            }
        }
        if (capture.hasGyroscope && settings.gyroscopeHz > 0) {
            if (registerSensor(manager, AndroidSensor.TYPE_GYROSCOPE, settings.gyroscopeHz, threadHandler, "gyroscope")) {
                registeredCount++
            }
        }
        if (capture.hasGravity && settings.gravityHz > 0) {
            if (registerSensor(manager, AndroidSensor.TYPE_GRAVITY, settings.gravityHz, threadHandler, "gravity")) {
                registeredCount++
            }
        }
        if (capture.hasMagnetometer && settings.magnetometerHz > 0) {
            if (registerSensor(manager, AndroidSensor.TYPE_MAGNETIC_FIELD, settings.magnetometerHz, threadHandler, "magnetometer")) {
                registeredCount++
            }
        }

        started = registeredCount > 0
        if (!started) {
            runCatching { thread.quitSafely() }
            handlerThread = null
            handler = null
        }

        capture.markStarted(clock.unityUs(), nativeRunning = started)
        started
    }

    /**
     * Keeps native listeners active through full-screen ads while honoring `native_buffer_s == 0` pause drop.
     */
    fun onPaused() {
        capture.ringBuffer.setPaused(true)
    }

    fun onResumed() {
        capture.ringBuffer.setPaused(false)
    }

    fun stop() = synchronized(lock) {
        if (started) {
            runCatching { sensorManager?.unregisterListener(this) }
        }
        started = false
        handler = null
        val thread = handlerThread
        handlerThread = null
        if (thread != null) {
            runCatching { thread.quitSafely() }
        }
        capture.markStopped()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.size < 3) return
        val sensorProtoNumber = when (event.sensor?.type) {
            AndroidSensor.TYPE_ACCELEROMETER -> ContractSensor.SENSOR_ACCELEROMETER.protoNumber
            AndroidSensor.TYPE_GYROSCOPE -> ContractSensor.SENSOR_GYROSCOPE.protoNumber
            AndroidSensor.TYPE_GRAVITY -> ContractSensor.SENSOR_GRAVITY.protoNumber
            AndroidSensor.TYPE_MAGNETIC_FIELD -> ContractSensor.SENSOR_MAGNETOMETER.protoNumber
            else -> return
        }
        val timeUs = event.timestamp / 1_000L
        capture.ringBuffer.push(
            sensorProtoNumber = sensorProtoNumber,
            timeUs = timeUs,
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
        )
    }

    override fun onAccuracyChanged(sensor: AndroidSensor?, accuracy: Int) {
        // No-op: accuracy changes are not part of the batch schema.
    }

    private fun registerSensor(
        manager: SensorManager,
        androidType: Int,
        requestedHz: Int,
        targetHandler: Handler,
        label: String,
    ): Boolean {
        val sensor = runCatching { manager.getDefaultSensor(androidType) }.getOrNull()
        if (sensor == null) {
            return false
        }
        // Unprivileged Android ceiling is 200 Hz (5,000 us period) per 02-unity-sdk.md line 91.
        val samplingPeriodUs = (1_000_000 / requestedHz.coerceAtLeast(1)).coerceAtLeast(MIN_SAMPLING_PERIOD_US)
        val ok = runCatching {
            manager.registerListener(this, sensor, samplingPeriodUs, targetHandler)
        }.getOrElse { ex ->
            capture.recordDiagnostic(
                Diagnostic(
                    timeUs = clock.unityUs(),
                    severity = Severity.SEVERITY_WARNING,
                    code = MotionWindowCapture.DIAGNOSTIC_SENSOR_REFUSED,
                    detail = "$label registration error: ${ex.message}",
                    count = 1,
                ),
            )
            return false
        }

        if (!ok) {
            capture.recordDiagnostic(
                Diagnostic(
                    timeUs = clock.unityUs(),
                    severity = Severity.SEVERITY_WARNING,
                    code = MotionWindowCapture.DIAGNOSTIC_SENSOR_REFUSED,
                    detail = "$label refused by SensorManager",
                    count = 1,
                ),
            )
        }
        return ok
    }

    companion object {
        private const val MIN_SAMPLING_PERIOD_US: Int = 5_000
    }
}
