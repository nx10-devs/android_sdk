package com.nx10.asparagus.lifecycle

import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.time.SessionClock

/**
 * Thread-safe per-window aggregator of SDK internal warnings and errors (`batch.proto` lines 534-544).
 *
 * Deduplicates identical `(severity, code, detail)` entries within a window by summing `count`
 * while preserving the earliest `timeUs`, and caps distinct entries per window to bound batch size.
 */
class DiagnosticsAggregator(
    private val sessionClock: SessionClock,
    private val maxDistinctPerWindow: Int = DEFAULT_MAX_DISTINCT_PER_WINDOW,
) {
    init {
        require(maxDistinctPerWindow >= 1) { "maxDistinctPerWindow must be >= 1: $maxDistinctPerWindow" }
    }

    private data class Key(
        val severity: Severity,
        val code: String,
        val detail: String,
    )

    private class Bucket(
        var earliestTimeUs: Long,
        var count: Int,
    )

    private val lock = Any()
    private val buckets = LinkedHashMap<Key, Bucket>()

    fun record(
        severity: Severity,
        code: String,
        detail: String = "",
        timeUs: Long = sessionClock.unityUs(),
        increment: Int = 1,
    ) = synchronized(lock) {
        val safeIncrement = increment.coerceAtLeast(1)
        val key = Key(severity = severity, code = code, detail = detail)
        val existing = buckets[key]
        if (existing != null) {
            if (timeUs < existing.earliestTimeUs) {
                existing.earliestTimeUs = timeUs
            }
            existing.count = addSaturating(existing.count, safeIncrement)
        } else if (buckets.size < maxDistinctPerWindow) {
            buckets[key] = Bucket(earliestTimeUs = timeUs, count = safeIncrement)
        }
    }

    fun recordAll(items: Iterable<Diagnostic>) = synchronized(lock) {
        for (item in items) {
            record(
                severity = item.severity,
                code = item.code,
                detail = item.detail,
                timeUs = item.timeUs,
                increment = item.count.coerceAtLeast(1),
            )
        }
    }

    fun currentCount(): Int = synchronized(lock) { buckets.size }

    fun flushWindow(): List<Diagnostic> = synchronized(lock) {
        if (buckets.isEmpty()) return emptyList()
        val result = buckets.map { (key, bucket) ->
            Diagnostic(
                timeUs = bucket.earliestTimeUs,
                severity = key.severity,
                code = key.code,
                detail = key.detail,
                count = bucket.count,
            )
        }
        buckets.clear()
        result
    }

    private fun addSaturating(a: Int, b: Int): Int {
        val sum = a.toLong() + b.toLong()
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val DEFAULT_MAX_DISTINCT_PER_WINDOW: Int = 64
    }
}
