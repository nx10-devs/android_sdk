package com.nx10.asparagus.session

import android.content.Context
import com.nx10.asparagus.contract.CollectionHistory

/**
 * Tracks per-install [CollectionHistory] (`sessions_before`, `collected_seconds_before`,
 * `longest_session_seconds_before`) across sessions and app launches per `session.proto` lines 70-82.
 */
interface CollectionHistoryStore {
    /**
     * Finalizes any unclosed prior session (e.g., after an unexpected process kill) and returns the
     * [CollectionHistory] to send in `SessionStartRequest.history`.
     */
    fun readBeforeSessionStart(): CollectionHistory

    /**
     * Marks a new asparagus session as started after a `200 OK` session start response.
     * Finalizes any unclosed prior session first if still open.
     */
    fun onSessionStarted()

    /**
     * Accumulates collected window duration (in microseconds) for the currently open session.
     */
    fun onWindowCollected(durationUs: Long)

    /**
     * Finalizes the currently open session (if any) into persisted prior-session history counters.
     */
    fun onSessionClosed()

    /**
     * Returns a non-mutating snapshot of the committed history counts for UI/diagnostics.
     */
    fun peekCurrentHistory(): CollectionHistory
}

/**
 * Thread-safe in-memory [CollectionHistoryStore] used as the default in JVM unit tests.
 */
