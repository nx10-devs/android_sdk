package com.nx10.asparagus.capture.touch

import com.nx10.asparagus.contract.ScreenRecord

data class ScreenWindowFlush(
    val openingScreen: ScreenRecord,
    val screenChanges: List<ScreenRecord>,
)

/**
 * Tracks window-opening screen geometry (`Header.screen`, `timeUs = 0L`) and deduplicated
 * in-window `screenChanges` stamped on `CLOCK_UNITY`.
 */
class ScreenChangeTracker(initialScreen: ScreenRecord) {
    private val lock = Any()

    private var openingScreen: ScreenRecord = initialScreen.copy(timeUs = 0L)
    private var latestScreen: ScreenRecord = openingScreen
    private val changes = mutableListOf<ScreenRecord>()

    fun currentOpeningScreen(): ScreenRecord = synchronized(lock) { openingScreen }

    fun currentScreenChangeCount(): Int = synchronized(lock) { changes.size }

    fun onScreenObserved(screen: ScreenRecord, unityTimeUs: Long) = synchronized(lock) {
        if (!sameGeometry(latestScreen, screen)) {
            changes.add(screen.copy(timeUs = unityTimeUs))
            latestScreen = screen.copy(timeUs = 0L)
        }
    }

    fun flushWindow(nextOpeningScreen: ScreenRecord? = null): ScreenWindowFlush = synchronized(lock) {
        val result = ScreenWindowFlush(
            openingScreen = openingScreen,
            screenChanges = changes.toList(),
        )
        changes.clear()
        val next = (nextOpeningScreen ?: latestScreen).copy(timeUs = 0L)
        openingScreen = next
        latestScreen = next
        result
    }

    private fun sameGeometry(a: ScreenRecord, b: ScreenRecord): Boolean =
        a.widthPx == b.widthPx &&
            a.heightPx == b.heightPx &&
            a.densityDpi == b.densityDpi &&
            a.orientation == b.orientation &&
            a.origin == b.origin
}
