package com.nx10.asparagus.events

import com.nx10.asparagus.contract.Value

/**
 * Typed shortcuts for all 11 core events defined in `asparagus_contract/core_events.json`.
 *
 * Per Rule 2 of `events/README.md`, each call is strictly a shortcut delegating to [EventSink]
 * with exact `snake_case` field keys and adds no hidden metadata.
 */
class CoreEvents(private val sink: EventSink) {

    fun levelStarted(
        level: String,
        attemptNumber: Double? = null,
        difficulty: Double? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "game_level_started",
            fields = buildFields(extraFields) {
                put("level", Value.Text(level))
                attemptNumber?.let { put("attempt_number", Value.Number(it)) }
                difficulty?.let { put("difficulty", Value.Number(it)) }
            },
            span = span,
        )
    }

    fun levelSucceeded(
        level: String,
        score: Double? = null,
        duration: Double? = null,
        attemptNumber: Double? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "game_level_succeeded",
            fields = buildFields(extraFields) {
                put("level", Value.Text(level))
                score?.let { put("score", Value.Number(it)) }
                duration?.let { put("duration", Value.Number(it)) }
                attemptNumber?.let { put("attempt_number", Value.Number(it)) }
            },
            span = span,
        )
    }

    fun levelFailed(
        level: String,
        score: Double? = null,
        duration: Double? = null,
        attemptNumber: Double? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "game_level_failed",
            fields = buildFields(extraFields) {
                put("level", Value.Text(level))
                score?.let { put("score", Value.Number(it)) }
                duration?.let { put("duration", Value.Number(it)) }
                attemptNumber?.let { put("attempt_number", Value.Number(it)) }
            },
            span = span,
        )
    }

    fun adStarted(
        type: String,
        placement: String? = null,
        adsInSet: Int? = null,
        adNumber: Int? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "ad_started",
            fields = buildFields(extraFields) {
                put("type", Value.Text(type))
                placement?.let { put("placement", Value.Text(it)) }
                adsInSet?.let { put("ads_in_set", Value.Number(it.toDouble())) }
                adNumber?.let { put("ad_number", Value.Number(it.toDouble())) }
            },
            span = span,
        )
    }

    fun adFinished(
        type: String,
        placement: String? = null,
        adsInSet: Int? = null,
        adNumber: Int? = null,
        completed: Boolean? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "ad_finished",
            fields = buildFields(extraFields) {
                put("type", Value.Text(type))
                placement?.let { put("placement", Value.Text(it)) }
                adsInSet?.let { put("ads_in_set", Value.Number(it.toDouble())) }
                adNumber?.let { put("ad_number", Value.Number(it.toDouble())) }
                completed?.let { put("completed", Value.Flag(it)) }
            },
            span = span,
        )
    }

    fun adRevenue(
        type: String,
        amount: Double,
        currency: String,
        outcome: Outcome,
        network: String? = null,
        reason: String? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendOutcomeEvent(
            name = "ad_revenue",
            outcome = outcome,
            fields = buildFields(extraFields) {
                put("type", Value.Text(type))
                network?.let { put("network", Value.Text(it)) }
                put("amount", Value.Number(amount))
                put("currency", Value.Text(currency))
                reason?.let { put("reason", Value.Text(it)) }
            },
            span = span,
        )
    }

    fun inAppPurchase(
        product: String,
        price: Double,
        currency: String,
        outcome: Outcome,
        reason: String? = null,
        quantity: Int? = null,
        transactionId: String? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendOutcomeEvent(
            name = "in_app_purchase",
            outcome = outcome,
            fields = buildFields(extraFields) {
                put("product", Value.Text(product))
                put("price", Value.Number(price))
                put("currency", Value.Text(currency))
                reason?.let { put("reason", Value.Text(it)) }
                quantity?.let { put("quantity", Value.Number(it.toDouble())) }
                transactionId?.let { put("transaction_id", Value.Text(it)) }
            },
            span = span,
        )
    }

    fun inAppPurchaseViewOpened(
        type: String,
        product: String? = null,
        amount: Double? = null,
        currency: String? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "in_app_purchase_view_opened",
            fields = buildFields(extraFields) {
                put("type", Value.Text(type))
                product?.let { put("product", Value.Text(it)) }
                amount?.let { put("amount", Value.Number(it)) }
                currency?.let { put("currency", Value.Text(it)) }
            },
            span = span,
        )
    }

    fun inAppPurchaseViewClosed(
        type: String,
        product: String? = null,
        amount: Double? = null,
        currency: String? = null,
        reason: String? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "in_app_purchase_view_closed",
            fields = buildFields(extraFields) {
                put("type", Value.Text(type))
                product?.let { put("product", Value.Text(it)) }
                amount?.let { put("amount", Value.Number(it)) }
                currency?.let { put("currency", Value.Text(it)) }
                reason?.let { put("reason", Value.Text(it)) }
            },
            span = span,
        )
    }

    fun gamblingRoundStarted(
        gameType: String,
        machine: String? = null,
        betAmount: Double? = null,
        betCurrency: String? = null,
        autoSpin: Boolean? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "gambling_round_started",
            fields = buildFields(extraFields) {
                put("game_type", Value.Text(gameType))
                machine?.let { put("machine", Value.Text(it)) }
                betAmount?.let { put("bet_amount", Value.Number(it)) }
                betCurrency?.let { put("bet_currency", Value.Text(it)) }
                autoSpin?.let { put("auto_spin", Value.Flag(it)) }
            },
            span = span,
        )
    }

    fun gamblingRoundFinished(
        gameType: String,
        result: String,
        betAmount: Double? = null,
        betCurrency: String? = null,
        returnAmount: Double? = null,
        returnCurrency: String? = null,
        machine: String? = null,
        span: String? = null,
        extraFields: Map<String, Value> = emptyMap(),
    ) {
        sink.sendEvent(
            name = "gambling_round_finished",
            fields = buildFields(extraFields) {
                put("game_type", Value.Text(gameType))
                put("result", Value.Text(result))
                betAmount?.let { put("bet_amount", Value.Number(it)) }
                betCurrency?.let { put("bet_currency", Value.Text(it)) }
                returnAmount?.let { put("return_amount", Value.Number(it)) }
                returnCurrency?.let { put("return_currency", Value.Text(it)) }
                machine?.let { put("machine", Value.Text(it)) }
            },
            span = span,
        )
    }

    private fun buildFields(
        extraFields: Map<String, Value>,
        typedBuilder: MutableMap<String, Value>.() -> Unit,
    ): Map<String, Value> {
        val map = LinkedHashMap<String, Value>(extraFields.size + 8)
        map.typedBuilder()
        for ((k, v) in extraFields) {
            if (!map.containsKey(k)) {
                map[k] = v
            }
        }
        return map
    }
}