class InMemoryCollectionHistoryStore(
    initialHistory: CollectionHistory = CollectionHistory(),
) : CollectionHistoryStore {
    private val lock = Any()
    private var sessionsBefore: Int = initialHistory.sessionsBefore.coerceAtLeast(0)
    private var collectedSecondsBefore: Long = initialHistory.collectedSecondsBefore.coerceAtLeast(0L)
    private var longestSessionSecondsBefore: Int = initialHistory.longestSessionSecondsBefore.coerceAtLeast(0)
    private var activeOpen: Boolean = false
    private var activeCollectedUs: Long = 0L

    override fun readBeforeSessionStart(): CollectionHistory = synchronized(lock) {
        finalizeActiveLocked()
        snapshotLocked()
    }

    override fun onSessionStarted() = synchronized(lock) {
        finalizeActiveLocked()
        activeOpen = true
        activeCollectedUs = 0L
    }

    override fun onWindowCollected(durationUs: Long) = synchronized(lock) {
        if (!activeOpen || durationUs <= 0L) return@synchronized
        activeCollectedUs = safeAddLong(activeCollectedUs, durationUs)
    }

    override fun onSessionClosed() = synchronized(lock) {
        finalizeActiveLocked()
    }

    override fun peekCurrentHistory(): CollectionHistory = synchronized(lock) {
        snapshotLocked()
    }

    private fun finalizeActiveLocked() {
        if (!activeOpen) return
        val sessionSecondsLong = (activeCollectedUs / 1_000_000L).coerceAtLeast(0L)
        val sessionSecondsInt = sessionSecondsLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        sessionsBefore = if (sessionsBefore < Int.MAX_VALUE) sessionsBefore + 1 else Int.MAX_VALUE
        collectedSecondsBefore = safeAddLong(collectedSecondsBefore, sessionSecondsLong)
        if (sessionSecondsInt > longestSessionSecondsBefore) {
            longestSessionSecondsBefore = sessionSecondsInt
        }
        activeOpen = false
        activeCollectedUs = 0L
    }

    private fun snapshotLocked(): CollectionHistory = CollectionHistory(
        sessionsBefore = sessionsBefore,
        collectedSecondsBefore = collectedSecondsBefore,
        longestSessionSecondsBefore = longestSessionSecondsBefore,
    )

    private fun safeAddLong(a: Long, b: Long): Long {
        if (b <= 0L) return a
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}

/**
 * Fault-isolated Android [CollectionHistoryStore] persisted in `SharedPreferences`
 * (`com.nx10.asparagus.history`). Lost cleanly with app data per `session.proto` lines 71-73.
 */
class AndroidCollectionHistoryStore(context: Context) : CollectionHistoryStore {
    private val appContext: Context = context.applicationContext
    private val lock = Any()

    override fun readBeforeSessionStart(): CollectionHistory = synchronized(lock) {
        runCatching {
            finalizeActiveLocked()
            readSnapshotLocked()
        }.getOrDefault(CollectionHistory())
    }

    override fun onSessionStarted() {
        synchronized(lock) {
            runCatching {
                finalizeActiveLocked()
                prefs().edit()
                    .putBoolean(KEY_ACTIVE_OPEN, true)
                    .putLong(KEY_ACTIVE_COLLECTED_US, 0L)
                    .apply()
            }
        }
    }

    override fun onWindowCollected(durationUs: Long) {
        if (durationUs <= 0L) return
        synchronized(lock) {
            runCatching {
                val p = prefs()
                if (!p.getBoolean(KEY_ACTIVE_OPEN, false)) return@runCatching
                val currentUs = p.getLong(KEY_ACTIVE_COLLECTED_US, 0L).coerceAtLeast(0L)
                val updatedUs = if (Long.MAX_VALUE - currentUs < durationUs) Long.MAX_VALUE else currentUs + durationUs
                p.edit().putLong(KEY_ACTIVE_COLLECTED_US, updatedUs).apply()
            }
        }
    }

    override fun onSessionClosed() {
        synchronized(lock) {
            runCatching {
                finalizeActiveLocked()
            }
        }
    }

    override fun peekCurrentHistory(): CollectionHistory = synchronized(lock) {
        runCatching {
            readSnapshotLocked()
        }.getOrDefault(CollectionHistory())
    }

    private fun finalizeActiveLocked() {
        val p = prefs()
        if (!p.getBoolean(KEY_ACTIVE_OPEN, false)) return

        val prevSessions = p.getInt(KEY_SESSIONS_BEFORE, 0).coerceAtLeast(0)
        val prevCollected = p.getLong(KEY_COLLECTED_SECONDS_BEFORE, 0L).coerceAtLeast(0L)
        val prevLongest = p.getInt(KEY_LONGEST_SESSION_SECONDS_BEFORE, 0).coerceAtLeast(0)
        val activeUs = p.getLong(KEY_ACTIVE_COLLECTED_US, 0L).coerceAtLeast(0L)

        val sessionSecondsLong = activeUs / 1_000_000L
        val sessionSecondsInt = sessionSecondsLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nextSessions = if (prevSessions < Int.MAX_VALUE) prevSessions + 1 else Int.MAX_VALUE
        val nextCollected = if (Long.MAX_VALUE - prevCollected < sessionSecondsLong) {
            Long.MAX_VALUE
        } else {
            prevCollected + sessionSecondsLong
        }
        val nextLongest = maxOf(prevLongest, sessionSecondsInt)

        p.edit()
            .putInt(KEY_SESSIONS_BEFORE, nextSessions)
            .putLong(KEY_COLLECTED_SECONDS_BEFORE, nextCollected)
            .putInt(KEY_LONGEST_SESSION_SECONDS_BEFORE, nextLongest)
            .putBoolean(KEY_ACTIVE_OPEN, false)
            .putLong(KEY_ACTIVE_COLLECTED_US, 0L)
            .apply()
    }

    private fun readSnapshotLocked(): CollectionHistory {
        val p = prefs()
        return CollectionHistory(
            sessionsBefore = p.getInt(KEY_SESSIONS_BEFORE, 0).coerceAtLeast(0),
            collectedSecondsBefore = p.getLong(KEY_COLLECTED_SECONDS_BEFORE, 0L).coerceAtLeast(0L),
            longestSessionSecondsBefore = p.getInt(KEY_LONGEST_SESSION_SECONDS_BEFORE, 0).coerceAtLeast(0),
        )
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "com.nx10.asparagus.history"
        private const val KEY_SESSIONS_BEFORE = "sessions_before"
        private const val KEY_COLLECTED_SECONDS_BEFORE = "collected_seconds_before"
        private const val KEY_LONGEST_SESSION_SECONDS_BEFORE = "longest_session_seconds_before"
        private const val KEY_ACTIVE_OPEN = "active_open"
        private const val KEY_ACTIVE_COLLECTED_US = "active_collected_us"
    }
}
