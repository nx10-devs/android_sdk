package com.nx10.asparagus.time

import android.os.SystemClock
import java.util.TimeZone

/**
 * Abstraction over Android monotonic and wall clocks for deterministic testing.
 */
interface MonotonicTimeSource {
    /** Monotonic nanoseconds since boot (same clock base as `SensorEvent.timestamp`). */
    fun elapsedRealtimeNanos(): Long

    /** Device wall-clock UTC time in milliseconds since 1970-01-01T00:00:00Z. */
    fun wallUtcMs(): Long

    /** Local offset from UTC in minutes (positive east of Greenwich per `batch.proto`). */
    fun utcOffsetMinutes(wallUtcMs: Long): Int
}

object AndroidTimeSource : MonotonicTimeSource {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun wallUtcMs(): Long = System.currentTimeMillis()

    override fun utcOffsetMinutes(wallUtcMs: Long): Int =
        TimeZone.getDefault().getOffset(wallUtcMs) / 60_000
}
