package com.nx10.asparagus.events

/**
 * Standard outcome strings matching `core_events.json` (`"Converted"` and `"UnConverted"`).
 */
enum class Outcome(val wireValue: String) {
    CONVERTED(CoreEventsCatalog.OUTCOME_CONVERTED),
    UNCONVERTED(CoreEventsCatalog.OUTCOME_UNCONVERTED),
}

/**
 * Standard state keys and Turnip default attribute names from `core_events.json` / `events/README.md`.
 */
object StandardStateKeys {
    const val LEVEL: String = "level"
    const val SCREEN: String = "screen"
    const val MODE: String = "mode"

    const val DEFAULT_ATTR_LEVEL: String = "game_level"
    const val DEFAULT_ATTR_SCREEN: String = "view"
    const val DEFAULT_ATTR_MODE: String = "mode"
}

/**
 * SDK-managed system state keys defined in `core_events.json` (`sdk_state_keys`).
 */
object SdkStateKeys {
    const val SCREEN_BRIGHTNESS: String = CoreEventsCatalog.SDK_STATE_SCREEN_BRIGHTNESS
    const val SCREEN_LOCKED: String = CoreEventsCatalog.SDK_STATE_SCREEN_LOCKED
}
