package com.nx10.asparagus.internal.net

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Exponential retry backoff with up-to-50% random shortening per `session.proto` lines 212-217
 * and `BUILDING_AN_SDK.md` line 226.
 */
class RetryBackoff(
    private val jitterSource: () -> Double = { Random.Default.nextDouble() },
) {
    /**
     * Computes the jittered delay in milliseconds for zero-based retry [attempt].
     *
     * - Unjittered base doubles each attempt: `firstWait * 2^attempt`, capped at `longestWait`.
     * - Shortened by a random fraction in `[0.0, 0.5]`, returning a value in `[max(1, 0.5 * base), base]`.
     */
    fun computeDelayMs(attempt: Int, firstWaitMs: Int, longestWaitMs: Int): Long {
        val longest = maxOf(1, longestWaitMs).toLong()
        val first = minOf(maxOf(1, firstWaitMs).toLong(), longest)
        val safeAttempt = attempt.coerceAtLeast(0)

        val base = if (safeAttempt >= 30) {
            longest
        } else {
            minOf(longest, first * (1L shl safeAttempt))
        }

        val reduction = jitterSource().coerceIn(0.0, 1.0) * 0.5
        return (base * (1.0 - reduction)).roundToLong().coerceIn(1L, longest)
    }
}
