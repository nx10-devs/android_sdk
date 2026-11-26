package com.nx10.asparagus.events

import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.OpenSpan
import com.nx10.asparagus.contract.OpenSpans
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.SpanMarker
import com.nx10.asparagus.contract.State
import com.nx10.asparagus.contract.StateKind
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.lifecycle.LifecycleAndTelemetryTracker
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.SessionClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventStateTrackerTest {

    private class FakeTimeSource(
        var elapsedNanos: Long = 10_000_000_000L,
        var wallMs: Long = 1_795_000_000_000L,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = 0
    }

    private fun newTracker(
        fake: FakeTimeSource = FakeTimeSource(),
        maxStateKeys: Int = SessionEventStateTracker.DEFAULT_MAX_STATE_KEYS,
    ): Triple<SessionEventStateTracker, DiagnosticsAggregator, SessionClock> {
        val clock = SessionClock(timeSource = fake, appStartElapsedNanos = fake.elapsedNanos)
        clock.anchor(serverUtcMs = fake.wallMs, roundTripMs = 10)
        val diagnostics = DiagnosticsAggregator(clock)
        val tracker = SessionEventStateTracker(
            sessionClock = clock,
            diagnostics = diagnostics,
            maxStateKeys = maxStateKeys,
        )
        return Triple(tracker, diagnostics, clock)
    }

    @Test
    fun `all 11 typed CoreEvents match core_events json keys, outcomes, and manual sendEvent equivalence`() {
        val fakeA = FakeTimeSource()
        val (trackerA, _, _) = newTracker(fakeA)
        val fakeB = FakeTimeSource()
        val (trackerB, _, _) = newTracker(fakeB)

        // 1. game_level_started
        trackerA.coreEvents.levelStarted(level = "3", attemptNumber = 2.0, difficulty = 5.0)
        trackerB.sendEvent(
            "game_level_started",
            linkedMapOf("level" to Value.Text("3"), "attempt_number" to Value.Number(2.0), "difficulty" to Value.Number(5.0)),
        )

        // 2. game_level_succeeded
        fakeA.elapsedNanos += 1_000_000L; fakeB.elapsedNanos += 1_000_000L
        trackerA.coreEvents.levelSucceeded(level = "3", score = 1250.5, duration = 42.0, attemptNumber = 2.0)
        trackerB.sendEvent(
            "game_level_succeeded",
            linkedMapOf("level" to Value.Text("3"), "score" to Value.Number(1250.5), "duration" to Value.Number(42.0), "attempt_number" to Value.Number(2.0)),
        )

        // 3. game_level_failed (after opening another level)
        fakeA.elapsedNanos += 1_000_000L; fakeB.elapsedNanos += 1_000_000L
        trackerA.coreEvents.levelStarted(level = "4")
        trackerB.sendEvent("game_level_started", linkedMapOf("level" to Value.Text("4")))
        trackerA.coreEvents.levelFailed(level = "4", score = 100.0, duration = 12.5, attemptNumber = 1.0)
        trackerB.sendEvent(
            "game_level_failed",
            linkedMapOf("level" to Value.Text("4"), "score" to Value.Number(100.0), "duration" to Value.Number(12.5), "attempt_number" to Value.Number(1.0)),
        )

        // 4 & 5. ad_started & ad_finished
        trackerA.coreEvents.adStarted(type = "rewarded", placement = "shop", adsInSet = 2, adNumber = 1)
        trackerB.sendEvent(
            "ad_started",
            linkedMapOf("type" to Value.Text("rewarded"), "placement" to Value.Text("shop"), "ads_in_set" to Value.Number(2.0), "ad_number" to Value.Number(1.0)),
        )
        trackerA.coreEvents.adFinished(type = "rewarded", placement = "shop", adsInSet = 2, adNumber = 1, completed = true)
        trackerB.sendEvent(
            "ad_finished",
            linkedMapOf("type" to Value.Text("rewarded"), "placement" to Value.Text("shop"), "ads_in_set" to Value.Number(2.0), "ad_number" to Value.Number(1.0), "completed" to Value.Flag(true)),
        )

        // 6. ad_revenue (Converted)
        trackerA.coreEvents.adRevenue(type = "rewarded", amount = 0.025, currency = "USD", outcome = Outcome.CONVERTED, network = "admob", reason = "ad_displayed")
        trackerB.sendOutcomeEvent(
            "ad_revenue",
            "Converted",
            linkedMapOf("type" to Value.Text("rewarded"), "network" to Value.Text("admob"), "amount" to Value.Number(0.025), "currency" to Value.Text("USD"), "reason" to Value.Text("ad_displayed")),
        )

        // 7. in_app_purchase (UnConverted)
        trackerA.coreEvents.inAppPurchase(product = "coins_500", price = 4.99, currency = "GBP", outcome = Outcome.UNCONVERTED, reason = "user_cancelled", quantity = 1, transactionId = "tx-9")
        trackerB.sendOutcomeEvent(
            "in_app_purchase",
            "UnConverted",
            linkedMapOf("product" to Value.Text("coins_500"), "price" to Value.Number(4.99), "currency" to Value.Text("GBP"), "reason" to Value.Text("user_cancelled"), "quantity" to Value.Number(1.0), "transaction_id" to Value.Text("tx-9")),
        )

        // 8 & 9. in_app_purchase_view_opened & in_app_purchase_view_closed
        trackerA.coreEvents.inAppPurchaseViewOpened(type = "shop", product = "coins_500", amount = 4.99, currency = "GBP")
        trackerB.sendEvent(
            "in_app_purchase_view_opened",
            linkedMapOf("type" to Value.Text("shop"), "product" to Value.Text("coins_500"), "amount" to Value.Number(4.99), "currency" to Value.Text("GBP")),
        )
        trackerA.coreEvents.inAppPurchaseViewClosed(type = "shop", product = "coins_500", amount = 4.99, currency = "GBP", reason = "dismissed")
        trackerB.sendEvent(
            "in_app_purchase_view_closed",
            linkedMapOf("type" to Value.Text("shop"), "product" to Value.Text("coins_500"), "amount" to Value.Number(4.99), "currency" to Value.Text("GBP"), "reason" to Value.Text("dismissed")),
        )

        // 10 & 11. gambling_round_started & gambling_round_finished
        trackerA.coreEvents.gamblingRoundStarted(gameType = "slots", machine = "lucky7", betAmount = 2.5, betCurrency = "USD", autoSpin = true)
        trackerB.sendEvent(
            "gambling_round_started",
            linkedMapOf("game_type" to Value.Text("slots"), "machine" to Value.Text("lucky7"), "bet_amount" to Value.Number(2.5), "bet_currency" to Value.Text("USD"), "auto_spin" to Value.Flag(true)),
        )
        trackerA.coreEvents.gamblingRoundFinished(gameType = "slots", result = "jackpot", betAmount = 2.5, betCurrency = "USD", returnAmount = 100.0, returnCurrency = "USD", machine = "lucky7")
        trackerB.sendEvent(
            "gambling_round_finished",
            linkedMapOf("game_type" to Value.Text("slots"), "result" to Value.Text("jackpot"), "bet_amount" to Value.Number(2.5), "bet_currency" to Value.Text("USD"), "return_amount" to Value.Number(100.0), "return_currency" to Value.Text("USD"), "machine" to Value.Text("lucky7")),
        )

        val flushA = trackerA.flushWindow()
        val flushB = trackerB.flushWindow()
        // Rule 2: typed core calls and manual sendEvent calls are 100% identical!
        assertEquals(flushA, flushB)
        assertEquals(12, flushA.events.size)

        // Verify span names & numbers across all 4 core spans
        assertEquals("level", flushA.events[0].spanName)
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, flushA.events[0].spanMarker)
        assertEquals(1, flushA.events[0].spanNumber)
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, flushA.events[1].spanMarker)
        assertEquals(1, flushA.events[1].spanNumber)

        assertEquals("ad_rewarded", flushA.events[4].spanName)
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, flushA.events[4].spanMarker)
        assertEquals(3, flushA.events[4].spanNumber)
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, flushA.events[5].spanMarker)
        assertEquals(3, flushA.events[5].spanNumber)

        assertEquals("Converted", flushA.events[6].outcome)
        assertEquals("", flushA.events[6].spanName)
        assertEquals("UnConverted", flushA.events[7].outcome)

        assertEquals("purchase_view", flushA.events[8].spanName)
        assertEquals(4, flushA.events[8].spanNumber)
        assertEquals("round", flushA.events[10].spanName)
        assertEquals(5, flushA.events[10].spanNumber)
    }

    @Test
    fun `ad span defaults handle overlapping ad_banner and ad_rewarded, missing type as ad, and caller span override`() {
        val (tracker, _, _) = newTracker()

        // Rule 5 & 9: overlapping ad_banner (span 1) and ad_rewarded (span 2)
        tracker.coreEvents.adStarted(type = "banner")
        tracker.coreEvents.adStarted(type = "rewarded")
        assertEquals(listOf("ad_banner", "ad_rewarded"), tracker.currentOpenSpans().map { it.name })

        // Missing/empty type -> default span "ad" (span 3)
        tracker.sendEvent("ad_started", emptyMap())
        // Explicit caller span wins -> "custom_ad" (span 4)
        tracker.coreEvents.adStarted(type = "interstitial", span = "custom_ad")

        tracker.coreEvents.adFinished(type = "rewarded")
        tracker.sendEvent("ad_finished", mapOf("type" to Value.Text(""))) // closes "ad" (span 3)
        tracker.coreEvents.adFinished(type = "interstitial", span = "custom_ad")

        val events = tracker.flushWindow().events
        assertEquals("ad_banner", events[0].spanName); assertEquals(1, events[0].spanNumber)
        assertEquals("ad_rewarded", events[1].spanName); assertEquals(2, events[1].spanNumber)
        assertEquals("ad", events[2].spanName); assertEquals(3, events[2].spanNumber)
        assertEquals("custom_ad", events[3].spanName); assertEquals(4, events[3].spanNumber)

        assertEquals("ad_rewarded", events[4].spanName); assertEquals(SpanMarker.SPAN_MARKER_CLOSE, events[4].spanMarker); assertEquals(2, events[4].spanNumber)
        assertEquals("ad", events[5].spanName); assertEquals(SpanMarker.SPAN_MARKER_CLOSE, events[5].spanMarker); assertEquals(3, events[5].spanNumber)
        assertEquals("custom_ad", events[6].spanName); assertEquals(SpanMarker.SPAN_MARKER_CLOSE, events[6].spanMarker); assertEquals(4, events[6].spanNumber)

        // Banner is still open
        assertEquals(listOf(OpenSpan("ad_banner", 1, 0L)), tracker.currentOpenSpans())
    }

    @Test
    fun `rules 6, 7, 8, and 14 enforce core reopen, orphan close zero, roleless toggle, and markerless inside core span`() {
        val (tracker, _, _) = newTracker()

        // Rule 8: orphan core close -> SPAN_MARKER_CLOSE, spanNumber = 0, does NOT increment nextSpanNumber
        tracker.coreEvents.levelFailed(level = "1")

        // Rule 7: core OPENS while same span already open -> replaces old span, allocates new number
        tracker.coreEvents.levelStarted(level = "1") // span 1
        tracker.coreEvents.levelStarted(level = "2") // replaces span 1 with span 2

        // Rule 6 & 14: roleless custom event (and ad_revenue) inside core-opened "level" -> markerless, spanNumber = 0, leaves span 2 open!
        tracker.sendEvent("checkpoint_reached", span = "level")
        tracker.coreEvents.adRevenue(type = "rewarded", amount = 0.01, currency = "USD", outcome = Outcome.CONVERTED, span = "level")
        assertEquals(listOf(2), tracker.currentOpenSpans().map { it.number })

        // Core CLOSES closes span 2
        tracker.coreEvents.levelSucceeded(level = "2")
        assertTrue(tracker.currentOpenSpans().isEmpty())

        // Rule 6: roleless custom event with custom span toggles OPEN (3) -> CLOSE (3) -> OPEN (4)
        tracker.sendEvent("boss_entered", span = "boss_fight")
        tracker.sendEvent("boss_defeated", span = "boss_fight")
        tracker.sendEvent("boss_entered", span = "boss_fight")

        val events = tracker.flushWindow().events
        // Orphan close
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, events[0].spanMarker)
        assertEquals(0, events[0].spanNumber)
        assertEquals("level", events[0].spanName)

        // Core opens 1 then reopens 2
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, events[1].spanMarker); assertEquals(1, events[1].spanNumber)
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, events[2].spanMarker); assertEquals(2, events[2].spanNumber)

        // Roleless events inside core-opened span -> markerless, number 0
        assertEquals("level", events[3].spanName); assertEquals(SpanMarker.SPAN_MARKER_UNSPECIFIED, events[3].spanMarker); assertEquals(0, events[3].spanNumber)
        assertEquals("level", events[4].spanName); assertEquals(SpanMarker.SPAN_MARKER_UNSPECIFIED, events[4].spanMarker); assertEquals(0, events[4].spanNumber)

        // Core closes span 2
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, events[5].spanMarker); assertEquals(2, events[5].spanNumber)

        // Roleless toggle: OPEN 3 -> CLOSE 3 -> OPEN 4
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, events[6].spanMarker); assertEquals(3, events[6].spanNumber)
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, events[7].spanMarker); assertEquals(3, events[7].spanNumber)
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, events[8].spanMarker); assertEquals(4, events[8].spanNumber)
    }

    @Test
    fun `rule 11 keeps span state across window flush, pause resume, and telemetry stop start`() {
        val fake = FakeTimeSource()
        val (tracker, _, clock) = newTracker(fake)
        val lifecycle = LifecycleAndTelemetryTracker(clock)

        tracker.coreEvents.levelStarted(level = "1") // span 1
        tracker.flushWindow()

        // Pause & resume within newSessionAfterS
        lifecycle.onPaused()
        fake.elapsedNanos += 5_000_000_000L
        fake.wallMs += 5_000L
        lifecycle.onResumed(newSessionAfterS = 1800)

        // Stop & start telemetry
        lifecycle.stopTelemetry()
        fake.elapsedNanos += 2_000_000_000L
        lifecycle.startTelemetry()

        // Span 1 is STILL open and closes cleanly with spanNumber = 1!
        assertEquals(1, tracker.currentOpenSpans().single().number)
        tracker.coreEvents.levelSucceeded(level = "1")
        val flush2 = tracker.flushWindow()
        assertEquals(1, flush2.carried.openSpans!!.spans.single().number)
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, flush2.events.single().spanMarker)
        assertEquals(1, flush2.events.single().spanNumber)
    }

    @Test
    fun `state, shared order counter, and window-opening Carried match batch_schema_2 sequence and protobuf round-trip`() {
        val fake = FakeTimeSource(elapsedNanos = 1_000_000_000L)
        val (tracker, _, _) = newTracker(fake)

        // Window 0 (session start): Carried has non-null empty State(version=0), non-null empty OpenSpans, lastEventTimeUs=null
        val initialEmpty = tracker.flushWindow()
        assertNotNull(initialEmpty.carried.state)
        assertEquals(State(emptyMap(), 0), initialEmpty.carried.state)
        assertNotNull(initialEmpty.carried.openSpans)
        assertEquals(OpenSpans(emptyList()), initialEmpty.carried.openSpans)
        assertNull(initialEmpty.carried.lastEventTimeUs)

        // Prepare pre-window state matching batch_schema_2.json (version 12, open span 7 at 2920250000, lastEvent at 2955125000)
        repeat(6) {
            tracker.coreEvents.levelStarted(level = "pre-$it")
            tracker.coreEvents.levelSucceeded(level = "pre-$it")
        }
        fake.elapsedNanos = 1_000_000_000L + 2_920_250_000_000L // unityUs = 2_920_250_000
        tracker.coreEvents.levelStarted(level = "3") // span 7!
        repeat(11) {
            tracker.setAttribute("tmp", Value.Number(it.toDouble()))
        }
        tracker.setAttributes(
            linkedMapOf(
                "level" to Value.Text("3"),
                "screen" to Value.Text("game"),
                "boss" to Value.Flag(true),
                "lives" to Value.Number(2.0),
            ),
        ) // version = 12
        fake.elapsedNanos = 1_000_000_000L + 2_955_125_000_000L // unityUs = 2_955_125_000
        tracker.fireEvent("ping")
        tracker.flushWindow() // now Window K opens with version 12, open span 7, lastEventTimeUs = 2_955_125_000!

        // Execute exact 5-call sequence of batch_schema_2.json inside Window K
        fake.elapsedNanos = 1_000_000_000L + 2_962_250_000_000L
        tracker.sendOutcomeEvent(
            name = "game_level_succeeded",
            outcome = "succeeded",
            fields = linkedMapOf(
                "level" to Value.Text("3"),
                "score" to Value.Number(1250.5),
                "perfect" to Value.Flag(true),
                "bonus" to Value.Empty,
            ),
        ) // order 1, closes span 7

        fake.elapsedNanos = 1_000_000_000L + 2_962_250_400_000L
        tracker.patchAttributes(
            values = linkedMapOf("level" to Value.Text("4")),
            removedKeys = listOf("boss"),
        ) // order 2, version 13

        fake.elapsedNanos = 1_000_000_000L + 2_962_251_000_000L
        tracker.sendEvent(
            name = "game_level_started",
            fields = linkedMapOf("level" to Value.Text("4"), "attempt" to Value.Number(1.0)),
        ) // order 3, opens span 8

        fake.elapsedNanos = 1_000_000_000L + 2_967_250_000_000L
        tracker.setAttributes(
            linkedMapOf(
                "level" to Value.Text("4"),
                "screen" to Value.Text("shop"),
                "lives" to Value.Number(3.0),
            ),
        ) // order 4, version 14

        tracker.sendEvent(
            name = "button_clicked",
            fields = linkedMapOf("button" to Value.Text("Café 小店 🛒")),
        ) // order 5

        assertEquals(3, tracker.currentEventCount())
        assertEquals(2, tracker.currentStateRecordCount())

        val winK = tracker.flushWindow()
        // Window K's Carried reflects state BEFORE Window K's 5 calls!
        assertEquals(12, winK.carried.state!!.version)
        assertEquals(Value.Flag(true), winK.carried.state!!.values["boss"])
        assertEquals(listOf(OpenSpan("level", 7, 2_920_250_000L)), winK.carried.openSpans!!.spans)
        assertEquals(2_955_125_000L, winK.carried.lastEventTimeUs)

        // Verify exact unified order 1, 2, 3, 4, 5 and versions 13, 14
        assertEquals(listOf(1, 3, 5), winK.events.map { it.order })
        assertEquals(listOf(2, 4), winK.stateRecords.map { it.order })
        assertEquals(listOf(13, 14), winK.stateRecords.map { it.version })
        assertEquals(StateKind.STATE_KIND_PATCH, winK.stateRecords[0].kind)
        assertEquals(listOf("boss"), winK.stateRecords[0].removedKeys)
        assertEquals(StateKind.STATE_KIND_PUT, winK.stateRecords[1].kind)
        assertEquals(7, winK.events[0].spanNumber)
        assertEquals(8, winK.events[1].spanNumber)

        // Window K+1's Carried reflects state AFTER Window K!
        val winKPlus1 = tracker.flushWindow()
        assertEquals(14, winKPlus1.carried.state!!.version)
        assertNull(winKPlus1.carried.state!!.values["boss"])
        assertEquals(Value.Text("shop"), winKPlus1.carried.state!!.values["screen"])
        assertEquals(listOf(OpenSpan("level", 8, 2_962_251_000L)), winKPlus1.carried.openSpans!!.spans)
        assertEquals(2_967_250_000L, winKPlus1.carried.lastEventTimeUs)

        // Verify binary Protobuf round-trip of Window K
        val batch = Batch(
            events = winK.events,
            stateRecords = winK.stateRecords,
            carried = winK.carried,
        )
        assertEquals(batch, BatchProtoCodec.decode(BatchProtoCodec.encode(batch)))
    }

    @Test
    fun `maxStateKeys cap trims PUT and ignores excess PATCH keys with state_key_limit diagnostic`() {
        val (tracker, diagnostics, _) = newTracker(maxStateKeys = 2)

        tracker.setAttributes(
            linkedMapOf(
                "a" to Value.Text("1"),
                "b" to Value.Text("2"),
                "c" to Value.Text("3"),
            ),
        )
        assertEquals(setOf("a", "b"), tracker.currentStateSnapshot().values.keys)
        assertEquals(1, tracker.currentStateSnapshot().version)

        // New key past limit is ignored cleanly without crashing
        tracker.setAttribute("d", Value.Text("4"))
        assertEquals(setOf("a", "b"), tracker.currentStateSnapshot().values.keys)
        assertEquals(1, tracker.currentStateSnapshot().version)

        // Updating existing key still succeeds
        tracker.setAttribute("a", Value.Text("updated"))
        assertEquals(Value.Text("updated"), tracker.currentStateSnapshot().values["a"])
        assertEquals(2, tracker.currentStateSnapshot().version)

        // Clear attributes emits empty PUT and bumps version to 3
        tracker.clearAttributes()
        assertTrue(tracker.currentStateSnapshot().values.isEmpty())
        assertEquals(3, tracker.currentStateSnapshot().version)

        val diags = diagnostics.flushWindow()
        assertEquals(2, diags.size)
        assertTrue(diags.all { it.severity == Severity.SEVERITY_WARNING && it.code == SessionEventStateTracker.DIAGNOSTIC_STATE_KEY_LIMIT })
    }

    @Test
    fun `updateSdkState deduplicates unchanged values and shares unified order with game events and state`() {
        val fake = FakeTimeSource()
        val (tracker, _, _) = newTracker(fake)

        // Initial SDK observation -> PATCH, order 1, version 1
        assertTrue(
            tracker.updateSdkState(
                linkedMapOf(
                    SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.75),
                    SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
                ),
            ),
        )

        // Exact duplicate -> returns false, no record, no order/version bump
        assertFalse(
            tracker.updateSdkState(
                linkedMapOf(
                    SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.75),
                    SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
                ),
            ),
        )
        assertEquals(1, tracker.currentStateRecordCount())
        assertEquals(1, tracker.currentStateSnapshot().version)

        // Game event -> order 2
        fake.elapsedNanos += 1_000_000L
        tracker.sendEvent("button_clicked", mapOf("button" to Value.Text("play")))

        // Game state mutation -> order 3, version 2
        fake.elapsedNanos += 1_000_000L
        tracker.setAttribute("level", Value.Text("2"))

        // Partial SDK change (only lock changes; brightness unchanged) -> order 4, version 3
        fake.elapsedNanos += 1_000_000L
        assertTrue(
            tracker.updateSdkState(
                linkedMapOf(
                    SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.75),
                    SdkStateKeys.SCREEN_LOCKED to Value.Flag(true),
                ),
            ),
        )

        val win1 = tracker.flushWindow()
        assertEquals(listOf(2), win1.events.map { it.order })
        assertEquals(listOf(1, 3, 4), win1.stateRecords.map { it.order })
        assertEquals(listOf(1, 2, 3), win1.stateRecords.map { it.version })
        assertTrue(win1.stateRecords.all { it.kind == StateKind.STATE_KIND_PATCH })
        assertEquals(
            linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.75),
                SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
            ),
            win1.stateRecords[0].values,
        )
        // Partial change emits only the changed SDK key
        assertEquals(
            linkedMapOf(SdkStateKeys.SCREEN_LOCKED to Value.Flag(true)),
            win1.stateRecords[2].values,
        )

        // Next window's Carried.state carries both SDK and game keys at version 3
        val win2 = tracker.flushWindow()
        assertEquals(3, win2.carried.state!!.version)
        assertEquals(Value.Number(0.75), win2.carried.state!!.values[SdkStateKeys.SCREEN_BRIGHTNESS])
        assertEquals(Value.Flag(true), win2.carried.state!!.values[SdkStateKeys.SCREEN_LOCKED])
        assertEquals(Value.Text("2"), win2.carried.state!!.values["level"])
    }

    @Test
    fun `nx10_ namespace is protected across game PUT, PATCH, clearAttributes, and exempt from maxStateKeys`() {
        val (tracker, diagnostics, _) = newTracker(maxStateKeys = 2)

        // SDK keys do NOT consume the game's maxStateKeys=2 quota
        assertTrue(
            tracker.updateSdkState(
                linkedMapOf(
                    SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.60),
                    SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
                ),
            ),
        )
        tracker.setAttribute("a", Value.Text("1"))
        tracker.setAttribute("b", Value.Text("2"))
        assertTrue(diagnostics.flushWindow().isEmpty())
        assertEquals(4, tracker.currentStateSnapshot().values.size)
        assertEquals(3, tracker.currentStateSnapshot().version)

        // 3rd game key hits maxStateKeys=2 limit
        tracker.setAttribute("c", Value.Text("3"))
        assertEquals(1, diagnostics.flushWindow().size)
        assertEquals(3, tracker.currentStateSnapshot().version)

        // Game attempts to overwrite or remove nx10_* keys are ignored (no-op, no version bump)
        tracker.setAttribute(SdkStateKeys.SCREEN_LOCKED, Value.Flag(true))
        tracker.setAttribute("nx10_custom_spoof", Value.Text("nope"))
        tracker.removeAttribute(SdkStateKeys.SCREEN_BRIGHTNESS)
        assertEquals(3, tracker.currentStateSnapshot().version)
        assertEquals(Value.Number(0.60), tracker.currentStateSnapshot().values[SdkStateKeys.SCREEN_BRIGHTNESS])
        assertEquals(Value.Flag(false), tracker.currentStateSnapshot().values[SdkStateKeys.SCREEN_LOCKED])
        assertNull(tracker.currentStateSnapshot().values["nx10_custom_spoof"])

        // Mixed patchAttributes strips nx10_* from both values and removedKeys while applying game keys
        tracker.patchAttributes(
            values = linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.0),
                "a" to Value.Text("1-updated"),
            ),
            removedKeys = listOf(SdkStateKeys.SCREEN_LOCKED, "b"),
        )
        assertEquals(4, tracker.currentStateSnapshot().version)
        assertEquals(Value.Number(0.60), tracker.currentStateSnapshot().values[SdkStateKeys.SCREEN_BRIGHTNESS])
        assertEquals(Value.Flag(false), tracker.currentStateSnapshot().values[SdkStateKeys.SCREEN_LOCKED])
        assertEquals(Value.Text("1-updated"), tracker.currentStateSnapshot().values["a"])
        assertNull(tracker.currentStateSnapshot().values["b"])

        // Game setAttributes (PUT) ignores caller nx10_* and preserves active sdkManagedState in StateRecord + currentState
        tracker.setAttributes(
            linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.0),
                "x" to Value.Text("10"),
            ),
        )
        assertEquals(5, tracker.currentStateSnapshot().version)

        // Game clearAttributes (PUT) removes game keys but preserves sdkManagedState in StateRecord + currentState
        tracker.clearAttributes()
        assertEquals(6, tracker.currentStateSnapshot().version)

        val win1 = tracker.flushWindow()
        val patchRecord = win1.stateRecords[3]
        assertEquals(StateKind.STATE_KIND_PATCH, patchRecord.kind)
        assertEquals(mapOf("a" to Value.Text("1-updated")), patchRecord.values)
        assertEquals(listOf("b"), patchRecord.removedKeys)

        val putRecord = win1.stateRecords[4]
        assertEquals(StateKind.STATE_KIND_PUT, putRecord.kind)
        assertEquals(
            linkedMapOf(
                "x" to Value.Text("10"),
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.60),
                SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
            ),
            putRecord.values,
        )

        val clearPutRecord = win1.stateRecords[5]
        assertEquals(StateKind.STATE_KIND_PUT, clearPutRecord.kind)
        assertEquals(
            linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.60),
                SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
            ),
            clearPutRecord.values,
        )

        // Next window's Carried.state still carries both SDK keys intact
        val win2 = tracker.flushWindow()
        assertEquals(6, win2.carried.state!!.version)
        assertEquals(
            linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.60),
                SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
            ),
            win2.carried.state!!.values,
        )
    }
}
