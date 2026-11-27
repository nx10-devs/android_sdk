package com.nx10.asparagus.internal.codec

import com.nx10.asparagus.contract.CollectionHistory
import com.nx10.asparagus.contract.DeviceProperties
import com.nx10.asparagus.contract.MotionSource
import com.nx10.asparagus.contract.Platform
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.contract.SessionStartRequest
import com.nx10.asparagus.contract.SessionStartResponse
import com.nx10.asparagus.contract.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonCodecTest {

    @Test
    fun `encodeSessionStartRequest uses strict snake_case and omits empty defaults`() {
        val request = SessionStartRequest(
            apiKey = "test-api-key",
            deviceIdentifier = "android-id-abc",
            sdkVersion = SdkVersion(major = 0, minor = 1, patch = 0),
            platform = Platform.PLATFORM_ANDROID,
            device = DeviceProperties(
                maker = "Google",
                model = "Pixel 9",
                osVersion = "15",
                deviceType = "Handheld",
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                screenDensityDpi = 420f,
                hasGyroscope = true,
                timezone = "Europe/London",
                locale = "en-GB",
            ),
            appVersion = "1.4.0",
            buildNumber = "42",
            sessionProperties = linkedMapOf(
                "env" to Value.Text("stage"),
                "zero" to Value.Number(0.0),
                "flag_false" to Value.Flag(false),
                "empty_prop" to Value.Empty,
            ),
            demo = false,
        )

        val json = JsonCodec.encodeSessionStartRequest(request)
        val parsed = JsonCodec.parse(json) as JsonValue.JsonObject

        assertEquals(JsonValue.JsonString("test-api-key"), parsed["api_key"])
        assertEquals(JsonValue.JsonString("android-id-abc"), parsed["device_identifier"])
        assertEquals(JsonValue.JsonString("PLATFORM_ANDROID"), parsed["platform"])
        assertEquals(JsonValue.JsonString("1.4.0"), parsed["app_version"])
        assertEquals(JsonValue.JsonString("42"), parsed["build_number"])
        assertNull("demo=false must be omitted", parsed["demo"])

        val sdkVer = parsed["sdk_version"] as JsonValue.JsonObject
        assertNull("major=0 omitted", sdkVer["major"])
        assertEquals(JsonValue.JsonNumber("1"), sdkVer["minor"])
        assertNull("patch=0 omitted", sdkVer["patch"])

        val device = parsed["device"] as JsonValue.JsonObject
        assertEquals(JsonValue.JsonString("Google"), device["maker"])
        assertEquals(JsonValue.JsonString("Pixel 9"), device["model"])
        assertEquals(JsonValue.JsonString("15"), device["os_version"])
        assertEquals(JsonValue.JsonString("Handheld"), device["device_type"])
        assertEquals(JsonValue.JsonNumber("1080"), device["screen_width_px"])
        assertEquals(JsonValue.JsonNumber("2400"), device["screen_height_px"])
        assertEquals(JsonValue.JsonBool(true), device["has_gyroscope"])
        assertEquals(JsonValue.JsonString("Europe/London"), device["timezone"])
        assertEquals(JsonValue.JsonString("en-GB"), device["locale"])

        val props = parsed["session_properties"] as JsonValue.JsonObject
        assertEquals(JsonValue.JsonObject(mapOf("text" to JsonValue.JsonString("stage"))), props["env"])
        assertEquals(JsonValue.JsonObject(mapOf("number" to JsonValue.JsonNumber("0.0"))), props["zero"])
        assertEquals(JsonValue.JsonObject(mapOf("flag" to JsonValue.JsonBool(false))), props["flag_false"])
        assertEquals(JsonValue.JsonObject(emptyMap()), props["empty_prop"])
    }

    @Test
    fun `decodeSessionStartEnvelope parses 64-bit string numbers and ignores unknown fields and enums`() {
        val rawJson = """
            {
              "status": "success",
              "extra_top_level": [1, 2, 3],
              "data": {
                "id_org": "org-1",
                "id_deployment": "dep-1",
                "id_source": "src-1",
                "id_session": "ses-1",
                "token": "tok-xyz",
                "token_expires_utc_ms": "9007199254740993",
                "server_utc_ms": "1794733920250",
                "batch_endpoint": "https://intake.example/asparagus/v1/batches",
                "access_request_endpoint": "https://cp.example/access",
                "forget_request_endpoint": "https://cp.example/forget",
                "unknown_nested": { "foo": "bar" },
                "settings": {
                  "version": "s-7",
                  "accelerometer_hz": 0,
                  "compress": false,
                  "motion_source": "MOTION_SOURCE_FUTURE_HARDWARE",
                  "batch_length_s": 15,
                  "magnetometer_hz": 20,
                  "magnetometer_hold_tolerance": 3,
                  "unknown_setting": 999
                },
                "models": [
                  {
                    "purpose": "ad_propensity",
                    "name": "propensity_v1",
                    "version": "1.0.0",
                    "download_url": "https://cdn.example/m.onnx",
                    "arguments": ["level", "score"]
                  }
                ],
                "settings_label": "All devices"
              }
            }
        """.trimIndent()

        val envelope = JsonCodec.decodeSessionStartEnvelope(rawJson)
        assertEquals("success", envelope.status)
        val data = envelope.data
        assertNotNull(data)
        data!!

        assertEquals("org-1", data.idOrg)
        assertEquals("dep-1", data.idDeployment)
        assertEquals("src-1", data.idSource)
        assertEquals("ses-1", data.idSession)
        assertEquals("tok-xyz", data.token)
        assertEquals(9007199254740993L, data.tokenExpiresUtcMs)
        assertEquals(1794733920250L, data.serverUtcMs)
        assertEquals("https://intake.example/asparagus/v1/batches", data.batchEndpoint)
        assertEquals("https://cp.example/access", data.accessRequestEndpoint)
        assertEquals("https://cp.example/forget", data.forgetRequestEndpoint)

        val settings = data.settings!!
        assertEquals("s-7", settings.version)
        assertEquals(0, settings.accelerometerHz)
        assertEquals(false, settings.compress)
        assertNull("Unknown enum name must be ignored", settings.motionSource)
        assertEquals(15, settings.batchLengthS)
        assertEquals(20, settings.magnetometerHz)
        assertEquals(3, settings.magnetometerHoldTolerance)
        assertNull("Omitted setting must remain null", settings.gyroscopeHz)

        assertEquals(1, data.models.size)
        assertEquals("ad_propensity", data.models[0].purpose)
        assertEquals(listOf("level", "score"), data.models[0].arguments)
        assertEquals("All devices", data.settingsLabel)
    }

    @Test
    fun `encodeSessionStartRequest encodes CollectionHistory uint64 as string and omits all-zero history`() {
        val zeroHistoryReq = SessionStartRequest(
            apiKey = "test-api-key",
            appVersion = "1.0.0",
            history = CollectionHistory(0, 0L, 0),
        )
        val zeroParsed = JsonCodec.parse(JsonCodec.encodeSessionStartRequest(zeroHistoryReq)) as JsonValue.JsonObject
        assertNull("All-zero CollectionHistory must be omitted", zeroParsed["history"])

        val populatedReq = SessionStartRequest(
            apiKey = "test-api-key",
            appVersion = "1.0.0",
            history = CollectionHistory(
                sessionsBefore = 4,
                collectedSecondsBefore = 3600L,
                longestSessionSecondsBefore = 1200,
            ),
        )
        val encodedJson = JsonCodec.encodeSessionStartRequest(populatedReq)
        val parsed = JsonCodec.parse(encodedJson) as JsonValue.JsonObject
        val historyObj = parsed["history"] as JsonValue.JsonObject
        assertEquals(JsonValue.JsonNumber("4"), historyObj["sessions_before"])
        assertEquals(JsonValue.JsonString("3600"), historyObj["collected_seconds_before"])
        assertEquals(JsonValue.JsonNumber("1200"), historyObj["longest_session_seconds_before"])

        val decoded = JsonCodec.decodeSessionStartRequest(encodedJson)
        assertEquals(populatedReq.history, decoded.history)

        // Response settings_label round-trip (snake_case & camelCase)
        val respJson = JsonCodec.encodeSessionStartResponse(
            SessionStartResponse(idSession = "s1", settingsLabel = "Canary 10%"),
        )
        val respParsed = JsonCodec.parse(respJson) as JsonValue.JsonObject
        assertEquals(JsonValue.JsonString("Canary 10%"), respParsed["settings_label"])
        assertEquals("Canary 10%", JsonCodec.decodeSessionStartResponse(respJson).settingsLabel)
        assertEquals(
            "Beta Share",
            JsonCodec.decodeSessionStartResponse("""{"idSession":"s2","settingsLabel":"Beta Share"}""").settingsLabel,
        )
    }

    @Test
    fun `decodeSessionStartResponse supports camelCase keys`() {
        val camelJson = """
            {
              "idOrg": "o1",
              "idDeployment": "d1",
              "idSource": "s1",
              "idSession": "sess1",
              "tokenExpiresUtcMs": "1795000000000",
              "serverUtcMs": 1794900000000,
              "batchEndpoint": "https://stage/batches",
              "settings": {
                "motionSource": "MOTION_SOURCE_UNITY",
                "pauseTouchOnKeyboard": true,
                "newSessionAfterS": 900,
                "magnetometerHz": 0,
                "magnetometerHoldTolerance": 2
              }
            }
        """.trimIndent()

        val resp = JsonCodec.decodeSessionStartResponse(camelJson)
        assertEquals("o1", resp.idOrg)
        assertEquals("sess1", resp.idSession)
        assertEquals(1795000000000L, resp.tokenExpiresUtcMs)
        assertEquals(1794900000000L, resp.serverUtcMs)
        assertEquals(MotionSource.MOTION_SOURCE_UNITY, resp.settings?.motionSource)
        assertEquals(true, resp.settings?.pauseTouchOnKeyboard)
        assertEquals(900, resp.settings?.newSessionAfterS)
        assertEquals(0, resp.settings?.magnetometerHz)
        assertEquals(2, resp.settings?.magnetometerHoldTolerance)
    }

    @Test
    fun `json parser handles unicode surrogate pairs and escapes accurately`() {
        val json = """{"label":"Caf\u00e9 \u5c0f\u5e97 \uD83D\uDED2\n\"quoted\"\\slash"}"""
        val obj = JsonCodec.parse(json) as JsonValue.JsonObject
        val expected = "Café 小店 🛒\n\"quoted\"\\slash"
        assertEquals(JsonValue.JsonString(expected), obj["label"])

        // Round-trip stringify -> parse
        val serialized = JsonCodec.stringify(obj)
        assertFalse(serialized.contains("\n"))
        assertTrue(serialized.contains("\\n"))
        assertEquals(obj, JsonCodec.parse(serialized))
    }
}
