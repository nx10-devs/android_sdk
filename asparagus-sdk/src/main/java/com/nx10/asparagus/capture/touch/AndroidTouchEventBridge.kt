package com.nx10.asparagus.capture.touch

import android.os.SystemClock
import android.view.MotionEvent
import com.nx10.asparagus.time.SessionClock

/**
 * Observer-only Android [MotionEvent] adapter feeding [TouchWindowTracker] (including batched
 * historical `ACTION_MOVE` points). Never consumes host app input or throws into the game.
 */
class AndroidTouchEventBridge(
    private val tracker: TouchWindowTracker,
    private val clock: SessionClock,
) {
    fun observeMotionEvent(event: MotionEvent?) {
        if (event == null) return
        runCatching {
            val nowUnityUs = clock.unityUs()
            val nowUptimeMs = SystemClock.uptimeMillis()
            val hasPressure = runCatching {
                event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE) != null
            }.getOrDefault(false)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                -> {
                    val idx = event.actionIndex
                    emitCurrentPointer(event, idx, TouchPhase.DOWN, nowUnityUs, nowUptimeMs, hasPressure)
                }

                MotionEvent.ACTION_MOVE -> {
                    val historySize = event.historySize
                    val pointerCount = event.pointerCount
                    for (h in 0 until historySize) {
                        val sampleUnityUs = mapUptimeMsToUnityUs(
                            sampleUptimeMs = event.getHistoricalEventTime(h),
                            nowUptimeMs = nowUptimeMs,
                            nowUnityUs = nowUnityUs,
                        )
                        for (p in 0 until pointerCount) {
                            tracker.onTouchSample(
                                RawTouchSample(
                                    fingerId = event.getPointerId(p),
                                    phase = TouchPhase.MOVE,
                                    timeUs = sampleUnityUs,
                                    xPx = event.getHistoricalX(p, h),
                                    yPx = event.getHistoricalY(p, h),
                                    sizeMajor = event.getHistoricalTouchMajor(p, h),
                                    sizeMinor = event.getHistoricalTouchMinor(p, h),
                                    pressure = if (hasPressure) event.getHistoricalPressure(p, h) else null,
                                ),
                            )
                        }
                    }
                    for (p in 0 until pointerCount) {
                        emitCurrentPointer(event, p, TouchPhase.MOVE, nowUnityUs, nowUptimeMs, hasPressure)
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                -> {
                    val idx = event.actionIndex
                    emitCurrentPointer(event, idx, TouchPhase.UP, nowUnityUs, nowUptimeMs, hasPressure)
                }

                MotionEvent.ACTION_CANCEL -> {
                    val pointerCount = event.pointerCount
                    for (p in 0 until pointerCount) {
                        emitCurrentPointer(event, p, TouchPhase.CANCEL, nowUnityUs, nowUptimeMs, hasPressure)
                    }
                }
            }
        }
    }

    private fun emitCurrentPointer(
        event: MotionEvent,
        pointerIndex: Int,
        phase: TouchPhase,
        nowUnityUs: Long,
        nowUptimeMs: Long,
        hasPressure: Boolean,
    ) {
        if (pointerIndex !in 0 until event.pointerCount) return
        val sampleUnityUs = mapUptimeMsToUnityUs(
            sampleUptimeMs = event.eventTime,
            nowUptimeMs = nowUptimeMs,
            nowUnityUs = nowUnityUs,
        )
        tracker.onTouchSample(
            RawTouchSample(
                fingerId = event.getPointerId(pointerIndex),
                phase = phase,
                timeUs = sampleUnityUs,
                xPx = event.getX(pointerIndex),
                yPx = event.getY(pointerIndex),
                sizeMajor = event.getTouchMajor(pointerIndex),
                sizeMinor = event.getTouchMinor(pointerIndex),
                pressure = if (hasPressure) event.getPressure(pointerIndex) else null,
            ),
        )
    }

    private fun mapUptimeMsToUnityUs(
        sampleUptimeMs: Long,
        nowUptimeMs: Long,
        nowUnityUs: Long,
    ): Long {
        val ageMs = (nowUptimeMs - sampleUptimeMs).coerceAtLeast(0L)
        return (nowUnityUs - ageMs * 1_000L).coerceAtLeast(0L)
    }
}
