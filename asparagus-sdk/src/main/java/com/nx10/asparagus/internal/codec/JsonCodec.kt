package com.nx10.asparagus.internal.codec

import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.CaptureSource
import com.nx10.asparagus.contract.Carried
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.ClockReading
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.CollectionHistory
import com.nx10.asparagus.contract.ContactBefore
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.DeviceProperties
import com.nx10.asparagus.contract.Diagnostic
import com.nx10.asparagus.contract.FallbackReason
import com.nx10.asparagus.contract.GameEvent
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.GapRecord
import com.nx10.asparagus.contract.GapSignal
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.contract.Hold
import com.nx10.asparagus.contract.HoldStats
import com.nx10.asparagus.contract.LifecycleKind
import com.nx10.asparagus.contract.LifecycleRecord
import com.nx10.asparagus.contract.ModelReference
import com.nx10.asparagus.contract.MotionSource
import com.nx10.asparagus.contract.OpenSpan
import com.nx10.asparagus.contract.OpenSpans
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.Platform
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.SensorBlock
import com.nx10.asparagus.contract.ServerAnchor
import com.nx10.asparagus.contract.SessionStartEnvelope
import com.nx10.asparagus.contract.SessionStartRequest
import com.nx10.asparagus.contract.SessionStartResponse
import com.nx10.asparagus.contract.Settings
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.SpanMarker
import com.nx10.asparagus.contract.State
import com.nx10.asparagus.contract.StateKind
import com.nx10.asparagus.contract.StateRecord
import com.nx10.asparagus.contract.Touch
import com.nx10.asparagus.contract.TouchContact
import com.nx10.asparagus.contract.TouchEnd
import com.nx10.asparagus.contract.TouchHold
import com.nx10.asparagus.contract.TouchMode
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.contract.ValueUnit

class JsonParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

sealed interface JsonValue {
    data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
    }

    data class JsonArray(val elements: List<JsonValue>) : JsonValue
    data class JsonString(val value: String) : JsonValue
    data class JsonNumber(val raw: String) : JsonValue {
        fun toLong(): Long = raw.toLongOrNull()
            ?: throw JsonParseException("Not a 64-bit integer JSON number: $raw")

        fun toInt(): Int = raw.toIntOrNull()
            ?: throw JsonParseException("Not a 32-bit integer JSON number: $raw")

        fun toDouble(): Double = raw.toDoubleOrNull()
            ?: throw JsonParseException("Invalid JSON double: $raw")

        fun toFloat(): Float = raw.toFloatOrNull()
            ?: throw JsonParseException("Invalid JSON float: $raw")
    }

    data class JsonBool(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
}

/**
 * Pure-Kotlin RFC 8259 JSON parser/writer and Protobuf-JSON codec for Asparagus contracts.
 *
 * - Zero dependency on `org.json` (100% JVM unit-testable and zero external bloat).
 * - Preserves exact 64-bit integers as strings or raw number literals (never loses precision via Double).
 * - Serializes `SessionStartRequest` using strict `snake_case` proto field names per `session.proto`.
 * - Deserializes responses and fixtures accepting both `snake_case` and `camelCase` field names,
 *   ignoring unknown fields and unknown enum names per `session.proto` line 30.
 */
object JsonCodec {

    private const val MAX_DEPTH = 64

