package com.nx10.asparagus.capture.touch

import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.Settings
import com.nx10.asparagus.contract.TouchEnd
import com.nx10.asparagus.contract.TouchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAndScreenTrackerTest {

    @Test
    fun `touch thinning keeps down and up, tracks eventsSeen, and omits pressure when unreported`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(touchHz = 60, holdRunLength = 0),
        )
        val tracker = TouchWindowTracker(settings = settings)

        // Finger 1: DOWN at t=1_000_000, rapid MOVEs every 5ms (200 Hz -> thinned to ~16.67ms), UP at 1_025_000
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.DOWN, 1_000_000L, 100f, 200f, 10f, 8f, pressure = null))
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.MOVE, 1_005_000L, 105f, 205f, 10f, 8f, pressure = null)) // dropped
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.MOVE, 1_010_000L, 110f, 210f, 10f, 8f, pressure = null)) // dropped
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.MOVE, 1_017_000L, 120f, 220f, 10f, 8f, pressure = null)) // kept (>= 16667us)
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.UP, 1_020_000L, 125f, 225f, 10f, 8f, pressure = null))   // always kept!

        assertEquals(1, tracker.currentContactCount())
        assertEquals(3, tracker.currentTouchSampleCount())

        val touch = tracker.flushWindow()
        assertNotNull(touch)
        assertEquals(TouchMode.TOUCH_MODE_EVENTS, touch!!.mode)
        assertEquals(60f, touch.requestedHz, 0.001f)
        assertEquals(1, touch.contacts.size)

        val c = touch.contacts.single()
        assertEquals(1, c.fingerId)
        assertFalse(c.continued)
        assertEquals(TouchEnd.TOUCH_END_LIFTED, c.end)
        assertEquals(5, c.eventsSeen) // all 5 raw events counted before thinning
        assertEquals(1_000_000L, c.firstTimeUs)
        assertEquals(listOf(17_000, 3_000), c.timeStepsUs)
        assertEquals(listOf(1_000, 200, 50), c.x)
        assertEquals(listOf(2_000, 200, 50), c.y)
        assertTrue("pressure list must be empty when device reports no pressure", c.pressure.isEmpty())
        assertNull(c.before)

        // Subsequent empty window flushes as null
        assertNull(tracker.flushWindow())
    }

    @Test
    fun `touchHold populates 4 HoldStats without pressure and 5 HoldStats with pressure`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(touchHz = 0, touchHoldTolerance = 2, holdRunLength = 3),
        )
        val trackerNoPressure = TouchWindowTracker(settings = settings)
        val trackerWithPressure = TouchWindowTracker(settings = settings)

        for (i in 0 until 4) {
            val phase = when (i) {
                0 -> TouchPhase.DOWN
                3 -> TouchPhase.UP
                else -> TouchPhase.MOVE
            }
            trackerNoPressure.onTouchSample(
                RawTouchSample(1, phase, 1_000_000L + i * 16_000L, 500f, 600f, 12f, 10f, pressure = null),
            )
            trackerWithPressure.onTouchSample(
                RawTouchSample(1, phase, 1_000_000L + i * 16_000L, 500f, 600f, 12f, 10f, pressure = 0.5f),
            )
        }

        val cNoPress = trackerNoPressure.flushWindow()!!.contacts.single()
        assertEquals(1, cNoPress.holds.size)
        assertEquals(4, cNoPress.holds.single().sampleCount)
        assertEquals(4, cNoPress.holds.single().stats.size)
        assertTrue(cNoPress.pressure.isEmpty())

        val cWithPress = trackerWithPressure.flushWindow()!!.contacts.single()
        assertEquals(1, cWithPress.holds.size)
        assertEquals(4, cWithPress.holds.single().sampleCount)
        assertEquals(5, cWithPress.holds.single().stats.size)
        assertEquals(500, cWithPress.holds.single().pressure)
    }

    @Test
    fun `cross-window continuation tracks cumulative ContactBefore across windows 1, 2, and 3`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(touchHz = 0, holdRunLength = 0),
        )
        val tracker = TouchWindowTracker(settings = settings)

        // Window 1: finger 7 touches down and moves, still down at flush
        tracker.onTouchSample(RawTouchSample(7, TouchPhase.DOWN, 1_000_000L, 100f, 300f)) // q=(1000, 3000)
        tracker.onTouchSample(RawTouchSample(7, TouchPhase.MOVE, 1_050_000L, 150f, 250f)) // q=(1500, 2500)

        val w1 = tracker.flushWindow()!!.contacts.single()
        assertEquals(7, w1.fingerId)
        assertFalse(w1.continued)
        assertEquals(TouchEnd.TOUCH_END_STILL_DOWN, w1.end)
        assertNull(w1.before)

        // Window 2: finger 7 continues moving further, still down at flush
        tracker.onTouchSample(RawTouchSample(7, TouchPhase.MOVE, 2_000_000L, 80f, 400f)) // q=(800, 4000)
        val w2 = tracker.flushWindow()!!.contacts.single()
        assertTrue(w2.continued)
        assertEquals(TouchEnd.TOUCH_END_STILL_DOWN, w2.end)
        assertNotNull(w2.before)
        assertEquals(1_000_000L, w2.before!!.downTimeUs)
        assertEquals(1_000, w2.before!!.minX)
        assertEquals(1_500, w2.before!!.maxX)
        assertEquals(2_500, w2.before!!.minY)
        assertEquals(3_000, w2.before!!.maxY)

        // Window 3: finger 7 lifts -> ContactBefore reflects ALL samples from Windows 1 + 2!
        tracker.onTouchSample(RawTouchSample(7, TouchPhase.UP, 3_000_000L, 90f, 350f))
        val w3 = tracker.flushWindow()!!.contacts.single()
        assertTrue(w3.continued)
        assertEquals(TouchEnd.TOUCH_END_LIFTED, w3.end)
        assertEquals(1_000_000L, w3.before!!.downTimeUs)
        assertEquals(800, w3.before!!.minX)
        assertEquals(1_500, w3.before!!.maxX)
        assertEquals(2_500, w3.before!!.minY)
        assertEquals(4_000, w3.before!!.maxY)

        // Same fingerId touching down again later in window 4 starts a fresh contact
        tracker.onTouchSample(RawTouchSample(7, TouchPhase.DOWN, 4_000_000L, 50f, 50f))
        tracker.onTouchSample(RawTouchSample(7, TouchPhase.UP, 4_010_000L, 50f, 50f))
        val w4 = tracker.flushWindow()!!.contacts.single()
        assertFalse(w4.continued)
        assertNull(w4.before)
    }

    @Test
    fun `idle window with still-down finger emits no zero-sample contact and preserves ContactBefore for later lift`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(touchHz = 0, holdRunLength = 0),
        )
        val tracker = TouchWindowTracker(settings = settings)

        // Window 1: finger 4 touches down and moves, remains still down at flush
        tracker.onTouchSample(RawTouchSample(4, TouchPhase.DOWN, 1_000_000L, 100f, 200f))
        tracker.onTouchSample(RawTouchSample(4, TouchPhase.MOVE, 1_050_000L, 140f, 260f))
        assertEquals(1, tracker.currentContactCount())
        val w1 = tracker.flushWindow()!!
        assertEquals(1, w1.contacts.size)
        assertEquals(TouchEnd.TOUCH_END_STILL_DOWN, w1.contacts.single().end)

        // Window 2: finger stays completely stationary (0 raw events)
        assertEquals(0, tracker.currentContactCount())
        assertNull("Idle window must not emit a zero-sample TouchContact", tracker.flushWindow())

        // Window 3: finger lifts -> emits continued=true with ContactBefore from Window 1 intact!
        tracker.onTouchSample(RawTouchSample(4, TouchPhase.UP, 3_000_000L, 140f, 260f))
        assertEquals(1, tracker.currentContactCount())
        val w3 = tracker.flushWindow()!!.contacts.single()
        assertEquals(4, w3.fingerId)
        assertTrue(w3.continued)
        assertEquals(TouchEnd.TOUCH_END_LIFTED, w3.end)
        assertNotNull(w3.before)
        assertEquals(1_000_000L, w3.before!!.downTimeUs)
        assertEquals(1_000, w3.before!!.minX)
        assertEquals(1_400, w3.before!!.maxX)
        assertEquals(2_000, w3.before!!.minY)
        assertEquals(2_600, w3.before!!.maxY)
    }

    @Test
    fun `holdRunLength of 1 requires at least 2 unchanged samples and hasPressure stays locked from first sample`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(touchHz = 0, touchHoldTolerance = 1, holdRunLength = 1),
        )
        val tracker = TouchWindowTracker(settings = settings)

        // Single DOWN + MOVE to a different point -> neither should fold into a 1-sample hold!
        // Also first sample has pressure=null, second has non-null pressure -> hasPressure stays false!
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.DOWN, 1_000_000L, 100f, 100f, pressure = null))
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.MOVE, 1_020_000L, 200f, 200f, pressure = 0.5f))
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.UP, 1_040_000L, 300f, 300f, pressure = 0.7f))

        val contact = tracker.flushWindow()!!.contacts.single()
        assertTrue("Isolated samples must not fold into 1-sample holds", contact.holds.isEmpty())
        assertEquals(3, contact.x.size)
        assertTrue("hasPressure must remain locked false from first sample", contact.pressure.isEmpty())
    }

    @Test
    fun `keyboard and sensitive-screen pause cancel active fingers and ignore touches until unpaused`() {
        val settings = SdkSettings.loadDefaults().merge(
            Settings(pauseTouchOnKeyboard = true, holdRunLength = 0),
        )
        val tracker = TouchWindowTracker(settings = settings)

        tracker.onTouchSample(RawTouchSample(1, TouchPhase.DOWN, 1_000_000L, 100f, 100f))
        tracker.setKeyboardVisible(true)
        assertTrue(tracker.isTouchPaused)

        // Ignored while paused
        tracker.onTouchSample(RawTouchSample(2, TouchPhase.DOWN, 1_020_000L, 200f, 200f))
        tracker.onTouchSample(RawTouchSample(1, TouchPhase.MOVE, 1_030_000L, 110f, 110f))

        val flushed = tracker.flushWindow()!!
        assertEquals(1, flushed.contacts.size)
        assertEquals(1, flushed.contacts.single().fingerId)
        assertEquals(TouchEnd.TOUCH_END_CANCELLED, flushed.contacts.single().end)

        // Unpause keyboard, test sensitive-screen pause too
        tracker.setKeyboardVisible(false)
        assertFalse(tracker.isTouchPaused)
        tracker.setSensitiveScreenPaused(true)
        assertTrue(tracker.isTouchPaused)
        tracker.onTouchSample(RawTouchSample(3, TouchPhase.DOWN, 2_000_000L, 300f, 300f))
        assertNull(tracker.flushWindow())
    }

    @Test
    fun `screenChangeTracker deduplicates unchanged screens and carries latest screen into next header`() {
        val portrait = ScreenRecord(
            timeUs = 999L,
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420f,
            orientation = Orientation.ORIENTATION_PORTRAIT,
            origin = CoordinateOrigin.COORDINATE_ORIGIN_TOP_LEFT,
        )
        val landscape = ScreenRecord(
            timeUs = 0L,
            widthPx = 2400,
            heightPx = 1080,
            densityDpi = 420f,
            orientation = Orientation.ORIENTATION_LANDSCAPE_LEFT,
            origin = CoordinateOrigin.COORDINATE_ORIGIN_TOP_LEFT,
        )

        val tracker = ScreenChangeTracker(portrait)
        assertEquals(0L, tracker.currentOpeningScreen().timeUs)

        // Same geometry -> ignored
        tracker.onScreenObserved(portrait, unityTimeUs = 1_500_000L)
        assertEquals(0, tracker.currentScreenChangeCount())

        // Rotation -> recorded with unityTimeUs
        tracker.onScreenObserved(landscape, unityTimeUs = 2_500_000L)
        tracker.onScreenObserved(landscape, unityTimeUs = 2_600_000L) // duplicate ignored
        assertEquals(1, tracker.currentScreenChangeCount())

        val flush1 = tracker.flushWindow()
        assertEquals(portrait.copy(timeUs = 0L), flush1.openingScreen)
        assertEquals(1, flush1.screenChanges.size)
        assertEquals(landscape.copy(timeUs = 2_500_000L), flush1.screenChanges.single())

        // Next window opens with landscape and timeUs == 0L
        val flush2 = tracker.flushWindow()
        assertEquals(landscape.copy(timeUs = 0L), flush2.openingScreen)
        assertTrue(flush2.screenChanges.isEmpty())
    }
}
