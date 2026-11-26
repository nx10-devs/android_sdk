package com.nx10.asparagus.events

import com.nx10.asparagus.internal.codec.JsonCodec
import com.nx10.asparagus.internal.codec.JsonParseException
import com.nx10.asparagus.internal.codec.JsonValue

enum class CoreEventRole {
    OPENS,
    CLOSES,
    NONE,
}

enum class CoreFieldType {
    TEXT,
    NUMBER,
    FLAG,
}

data class CoreFieldDef(
    val key: String,
    val type: CoreFieldType,
    val expected: Boolean,
    val meaning: String,
    val values: List<String>,
)

data class CoreEventDef(
    val name: String,
    val meaning: String,
    val span: String?,
    val role: CoreEventRole,
    val carriesOutcome: Boolean,
    val fields: List<CoreFieldDef>,
)

data class CoreSpanDef(
    val name: String,
    val meaning: String,
    val sentAs: List<String>,
    val sentAsPrefixes: List<String>,
)

data class CoreStateKeyDef(
    val key: String,
    val defaultAttribute: String,
    val meaning: String,
)

data class SdkStateKeyDef(
    val key: String,
    val type: CoreFieldType,
    val meaning: String,
)

/**
 * Typed catalog parsed from vendored `asparagus_contract/core_events.json`.
 */
data class CoreEventsCatalog(
    val events: List<CoreEventDef>,
    val spans: List<CoreSpanDef>,
    val stateKeys: List<CoreStateKeyDef>,
    val outcomeConverted: String,
    val outcomeUnconverted: String,
    val sdkStateKeys: List<SdkStateKeyDef> = emptyList(),
) {
    private val eventsByName: Map<String, CoreEventDef> = events.associateBy { it.name }
    private val spansByName: Map<String, CoreSpanDef> = spans.associateBy { it.name }
    private val sdkStateKeysByKey: Map<String, SdkStateKeyDef> = sdkStateKeys.associateBy { it.key }

    fun findEvent(name: String): CoreEventDef? = eventsByName[name]

    fun findSpan(name: String): CoreSpanDef? = spansByName[name]

    fun findSdkStateKey(key: String): SdkStateKeyDef? = sdkStateKeysByKey[key]

    /**
     * Resolves a sent span name (e.g. `"ad_rewarded"`, `"level"`) to our canonical [CoreSpanDef]
     * per Rule 16 of `events/README.md`, or `null` if it is a custom game span.
     */
    fun resolveOurSpan(sentSpanName: String): CoreSpanDef? {
        if (sentSpanName.isEmpty()) return null
        return spans.firstOrNull { span ->
            sentSpanName in span.sentAs || span.sentAsPrefixes.any { prefix -> sentSpanName.startsWith(prefix) }
        }
    }

    companion object {
        const val RESOURCE_PATH = "asparagus_contract/core_events.json"
        const val OUTCOME_CONVERTED = "Converted"
        const val OUTCOME_UNCONVERTED = "UnConverted"
        const val SDK_STATE_SCREEN_BRIGHTNESS = "nx10_screen_brightness"
        const val SDK_STATE_SCREEN_LOCKED = "nx10_screen_locked"

        fun loadDefault(classLoader: ClassLoader? = CoreEventsCatalog::class.java.classLoader): CoreEventsCatalog {
            val stream = classLoader?.getResourceAsStream(RESOURCE_PATH)
                ?: error("Missing bundled contract resource: $RESOURCE_PATH")
            val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(json)
        }

        fun fromJson(json: String): CoreEventsCatalog {
            val root = JsonCodec.parse(json) as? JsonValue.JsonObject
                ?: throw JsonParseException("core_events.json root must be a JSON object")

            val events = root.reqArray("events").elements.map { elem ->
                val obj = elem.reqObject("event")
                CoreEventDef(
                    name = obj.reqString("name"),
                    meaning = obj.reqString("meaning"),
                    span = obj.optString("span"),
                    role = CoreEventRole.valueOf(obj.reqString("role")),
                    carriesOutcome = obj.reqBool("carries_outcome"),
                    fields = obj.reqArray("fields").elements.map { fElem ->
                        val fObj = fElem.reqObject("field")
                        CoreFieldDef(
                            key = fObj.reqString("key"),
                            type = CoreFieldType.valueOf(fObj.reqString("type")),
                            expected = fObj.reqBool("expected"),
                            meaning = fObj.reqString("meaning"),
                            values = fObj.reqArray("values").elements.map { it.reqStringValue("field.values[]") },
                        )
                    },
                )
            }

            val spans = root.reqArray("spans").elements.map { elem ->
                val obj = elem.reqObject("span")
                CoreSpanDef(
                    name = obj.reqString("name"),
                    meaning = obj.reqString("meaning"),
                    sentAs = obj.reqArray("sent_as").elements.map { it.reqStringValue("span.sent_as[]") },
                    sentAsPrefixes = obj.reqArray("sent_as_prefixes").elements.map { it.reqStringValue("span.sent_as_prefixes[]") },
                )
            }

            val stateKeys = root.reqArray("state_keys").elements.map { elem ->
                val obj = elem.reqObject("state_key")
                CoreStateKeyDef(
                    key = obj.reqString("key"),
                    defaultAttribute = obj.reqString("default_attribute"),
                    meaning = obj.reqString("meaning"),
                )
            }

            val sdkStateKeys = (root.entries["sdk_state_keys"] as? JsonValue.JsonArray)?.elements?.map { elem ->
                val obj = elem.reqObject("sdk_state_key")
                SdkStateKeyDef(
                    key = obj.reqString("key"),
                    type = CoreFieldType.valueOf(obj.reqString("type")),
                    meaning = obj.reqString("meaning"),
                )
            } ?: emptyList()

            return CoreEventsCatalog(
                events = events,
                spans = spans,
                stateKeys = stateKeys,
                outcomeConverted = root.reqString("outcome_converted"),
                outcomeUnconverted = root.reqString("outcome_unconverted"),
                sdkStateKeys = sdkStateKeys,
            )
        }

        private fun JsonValue.reqObject(label: String): JsonValue.JsonObject =
            this as? JsonValue.JsonObject ?: throw JsonParseException("Expected object for $label")

        private fun JsonValue.reqStringValue(label: String): String =
            (this as? JsonValue.JsonString)?.value ?: throw JsonParseException("Expected string for $label")

        private fun JsonValue.JsonObject.reqArray(key: String): JsonValue.JsonArray =
            entries[key] as? JsonValue.JsonArray ?: throw JsonParseException("Missing array '$key'")

        private fun JsonValue.JsonObject.reqString(key: String): String =
            (entries[key] as? JsonValue.JsonString)?.value ?: throw JsonParseException("Missing string '$key'")

        private fun JsonValue.JsonObject.optString(key: String): String? = when (val v = entries[key]) {
            null, JsonValue.JsonNull -> null
            is JsonValue.JsonString -> v.value
            else -> throw JsonParseException("Expected string or null for '$key'")
        }

        private fun JsonValue.JsonObject.reqBool(key: String): Boolean =
            (entries[key] as? JsonValue.JsonBool)?.value ?: throw JsonParseException("Missing boolean '$key'")
    }
}
