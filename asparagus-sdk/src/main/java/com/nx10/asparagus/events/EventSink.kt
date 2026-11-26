package com.nx10.asparagus.events

import com.nx10.asparagus.contract.Value

/**
 * Sink interface for recording game events (including outcome events and obsolete `fireEvent` forwarding).
 */
interface EventSink {
    fun sendEvent(
        name: String,
        fields: Map<String, Value> = emptyMap(),
        span: String? = null,
    )

    fun sendOutcomeEvent(
        name: String,
        outcome: String,
        fields: Map<String, Value> = emptyMap(),
        span: String? = null,
    )

    fun sendOutcomeEvent(
        name: String,
        outcome: Outcome,
        fields: Map<String, Value> = emptyMap(),
        span: String? = null,
    ) {
        sendOutcomeEvent(name = name, outcome = outcome.wireValue, fields = fields, span = span)
    }

    /**
     * Obsolete Turnip-era call forwarded directly to [sendEvent] per `02-unity-sdk.md` line 405.
     */
    fun fireEvent(
        name: String,
        fields: Map<String, Value> = emptyMap(),
    ) {
        sendEvent(name = name, fields = fields, span = null)
    }
}
