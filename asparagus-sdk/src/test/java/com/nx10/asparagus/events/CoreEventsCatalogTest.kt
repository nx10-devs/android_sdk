package com.nx10.asparagus.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEventsCatalogTest {

    @Test
    fun `loadDefault parses all 11 core events, 4 spans, 3 state keys, and both outcome texts`() {
        val catalog = CoreEventsCatalog.loadDefault()

        assertEquals(CoreEventsCatalog.OUTCOME_CONVERTED, catalog.outcomeConverted)
        assertEquals(CoreEventsCatalog.OUTCOME_UNCONVERTED, catalog.outcomeUnconverted)
        assertEquals("Converted", catalog.outcomeConverted)
        assertEquals("UnConverted", catalog.outcomeUnconverted)

        // All 11 core events in fixed order
        assertEquals(
            listOf(
                "game_level_started",
                "game_level_succeeded",
                "game_level_failed",
                "ad_started",
                "ad_finished",
                "ad_revenue",
                "in_app_purchase",
                "in_app_purchase_view_opened",
                "in_app_purchase_view_closed",
                "gambling_round_started",
                "gambling_round_finished",
            ),
            catalog.events.map { it.name },
        )

        val levelStart = catalog.findEvent("game_level_started")
        assertNotNull(levelStart)
        assertEquals("level", levelStart!!.span)
        assertEquals(CoreEventRole.OPENS, levelStart.role)
        assertFalse(levelStart.carriesOutcome)
        assertEquals(listOf("level", "attempt_number", "difficulty"), levelStart.fields.map { it.key })
        assertTrue(levelStart.fields[0].expected)
        assertEquals(CoreFieldType.TEXT, levelStart.fields[0].type)

        val adRevenue = catalog.findEvent("ad_revenue")
        assertNotNull(adRevenue)
        assertNull(adRevenue!!.span)
        assertEquals(CoreEventRole.NONE, adRevenue.role)
        assertTrue(adRevenue.carriesOutcome)

        val iap = catalog.findEvent("in_app_purchase")
        assertNotNull(iap)
        assertTrue(iap!!.carriesOutcome)

        // All 4 spans
        assertEquals(listOf("level", "ad", "purchase_view", "round"), catalog.spans.map { it.name })
        assertEquals("level", catalog.resolveOurSpan("level")?.name)
        assertEquals("ad", catalog.resolveOurSpan("ad")?.name)
        assertEquals("ad", catalog.resolveOurSpan("ad_rewarded")?.name)
        assertEquals("ad", catalog.resolveOurSpan("ad_banner")?.name)
        assertEquals("purchase_view", catalog.resolveOurSpan("purchase_view")?.name)
        assertEquals("round", catalog.resolveOurSpan("round")?.name)
        assertNull(catalog.resolveOurSpan("boss_fight"))

        // All 3 standard state keys
        assertEquals(
            listOf(
                "level" to "game_level",
                "screen" to "view",
                "mode" to "mode",
            ),
            catalog.stateKeys.map { it.key to it.defaultAttribute },
        )

        // All 2 SDK-managed state keys (Schema v3)
        assertEquals(
            listOf(
                CoreEventsCatalog.SDK_STATE_SCREEN_BRIGHTNESS to CoreFieldType.NUMBER,
                CoreEventsCatalog.SDK_STATE_SCREEN_LOCKED to CoreFieldType.FLAG,
            ),
            catalog.sdkStateKeys.map { it.key to it.type },
        )
        assertEquals(CoreFieldType.NUMBER, catalog.findSdkStateKey("nx10_screen_brightness")?.type)
        assertEquals(CoreFieldType.FLAG, catalog.findSdkStateKey("nx10_screen_locked")?.type)
    }
}