    fun parse(input: String): JsonValue {
        val parser = Parser(input)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.isAtEnd) {
            throw JsonParseException("Unexpected trailing characters at index ${parser.index}")
        }
        return value
    }

    fun stringify(value: JsonValue): String {
        val sb = StringBuilder()
        writeJsonValue(sb, value)
        return sb.toString()
    }

    // region SessionStartRequest / SessionStartResponse / Settings

    fun encodeSessionStartRequest(request: SessionStartRequest): String {
        val fields = linkedMapOf<String, JsonValue>()
        if (request.apiKey.isNotEmpty()) fields["api_key"] = JsonValue.JsonString(request.apiKey)
        if (request.deviceIdentifier.isNotEmpty()) fields["device_identifier"] = JsonValue.JsonString(request.deviceIdentifier)
        request.sdkVersion?.let { v ->
            val versionObj = linkedMapOf<String, JsonValue>()
            if (v.major != 0) versionObj["major"] = JsonValue.JsonNumber(v.major.toUInt().toString())
            if (v.minor != 0) versionObj["minor"] = JsonValue.JsonNumber(v.minor.toUInt().toString())
            if (v.patch != 0) versionObj["patch"] = JsonValue.JsonNumber(v.patch.toUInt().toString())
            fields["sdk_version"] = JsonValue.JsonObject(versionObj)
        }
        if (request.platform != Platform.PLATFORM_UNSPECIFIED) {
            fields["platform"] = JsonValue.JsonString(request.platform.protoName)
        }
        request.device?.let { d ->
            fields["device"] = encodeDeviceProperties(d)
        }
        if (request.appVersion.isNotEmpty()) fields["app_version"] = JsonValue.JsonString(request.appVersion)
        if (request.buildNumber.isNotEmpty()) fields["build_number"] = JsonValue.JsonString(request.buildNumber)
        if (request.sessionProperties.isNotEmpty()) {
            val props = linkedMapOf<String, JsonValue>()
            for ((k, v) in request.sessionProperties) {
                props[k] = encodeValue(v)
            }
            fields["session_properties"] = JsonValue.JsonObject(props)
        }
        if (request.demo) fields["demo"] = JsonValue.JsonBool(true)
        val history = request.history
        if (history != null && history.hasAnyHistory) {
            fields["history"] = encodeCollectionHistory(history)
        }
        return stringify(JsonValue.JsonObject(fields))
    }

    fun decodeSessionStartRequest(json: String): SessionStartRequest {
        val obj = parse(json).asObject("SessionStartRequest")
        return SessionStartRequest(
            apiKey = obj.stringOrDefault("api_key", "apiKey"),
            deviceIdentifier = obj.stringOrDefault("device_identifier", "deviceIdentifier"),
            sdkVersion = obj.objectOrNull("sdk_version", "sdkVersion")?.let { decodeSdkVersionObj(it) },
            platform = obj.enumOrNull("platform", Platform::fromProtoName, Platform::fromProtoNumber)
                ?: Platform.PLATFORM_UNSPECIFIED,
            device = obj.objectOrNull("device")?.let { decodeDevicePropertiesObj(it) },
            appVersion = obj.stringOrDefault("app_version", "appVersion"),
            buildNumber = obj.stringOrDefault("build_number", "buildNumber"),
            sessionProperties = decodeValueMapObj(obj.objectOrNull("session_properties", "sessionProperties")),
            demo = obj.boolOrDefault("demo"),
            history = obj.objectOrNull("history")?.let { decodeCollectionHistoryObj(it) },
        )
    }

    fun encodeSessionStartResponse(response: SessionStartResponse): String {
        val fields = linkedMapOf<String, JsonValue>()
        if (response.idOrg.isNotEmpty()) fields["id_org"] = JsonValue.JsonString(response.idOrg)
        if (response.idDeployment.isNotEmpty()) fields["id_deployment"] = JsonValue.JsonString(response.idDeployment)
        if (response.idSource.isNotEmpty()) fields["id_source"] = JsonValue.JsonString(response.idSource)
        if (response.idSession.isNotEmpty()) fields["id_session"] = JsonValue.JsonString(response.idSession)
        if (response.token.isNotEmpty()) fields["token"] = JsonValue.JsonString(response.token)
        if (response.tokenExpiresUtcMs != 0L) fields["token_expires_utc_ms"] = JsonValue.JsonString(response.tokenExpiresUtcMs.toString())
        if (response.serverUtcMs != 0L) fields["server_utc_ms"] = JsonValue.JsonString(response.serverUtcMs.toString())
        if (response.batchEndpoint.isNotEmpty()) fields["batch_endpoint"] = JsonValue.JsonString(response.batchEndpoint)
        if (response.accessRequestEndpoint.isNotEmpty()) fields["access_request_endpoint"] = JsonValue.JsonString(response.accessRequestEndpoint)
        if (response.forgetRequestEndpoint.isNotEmpty()) fields["forget_request_endpoint"] = JsonValue.JsonString(response.forgetRequestEndpoint)
        response.settings?.let { fields["settings"] = encodeSettings(it) }
        if (response.models.isNotEmpty()) {
            fields["models"] = JsonValue.JsonArray(response.models.map { encodeModelReference(it) })
        }
        if (response.settingsLabel.isNotEmpty()) {
            fields["settings_label"] = JsonValue.JsonString(response.settingsLabel)
        }
        return stringify(JsonValue.JsonObject(fields))
    }

    fun decodeSessionStartEnvelope(json: String): SessionStartEnvelope {
        val obj = parse(json).asObject("SessionStartEnvelope")
        val status = obj.stringOrDefault("status")
        val data = obj.objectOrNull("data")?.let { decodeSessionStartResponseObj(it) }
        val errors = obj.arrayOrNull("errors")?.elements?.map { elem ->
            when (elem) {
                is JsonValue.JsonString -> elem.value
                else -> stringify(elem)
            }
        } ?: emptyList()
        return SessionStartEnvelope(status = status, data = data, errors = errors)
    }

    fun decodeSessionStartResponse(json: String): SessionStartResponse {
        val obj = parse(json).asObject("SessionStartResponse")
        return decodeSessionStartResponseObj(obj)
    }

    fun decodeSettings(json: String): Settings {
        val obj = parse(json).asObject("Settings")
        return decodeSettingsObj(obj)
    }

    private fun encodeCollectionHistory(h: CollectionHistory): JsonValue.JsonObject {
        val fields = linkedMapOf<String, JsonValue>()
        if (h.sessionsBefore > 0) {
            fields["sessions_before"] = JsonValue.JsonNumber(h.sessionsBefore.toUInt().toString())
        }
        if (h.collectedSecondsBefore > 0L) {
            fields["collected_seconds_before"] = JsonValue.JsonString(h.collectedSecondsBefore.toULong().toString())
        }
        if (h.longestSessionSecondsBefore > 0) {
            fields["longest_session_seconds_before"] = JsonValue.JsonNumber(h.longestSessionSecondsBefore.toUInt().toString())
        }
        return JsonValue.JsonObject(fields)
    }

    private fun decodeCollectionHistoryObj(obj: JsonValue.JsonObject): CollectionHistory = CollectionHistory(
        sessionsBefore = obj.intOrDefault("sessions_before", "sessionsBefore"),
        collectedSecondsBefore = obj.longOrDefault("collected_seconds_before", "collectedSecondsBefore"),
        longestSessionSecondsBefore = obj.intOrDefault("longest_session_seconds_before", "longestSessionSecondsBefore"),
    )

    private fun decodeDevicePropertiesObj(obj: JsonValue.JsonObject): DeviceProperties = DeviceProperties(
        maker = obj.stringOrDefault("maker"),
        model = obj.stringOrDefault("model"),
        osVersion = obj.stringOrDefault("os_version", "osVersion"),
        deviceType = obj.stringOrDefault("device_type", "deviceType"),
        screenWidthPx = obj.intOrDefault("screen_width_px", "screenWidthPx"),
        screenHeightPx = obj.intOrDefault("screen_height_px", "screenHeightPx"),
        screenDensityDpi = obj.floatOrDefault("screen_density_dpi", "screenDensityDpi"),
        hasGyroscope = obj.boolOrDefault("has_gyroscope", "hasGyroscope"),
        timezone = obj.stringOrDefault("timezone"),
        locale = obj.stringOrDefault("locale"),
    )

    private fun encodeDeviceProperties(d: DeviceProperties): JsonValue.JsonObject {
        val fields = linkedMapOf<String, JsonValue>()
        if (d.maker.isNotEmpty()) fields["maker"] = JsonValue.JsonString(d.maker)
        if (d.model.isNotEmpty()) fields["model"] = JsonValue.JsonString(d.model)
        if (d.osVersion.isNotEmpty()) fields["os_version"] = JsonValue.JsonString(d.osVersion)
        if (d.deviceType.isNotEmpty()) fields["device_type"] = JsonValue.JsonString(d.deviceType)
        if (d.screenWidthPx != 0) fields["screen_width_px"] = JsonValue.JsonNumber(d.screenWidthPx.toUInt().toString())
        if (d.screenHeightPx != 0) fields["screen_height_px"] = JsonValue.JsonNumber(d.screenHeightPx.toUInt().toString())
        if (d.screenDensityDpi != 0f) fields["screen_density_dpi"] = jsonFloat(d.screenDensityDpi)
        if (d.hasGyroscope) fields["has_gyroscope"] = JsonValue.JsonBool(true)
        if (d.timezone.isNotEmpty()) fields["timezone"] = JsonValue.JsonString(d.timezone)
        if (d.locale.isNotEmpty()) fields["locale"] = JsonValue.JsonString(d.locale)
        return JsonValue.JsonObject(fields)
    }

    private fun encodeSettings(s: Settings): JsonValue.JsonObject {
        val fields = linkedMapOf<String, JsonValue>()
        if (s.version.isNotEmpty()) fields["version"] = JsonValue.JsonString(s.version)
        s.accelerometerHz?.let { fields["accelerometer_hz"] = JsonValue.JsonNumber(it.toString()) }
        s.gyroscopeHz?.let { fields["gyroscope_hz"] = JsonValue.JsonNumber(it.toString()) }
        s.gravityHz?.let { fields["gravity_hz"] = JsonValue.JsonNumber(it.toString()) }
        s.touchHz?.let { fields["touch_hz"] = JsonValue.JsonNumber(it.toString()) }
        s.motionSource?.let { fields["motion_source"] = JsonValue.JsonString(it.protoName) }
        s.nativeBufferS?.let { fields["native_buffer_s"] = JsonValue.JsonNumber(it.toString()) }
        s.nativeStartLimitMs?.let { fields["native_start_limit_ms"] = JsonValue.JsonNumber(it.toString()) }
        s.nativeStallLimitMs?.let { fields["native_stall_limit_ms"] = JsonValue.JsonNumber(it.toString()) }
        s.accelerometerHoldTolerance?.let { fields["accelerometer_hold_tolerance"] = JsonValue.JsonNumber(it.toString()) }
        s.gyroscopeHoldTolerance?.let { fields["gyroscope_hold_tolerance"] = JsonValue.JsonNumber(it.toString()) }
        s.gravityHoldTolerance?.let { fields["gravity_hold_tolerance"] = JsonValue.JsonNumber(it.toString()) }
        s.touchHoldTolerance?.let { fields["touch_hold_tolerance"] = JsonValue.JsonNumber(it.toString()) }
        s.holdRunLength?.let { fields["hold_run_length"] = JsonValue.JsonNumber(it.toString()) }
        s.gapThresholdMs?.let { fields["gap_threshold_ms"] = JsonValue.JsonNumber(it.toString()) }
        s.gapThresholdPeriods?.let { fields["gap_threshold_periods"] = JsonValue.JsonNumber(it.toString()) }
        s.batchLengthS?.let { fields["batch_length_s"] = JsonValue.JsonNumber(it.toString()) }
        s.compress?.let { fields["compress"] = JsonValue.JsonBool(it) }
        s.queueMaxBytes?.let { fields["queue_max_bytes"] = JsonValue.JsonNumber(it.toString()) }
        s.queueMaxAgeS?.let { fields["queue_max_age_s"] = JsonValue.JsonNumber(it.toString()) }
        s.retryFirstWaitMs?.let { fields["retry_first_wait_ms"] = JsonValue.JsonNumber(it.toString()) }
        s.retryLongestWaitMs?.let { fields["retry_longest_wait_ms"] = JsonValue.JsonNumber(it.toString()) }
        s.pauseTouchOnKeyboard?.let { fields["pause_touch_on_keyboard"] = JsonValue.JsonBool(it) }
        s.tokenLifeS?.let { fields["token_life_s"] = JsonValue.JsonNumber(it.toString()) }
        s.newSessionAfterS?.let { fields["new_session_after_s"] = JsonValue.JsonNumber(it.toString()) }
        s.magnetometerHz?.let { fields["magnetometer_hz"] = JsonValue.JsonNumber(it.toString()) }
        s.magnetometerHoldTolerance?.let { fields["magnetometer_hold_tolerance"] = JsonValue.JsonNumber(it.toString()) }
        return JsonValue.JsonObject(fields)
    }

    private fun encodeModelReference(m: ModelReference): JsonValue.JsonObject {
        val fields = linkedMapOf<String, JsonValue>()
        if (m.purpose.isNotEmpty()) fields["purpose"] = JsonValue.JsonString(m.purpose)
        if (m.name.isNotEmpty()) fields["name"] = JsonValue.JsonString(m.name)
        if (m.version.isNotEmpty()) fields["version"] = JsonValue.JsonString(m.version)
        if (m.downloadUrl.isNotEmpty()) fields["download_url"] = JsonValue.JsonString(m.downloadUrl)
        if (m.arguments.isNotEmpty()) {
            fields["arguments"] = JsonValue.JsonArray(m.arguments.map { JsonValue.JsonString(it) })
        }
        return JsonValue.JsonObject(fields)
    }

    private fun encodeValue(v: Value): JsonValue.JsonObject = when (v) {
        is Value.Text -> JsonValue.JsonObject(mapOf("text" to JsonValue.JsonString(v.value)))
        is Value.Number -> JsonValue.JsonObject(mapOf("number" to jsonDouble(v.value)))
        is Value.Flag -> JsonValue.JsonObject(mapOf("flag" to JsonValue.JsonBool(v.value)))
        Value.Empty -> JsonValue.JsonObject(emptyMap())
    }

    private fun decodeSessionStartResponseObj(obj: JsonValue.JsonObject): SessionStartResponse {
        val models = obj.arrayOrNull("models")?.elements?.mapNotNull { elem ->
            (elem as? JsonValue.JsonObject)?.let { decodeModelReferenceObj(it) }
        } ?: emptyList()

        return SessionStartResponse(
            idOrg = obj.stringOrDefault("id_org", "idOrg"),
            idDeployment = obj.stringOrDefault("id_deployment", "idDeployment"),
            idSource = obj.stringOrDefault("id_source", "idSource"),
            idSession = obj.stringOrDefault("id_session", "idSession"),
            token = obj.stringOrDefault("token"),
            tokenExpiresUtcMs = obj.longOrDefault("token_expires_utc_ms", "tokenExpiresUtcMs"),
            serverUtcMs = obj.longOrDefault("server_utc_ms", "serverUtcMs"),
            batchEndpoint = obj.stringOrDefault("batch_endpoint", "batchEndpoint"),
            accessRequestEndpoint = obj.stringOrDefault("access_request_endpoint", "accessRequestEndpoint"),
            forgetRequestEndpoint = obj.stringOrDefault("forget_request_endpoint", "forgetRequestEndpoint"),
            settings = obj.objectOrNull("settings")?.let { decodeSettingsObj(it) },
            models = models,
            settingsLabel = obj.stringOrDefault("settings_label", "settingsLabel"),
        )
    }

    private fun decodeModelReferenceObj(obj: JsonValue.JsonObject): ModelReference {
        val arguments = obj.arrayOrNull("arguments")?.elements?.mapNotNull { elem ->
            (elem as? JsonValue.JsonString)?.value
        } ?: emptyList()
        return ModelReference(
            purpose = obj.stringOrDefault("purpose"),
            name = obj.stringOrDefault("name"),
            version = obj.stringOrDefault("version"),
            downloadUrl = obj.stringOrDefault("download_url", "downloadUrl"),
            arguments = arguments,
        )
    }

    private fun decodeSettingsObj(obj: JsonValue.JsonObject): Settings {
        return Settings(
            version = obj.stringOrDefault("version"),
            accelerometerHz = obj.intOrNull("accelerometer_hz", "accelerometerHz"),
            gyroscopeHz = obj.intOrNull("gyroscope_hz", "gyroscopeHz"),
            gravityHz = obj.intOrNull("gravity_hz", "gravityHz"),
            touchHz = obj.intOrNull("touch_hz", "touchHz"),
            motionSource = obj.enumOrNull("motion_source", "motionSource", MotionSource::fromProtoName, MotionSource::fromProtoNumber),
            nativeBufferS = obj.intOrNull("native_buffer_s", "nativeBufferS"),
            nativeStartLimitMs = obj.intOrNull("native_start_limit_ms", "nativeStartLimitMs"),
            nativeStallLimitMs = obj.intOrNull("native_stall_limit_ms", "nativeStallLimitMs"),
            accelerometerHoldTolerance = obj.intOrNull("accelerometer_hold_tolerance", "accelerometerHoldTolerance"),
            gyroscopeHoldTolerance = obj.intOrNull("gyroscope_hold_tolerance", "gyroscopeHoldTolerance"),
            gravityHoldTolerance = obj.intOrNull("gravity_hold_tolerance", "gravityHoldTolerance"),
            touchHoldTolerance = obj.intOrNull("touch_hold_tolerance", "touchHoldTolerance"),
            holdRunLength = obj.intOrNull("hold_run_length", "holdRunLength"),
            gapThresholdMs = obj.intOrNull("gap_threshold_ms", "gapThresholdMs"),
            gapThresholdPeriods = obj.intOrNull("gap_threshold_periods", "gapThresholdPeriods"),
            batchLengthS = obj.intOrNull("batch_length_s", "batchLengthS"),
            compress = obj.boolOrNull("compress"),
            queueMaxBytes = obj.intOrNull("queue_max_bytes", "queueMaxBytes"),
            queueMaxAgeS = obj.intOrNull("queue_max_age_s", "queueMaxAgeS"),
            retryFirstWaitMs = obj.intOrNull("retry_first_wait_ms", "retryFirstWaitMs"),
            retryLongestWaitMs = obj.intOrNull("retry_longest_wait_ms", "retryLongestWaitMs"),
            pauseTouchOnKeyboard = obj.boolOrNull("pause_touch_on_keyboard", "pauseTouchOnKeyboard"),
            tokenLifeS = obj.intOrNull("token_life_s", "tokenLifeS"),
            newSessionAfterS = obj.intOrNull("new_session_after_s", "newSessionAfterS"),
            magnetometerHz = obj.intOrNull("magnetometer_hz", "magnetometerHz"),
            magnetometerHoldTolerance = obj.intOrNull("magnetometer_hold_tolerance", "magnetometerHoldTolerance"),
        )
    }

    // endregion

    // region Batch Protobuf-JSON Decoder (supports camelCase & snake_case fixtures)

    fun decodeBatchFromJson(json: String): Batch {
        val obj = parse(json).asObject("Batch")
        return Batch(
            header = obj.objectOrNull("header")?.let { decodeHeaderObj(it) },
            sensorBlocks = obj.arrayOrNull("sensor_blocks", "sensorBlocks")?.elements?.map {
                decodeSensorBlockObj(it.asObject("SensorBlock"))
            } ?: emptyList(),
            touch = obj.objectOrNull("touch")?.let { decodeTouchObj(it) },
            events = obj.arrayOrNull("events")?.elements?.map {
                decodeGameEventObj(it.asObject("GameEvent"))
            } ?: emptyList(),
            stateRecords = obj.arrayOrNull("state_records", "stateRecords")?.elements?.map {
                decodeStateRecordObj(it.asObject("StateRecord"))
            } ?: emptyList(),
            lifecycle = obj.arrayOrNull("lifecycle")?.elements?.map {
                decodeLifecycleObj(it.asObject("LifecycleRecord"))
            } ?: emptyList(),
            gaps = obj.arrayOrNull("gaps")?.elements?.map {
                decodeGapObj(it.asObject("GapRecord"))
            } ?: emptyList(),
            screenChanges = obj.arrayOrNull("screen_changes", "screenChanges")?.elements?.map {
                decodeScreenRecordObj(it.asObject("ScreenRecord"))
            } ?: emptyList(),
            diagnostics = obj.arrayOrNull("diagnostics")?.elements?.map {
                decodeDiagnosticObj(it.asObject("Diagnostic"))
            } ?: emptyList(),
            carried = obj.objectOrNull("carried")?.let { decodeCarriedObj(it) },
        )
    }

    private fun decodeHeaderObj(obj: JsonValue.JsonObject): Header = Header(
        idOrg = obj.stringOrDefault("id_org", "idOrg"),
        idDeployment = obj.stringOrDefault("id_deployment", "idDeployment"),
        idSource = obj.stringOrDefault("id_source", "idSource"),
        idSession = obj.stringOrDefault("id_session", "idSession"),
        sequence = obj.longOrDefault("sequence"),
        schemaVersion = obj.intOrDefault("schema_version", "schemaVersion"),
        sdkVersion = obj.objectOrNull("sdk_version", "sdkVersion")?.let { decodeSdkVersionObj(it) },
        platform = obj.enumOrNull("platform", Platform::fromProtoName, Platform::fromProtoNumber)
            ?: Platform.PLATFORM_UNSPECIFIED,
        osVersion = obj.stringOrDefault("os_version", "osVersion"),
        deviceModel = obj.stringOrDefault("device_model", "deviceModel"),
        settingsVersion = obj.stringOrDefault("settings_version", "settingsVersion"),
        windowStartUs = obj.longOrDefault("window_start_us", "windowStartUs"),
        windowLengthS = obj.intOrDefault("window_length_s", "windowLengthS"),
        closedUs = obj.longOrDefault("closed_us", "closedUs"),
        closeReason = obj.enumOrNull("close_reason", "closeReason", CloseReason::fromProtoName, CloseReason::fromProtoNumber)
            ?: CloseReason.CLOSE_REASON_UNSPECIFIED,
        clocks = obj.objectOrNull("clocks")?.let { decodeClockReadingObj(it) },
        serverAnchor = obj.objectOrNull("server_anchor", "serverAnchor")?.let { decodeServerAnchorObj(it) },
        screen = obj.objectOrNull("screen")?.let { decodeScreenRecordObj(it) },
        hasGyroscope = obj.boolOrDefault("has_gyroscope", "hasGyroscope"),
        batchesDropped = obj.intOrDefault("batches_dropped", "batchesDropped"),
        utcOffsetMinutes = obj.intOrNull("utc_offset_minutes", "utcOffsetMinutes"),
    )

    private fun decodeSdkVersionObj(obj: JsonValue.JsonObject): SdkVersion = SdkVersion(
        major = obj.intOrDefault("major"),
        minor = obj.intOrDefault("minor"),
        patch = obj.intOrDefault("patch"),
    )

    private fun decodeClockReadingObj(obj: JsonValue.JsonObject): ClockReading = ClockReading(
        sessionUs = obj.longOrDefault("session_us", "sessionUs"),
        unityUs = obj.longOrDefault("unity_us", "unityUs"),
        sensorUs = obj.longOrNull("sensor_us", "sensorUs"),
        wallUtcMs = obj.longOrDefault("wall_utc_ms", "wallUtcMs"),
    )

    private fun decodeServerAnchorObj(obj: JsonValue.JsonObject): ServerAnchor = ServerAnchor(
        serverUtcMs = obj.longOrDefault("server_utc_ms", "serverUtcMs"),
        atReceipt = obj.objectOrNull("at_receipt", "atReceipt")?.let { decodeClockReadingObj(it) },
        roundTripMs = obj.intOrDefault("round_trip_ms", "roundTripMs"),
    )

    private fun decodeSensorBlockObj(obj: JsonValue.JsonObject): SensorBlock = SensorBlock(
        sensor = obj.enumOrNull("sensor", Sensor::fromProtoName, Sensor::fromProtoNumber)
            ?: Sensor.SENSOR_UNSPECIFIED,
        source = obj.enumOrNull("source", CaptureSource::fromProtoName, CaptureSource::fromProtoNumber)
            ?: CaptureSource.CAPTURE_SOURCE_UNSPECIFIED,
        fallbackReason = obj.enumOrNull("fallback_reason", "fallbackReason", FallbackReason::fromProtoName, FallbackReason::fromProtoNumber)
            ?: FallbackReason.FALLBACK_REASON_UNSPECIFIED,
        clock = obj.enumOrNull("clock", Clock::fromProtoName, Clock::fromProtoNumber)
            ?: Clock.CLOCK_UNSPECIFIED,
        unit = obj.enumOrNull("unit", ValueUnit::fromProtoName, ValueUnit::fromProtoNumber)
            ?: ValueUnit.VALUE_UNIT_UNSPECIFIED,
        scale = obj.doubleOrDefault("scale"),
        requestedHz = obj.floatOrDefault("requested_hz", "requestedHz"),
        sampleCount = obj.intOrDefault("sample_count", "sampleCount"),
        longestGapUs = obj.intOrDefault("longest_gap_us", "longestGapUs"),
        firstTimeUs = obj.longOrDefault("first_time_us", "firstTimeUs"),
        timeStepsUs = obj.intListOrEmpty("time_steps_us", "timeStepsUs"),
        x = obj.intListOrEmpty("x"),
        y = obj.intListOrEmpty("y"),
        z = obj.intListOrEmpty("z"),
        holds = obj.arrayOrNull("holds")?.elements?.map { decodeHoldObj(it.asObject("Hold")) } ?: emptyList(),
    )

    private fun decodeHoldObj(obj: JsonValue.JsonObject): Hold = Hold(
        startTimeUs = obj.longOrDefault("start_time_us", "startTimeUs"),
        endTimeUs = obj.longOrDefault("end_time_us", "endTimeUs"),
        sampleCount = obj.intOrDefault("sample_count", "sampleCount"),
        x = obj.intOrDefault("x"),
        y = obj.intOrDefault("y"),
        z = obj.intOrDefault("z"),
        stats = obj.arrayOrNull("stats")?.elements?.map { decodeHoldStatsObj(it.asObject("HoldStats")) } ?: emptyList(),
    )

    private fun decodeHoldStatsObj(obj: JsonValue.JsonObject): HoldStats = HoldStats(
        sum = obj.longOrDefault("sum"),
        sumOfSquares = obj.doubleOrDefault("sum_of_squares", "sumOfSquares"),
        minimum = obj.intOrDefault("minimum"),
        maximum = obj.intOrDefault("maximum"),
    )

    private fun decodeTouchObj(obj: JsonValue.JsonObject): Touch = Touch(
        mode = obj.enumOrNull("mode", TouchMode::fromProtoName, TouchMode::fromProtoNumber)
            ?: TouchMode.TOUCH_MODE_UNSPECIFIED,
        requestedHz = obj.floatOrDefault("requested_hz", "requestedHz"),
        positionScale = obj.doubleOrDefault("position_scale", "positionScale"),
        sizeScale = obj.doubleOrDefault("size_scale", "sizeScale"),
        pressureScale = obj.doubleOrDefault("pressure_scale", "pressureScale"),
        contacts = obj.arrayOrNull("contacts")?.elements?.map { decodeTouchContactObj(it.asObject("TouchContact")) } ?: emptyList(),
    )

    private fun decodeTouchContactObj(obj: JsonValue.JsonObject): TouchContact = TouchContact(
        fingerId = obj.intOrDefault("finger_id", "fingerId"),
        continued = obj.boolOrDefault("continued"),
        end = obj.enumOrNull("end", TouchEnd::fromProtoName, TouchEnd::fromProtoNumber)
            ?: TouchEnd.TOUCH_END_UNSPECIFIED,
        firstTimeUs = obj.longOrDefault("first_time_us", "firstTimeUs"),
        timeStepsUs = obj.intListOrEmpty("time_steps_us", "timeStepsUs"),
        x = obj.intListOrEmpty("x"),
        y = obj.intListOrEmpty("y"),
        sizeMajor = obj.intListOrEmpty("size_major", "sizeMajor"),
        sizeMinor = obj.intListOrEmpty("size_minor", "sizeMinor"),
        pressure = obj.intListOrEmpty("pressure"),
        holds = obj.arrayOrNull("holds")?.elements?.map { decodeTouchHoldObj(it.asObject("TouchHold")) } ?: emptyList(),
        eventsSeen = obj.intOrDefault("events_seen", "eventsSeen"),
        before = obj.objectOrNull("before")?.let { decodeContactBeforeObj(it) },
    )

    private fun decodeContactBeforeObj(obj: JsonValue.JsonObject): ContactBefore = ContactBefore(
        downTimeUs = obj.longOrDefault("down_time_us", "downTimeUs"),
        minX = obj.intOrDefault("min_x", "minX"),
        maxX = obj.intOrDefault("max_x", "maxX"),
        minY = obj.intOrDefault("min_y", "minY"),
        maxY = obj.intOrDefault("max_y", "maxY"),
    )

    private fun decodeTouchHoldObj(obj: JsonValue.JsonObject): TouchHold = TouchHold(
        startTimeUs = obj.longOrDefault("start_time_us", "startTimeUs"),
        endTimeUs = obj.longOrDefault("end_time_us", "endTimeUs"),
        sampleCount = obj.intOrDefault("sample_count", "sampleCount"),
        x = obj.intOrDefault("x"),
        y = obj.intOrDefault("y"),
        sizeMajor = obj.intOrDefault("size_major", "sizeMajor"),
        sizeMinor = obj.intOrDefault("size_minor", "sizeMinor"),
        pressure = obj.intOrDefault("pressure"),
        stats = obj.arrayOrNull("stats")?.elements?.map { decodeHoldStatsObj(it.asObject("HoldStats")) } ?: emptyList(),
    )

    private fun decodeScreenRecordObj(obj: JsonValue.JsonObject): ScreenRecord = ScreenRecord(
        timeUs = obj.longOrDefault("time_us", "timeUs"),
        widthPx = obj.intOrDefault("width_px", "widthPx"),
        heightPx = obj.intOrDefault("height_px", "heightPx"),
        densityDpi = obj.floatOrDefault("density_dpi", "densityDpi"),
        orientation = obj.enumOrNull("orientation", Orientation::fromProtoName, Orientation::fromProtoNumber)
            ?: Orientation.ORIENTATION_UNSPECIFIED,
        origin = obj.enumOrNull("origin", CoordinateOrigin::fromProtoName, CoordinateOrigin::fromProtoNumber)
            ?: CoordinateOrigin.COORDINATE_ORIGIN_UNSPECIFIED,
    )

    private fun decodeValueObj(obj: JsonValue.JsonObject): Value {
        obj["text"]?.let { if (it is JsonValue.JsonString) return Value.Text(it.value) }
        obj["number"]?.let {
            if (it is JsonValue.JsonNumber) return Value.Number(it.toDouble())
            if (it is JsonValue.JsonString) return Value.Number(it.value.toDouble())
        }
        obj["flag"]?.let { if (it is JsonValue.JsonBool) return Value.Flag(it.value) }
        return Value.Empty
    }

    private fun decodeValueMapObj(obj: JsonValue.JsonObject?): Map<String, Value> {
        if (obj == null) return emptyMap()
        val result = linkedMapOf<String, Value>()
        for ((k, v) in obj.entries) {
            result[k] = decodeValueObj(v.asObject("Value"))
        }
        return result
    }

    private fun decodeGameEventObj(obj: JsonValue.JsonObject): GameEvent = GameEvent(
        timeUs = obj.longOrDefault("time_us", "timeUs"),
        name = obj.stringOrDefault("name"),
        fields = decodeValueMapObj(obj.objectOrNull("fields")),
        outcome = obj.stringOrNull("outcome"),
        spanName = obj.stringOrDefault("span_name", "spanName"),
        spanMarker = obj.enumOrNull("span_marker", "spanMarker", SpanMarker::fromProtoName, SpanMarker::fromProtoNumber)
            ?: SpanMarker.SPAN_MARKER_UNSPECIFIED,
        spanNumber = obj.intOrDefault("span_number", "spanNumber"),
        order = obj.intOrDefault("order"),
    )

    private fun decodeStateRecordObj(obj: JsonValue.JsonObject): StateRecord = StateRecord(
        timeUs = obj.longOrDefault("time_us", "timeUs"),
        kind = obj.enumOrNull("kind", StateKind::fromProtoName, StateKind::fromProtoNumber)
            ?: StateKind.STATE_KIND_UNSPECIFIED,
        values = decodeValueMapObj(obj.objectOrNull("values")),
        removedKeys = obj.arrayOrNull("removed_keys", "removedKeys")?.elements?.mapNotNull {
            (it as? JsonValue.JsonString)?.value
        } ?: emptyList(),
        order = obj.intOrDefault("order"),
        version = obj.intOrDefault("version"),
    )

    private fun decodeCarriedObj(obj: JsonValue.JsonObject): Carried = Carried(
        state = obj.objectOrNull("state")?.let { decodeStateObj(it) },
        openSpans = obj.objectOrNull("open_spans", "openSpans")?.let { decodeOpenSpansObj(it) },
        lastEventTimeUs = obj.longOrNull("last_event_time_us", "lastEventTimeUs"),
    )

    private fun decodeStateObj(obj: JsonValue.JsonObject): State = State(
        values = decodeValueMapObj(obj.objectOrNull("values")),
        version = obj.intOrDefault("version"),
    )

    private fun decodeOpenSpansObj(obj: JsonValue.JsonObject): OpenSpans = OpenSpans(
        spans = obj.arrayOrNull("spans")?.elements?.map { decodeOpenSpanObj(it.asObject("OpenSpan")) } ?: emptyList(),
    )

    private fun decodeOpenSpanObj(obj: JsonValue.JsonObject): OpenSpan = OpenSpan(
        name = obj.stringOrDefault("name"),
        number = obj.intOrDefault("number"),
        openedTimeUs = obj.longOrDefault("opened_time_us", "openedTimeUs"),
    )

    private fun decodeLifecycleObj(obj: JsonValue.JsonObject): LifecycleRecord = LifecycleRecord(
        timeUs = obj.longOrDefault("time_us", "timeUs"),
        kind = obj.enumOrNull("kind", LifecycleKind::fromProtoName, LifecycleKind::fromProtoNumber)
            ?: LifecycleKind.LIFECYCLE_KIND_UNSPECIFIED,
        clockStepS = obj.longOrDefault("clock_step_s", "clockStepS"),
    )

    private fun decodeGapObj(obj: JsonValue.JsonObject): GapRecord = GapRecord(
        signal = obj.enumOrNull("signal", GapSignal::fromProtoName, GapSignal::fromProtoNumber)
            ?: GapSignal.GAP_SIGNAL_UNSPECIFIED,
        clock = obj.enumOrNull("clock", Clock::fromProtoName, Clock::fromProtoNumber)
            ?: Clock.CLOCK_UNSPECIFIED,
        startTimeUs = obj.longOrDefault("start_time_us", "startTimeUs"),
        endTimeUs = obj.longOrDefault("end_time_us", "endTimeUs"),
        cause = obj.enumOrNull("cause", GapCause::fromProtoName, GapCause::fromProtoNumber)
            ?: GapCause.GAP_CAUSE_UNSPECIFIED,
        batchesDropped = obj.intOrDefault("batches_dropped", "batchesDropped"),
        firstDroppedSequence = obj.longOrDefault("first_dropped_sequence", "firstDroppedSequence"),
    )

    private fun decodeDiagnosticObj(obj: JsonValue.JsonObject): Diagnostic = Diagnostic(
        timeUs = obj.longOrDefault("time_us", "timeUs"),
        severity = obj.enumOrNull("severity", Severity::fromProtoName, Severity::fromProtoNumber)
            ?: Severity.SEVERITY_UNSPECIFIED,
        code = obj.stringOrDefault("code"),
        detail = obj.stringOrDefault("detail"),
        count = obj.intOrDefault("count"),
    )

    // endregion

    // region JSON Object Helpers

    private fun jsonDouble(v: Double): JsonValue =
        if (v.isFinite()) JsonValue.JsonNumber(v.toString()) else JsonValue.JsonString(v.toString())

    private fun jsonFloat(v: Float): JsonValue =
        if (v.isFinite()) JsonValue.JsonNumber(v.toString()) else JsonValue.JsonString(v.toString())

    private fun JsonValue.asObject(name: String): JsonValue.JsonObject =
        this as? JsonValue.JsonObject ?: throw JsonParseException("Expected JSON object for $name")

    private fun JsonValue.JsonObject.firstNonNull(vararg keys: String): JsonValue? {
        for (k in keys) {
            val v = entries[k]
            if (v != null && v !is JsonValue.JsonNull) return v
        }
        return null
    }

    private fun JsonValue.JsonObject.objectOrNull(vararg keys: String): JsonValue.JsonObject? =
        firstNonNull(*keys) as? JsonValue.JsonObject

    private fun JsonValue.JsonObject.arrayOrNull(vararg keys: String): JsonValue.JsonArray? =
        firstNonNull(*keys) as? JsonValue.JsonArray

    private fun JsonValue.JsonObject.stringOrNull(vararg keys: String): String? =
        (firstNonNull(*keys) as? JsonValue.JsonString)?.value

    private fun JsonValue.JsonObject.stringOrDefault(vararg keys: String, default: String = ""): String =
        stringOrNull(*keys) ?: default

    private fun JsonValue.JsonObject.boolOrNull(vararg keys: String): Boolean? =
        (firstNonNull(*keys) as? JsonValue.JsonBool)?.value

    private fun JsonValue.JsonObject.boolOrDefault(vararg keys: String, default: Boolean = false): Boolean =
        boolOrNull(*keys) ?: default

    private fun JsonValue.JsonObject.intOrNull(vararg keys: String): Int? = when (val v = firstNonNull(*keys)) {
        is JsonValue.JsonNumber -> v.raw.toIntOrNull()
        is JsonValue.JsonString -> v.value.toIntOrNull()
        else -> null
    }

    private fun JsonValue.JsonObject.intOrDefault(vararg keys: String, default: Int = 0): Int =
        intOrNull(*keys) ?: default

    private fun JsonValue.JsonObject.longOrNull(vararg keys: String): Long? = when (val v = firstNonNull(*keys)) {
        is JsonValue.JsonNumber -> v.raw.toLongOrNull()
        is JsonValue.JsonString -> v.value.toLongOrNull()
        else -> null
    }

    private fun JsonValue.JsonObject.longOrDefault(vararg keys: String, default: Long = 0L): Long =
        longOrNull(*keys) ?: default

    private fun JsonValue.JsonObject.doubleOrDefault(vararg keys: String, default: Double = 0.0): Double =
        when (val v = firstNonNull(*keys)) {
            is JsonValue.JsonNumber -> v.toDouble()
            is JsonValue.JsonString -> v.value.toDoubleOrNull() ?: default
            else -> default
        }

    private fun JsonValue.JsonObject.floatOrDefault(vararg keys: String, default: Float = 0f): Float =
        when (val v = firstNonNull(*keys)) {
            is JsonValue.JsonNumber -> v.toFloat()
            is JsonValue.JsonString -> v.value.toFloatOrNull() ?: default
            else -> default
        }

    private fun JsonValue.JsonObject.intListOrEmpty(vararg keys: String): List<Int> {
        val arr = arrayOrNull(*keys) ?: return emptyList()
        return arr.elements.map { elem ->
            when (elem) {
                is JsonValue.JsonNumber -> elem.toInt()
                is JsonValue.JsonString -> elem.value.toInt()
                else -> throw JsonParseException("Expected integer element in array")
            }
        }
    }

    private fun <T> JsonValue.JsonObject.enumOrNull(
        key: String,
        fromName: (String) -> T?,
        fromNumber: (Int) -> T,
    ): T? = parseEnumValue(firstNonNull(key), fromName, fromNumber)

    private fun <T> JsonValue.JsonObject.enumOrNull(
        key1: String,
        key2: String,
        fromName: (String) -> T?,
        fromNumber: (Int) -> T,
    ): T? = parseEnumValue(firstNonNull(key1, key2), fromName, fromNumber)

    private fun <T> parseEnumValue(
        v: JsonValue?,
        fromName: (String) -> T?,
        fromNumber: (Int) -> T,
    ): T? = when (v) {
        is JsonValue.JsonString -> fromName(v.value) // unknown enum name returns null (ignored per session.proto line 30)
        is JsonValue.JsonNumber -> v.raw.toIntOrNull()?.let(fromNumber)
        else -> null
    }

    // endregion

    // region RFC 8259 Writer & Recursive-Descent Parser

    private fun writeJsonValue(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.JsonNull -> sb.append("null")
            is JsonValue.JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.JsonNumber -> sb.append(value.raw)
            is JsonValue.JsonString -> writeEscapedString(sb, value.value)
            is JsonValue.JsonArray -> {
                sb.append('[')
                value.elements.forEachIndexed { i, elem ->
                    if (i > 0) sb.append(',')
                    writeJsonValue(sb, elem)
                }
                sb.append(']')
            }
            is JsonValue.JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    writeEscapedString(sb, k)
                    sb.append(':')
                    writeJsonValue(sb, v)
                }
                sb.append('}')
            }
        }
    }

    private fun writeEscapedString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        var index: Int = 0
            private set

        val isAtEnd: Boolean
            get() = index >= src.length

        fun skipWhitespace() {
            while (index < src.length) {
                when (src[index]) {
                    ' ', '\t', '\r', '\n' -> index++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) {
                throw JsonParseException("JSON nesting depth exceeded $MAX_DEPTH")
            }
            skipWhitespace()
            if (isAtEnd) throw JsonParseException("Unexpected end of JSON input")
            return when (val c = src[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.JsonString(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonValue.JsonBool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.JsonBool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.JsonNull
                }
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("Unexpected character '$c' at index $index")
            }
        }

        private fun parseObject(depth: Int): JsonValue.JsonObject {
            index++ // '{'
            skipWhitespace()
            val map = linkedMapOf<String, JsonValue>()
            if (!isAtEnd && src[index] == '}') {
                index++
                return JsonValue.JsonObject(map)
            }
            while (true) {
                skipWhitespace()
                if (isAtEnd || src[index] != '"') {
                    throw JsonParseException("Expected object key string at index $index")
                }
                val key = parseString()
                skipWhitespace()
                if (isAtEnd || src[index] != ':') {
                    throw JsonParseException("Expected ':' after object key at index $index")
                }
                index++
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                if (isAtEnd) throw JsonParseException("Unterminated JSON object")
                when (src[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.JsonObject(map)
                    }
                    else -> throw JsonParseException("Expected ',' or '}' in object at index $index")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.JsonArray {
            index++ // '['
            skipWhitespace()
            val list = mutableListOf<JsonValue>()
            if (!isAtEnd && src[index] == ']') {
                index++
                return JsonValue.JsonArray(list)
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                if (isAtEnd) throw JsonParseException("Unterminated JSON array")
                when (src[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.JsonArray(list)
                    }
                    else -> throw JsonParseException("Expected ',' or ']' in array at index $index")
                }
            }
        }

        private fun parseString(): String {
            index++ // opening '"'
            val sb = StringBuilder()
            while (index < src.length) {
                val c = src[index++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (index >= src.length) throw JsonParseException("Unterminated escape sequence")
                        when (val esc = src[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val codeUnit = parseHex4()
                                sb.append(codeUnit.toChar())
                            }
                            else -> throw JsonParseException("Invalid JSON escape '\\$esc' at index ${index - 1}")
                        }
                    }
                    c.code < 0x20 -> throw JsonParseException("Unescaped control character in JSON string at index ${index - 1}")
                    else -> sb.append(c)
                }
            }
            throw JsonParseException("Unterminated JSON string")
        }

        private fun parseHex4(): Int {
            if (index + 4 > src.length) {
                throw JsonParseException("Truncated \\u escape at index $index")
            }
            var value = 0
            repeat(4) {
                val ch = src[index++]
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> throw JsonParseException("Invalid hex digit '$ch' in \\u escape at index ${index - 1}")
                }
                value = (value shl 4) or digit
            }
            return value
        }

        private fun parseNumber(): JsonValue.JsonNumber {
            val start = index
            if (src[index] == '-') index++
            if (isAtEnd) throw JsonParseException("Incomplete number at index $start")
            if (src[index] == '0') {
                index++
            } else if (src[index] in '1'..'9') {
                while (index < src.length && src[index] in '0'..'9') index++
            } else {
                throw JsonParseException("Invalid number starting at index $start")
            }
            if (index < src.length && src[index] == '.') {
                index++
                if (index >= src.length || src[index] !in '0'..'9') {
                    throw JsonParseException("Expected digit after decimal point at index $index")
                }
                while (index < src.length && src[index] in '0'..'9') index++
            }
            if (index < src.length && (src[index] == 'e' || src[index] == 'E')) {
                index++
                if (index < src.length && (src[index] == '+' || src[index] == '-')) index++
                if (index >= src.length || src[index] !in '0'..'9') {
                    throw JsonParseException("Expected digit in exponent at index $index")
                }
                while (index < src.length && src[index] in '0'..'9') index++
            }
            return JsonValue.JsonNumber(src.substring(start, index))
        }

        private fun expectLiteral(literal: String) {
            if (!src.startsWith(literal, index)) {
                throw JsonParseException("Expected '$literal' at index $index")
            }
            index += literal.length
        }
    }

    // endregion
}
