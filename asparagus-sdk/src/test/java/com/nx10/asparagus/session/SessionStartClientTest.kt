package com.nx10.asparagus.session

import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.DeviceProperties
import com.nx10.asparagus.contract.MotionSource
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.device.AppVersionInfo
import com.nx10.asparagus.device.DeviceInfoSource
import com.nx10.asparagus.internal.codec.JsonCodec
import com.nx10.asparagus.internal.codec.JsonValue
import com.nx10.asparagus.internal.net.HttpRequest
import com.nx10.asparagus.internal.net.HttpResponse
import com.nx10.asparagus.internal.net.HttpTransport
import com.nx10.asparagus.internal.net.RetryBackoff
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.SessionClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SessionStartClientTest {

    private class FakeDeviceInfo : DeviceInfoSource {
        override fun deviceIdentifier(): String = "synthetic-android-id-1"
        override fun deviceProperties(): DeviceProperties = DeviceProperties(
            maker = "MadeUpMaker",
            model = "MadeUpPhone 3",
            osVersion = "15",
            deviceType = "Handheld",
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            screenDensityDpi = 420f,
            hasGyroscope = true,
            timezone = "Europe/London",
            locale = "en-GB",
        )

        override fun screenRecord(timeUs: Long): ScreenRecord = ScreenRecord(
            timeUs = timeUs,
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420f,
            orientation = Orientation.ORIENTATION_PORTRAIT,
            origin = CoordinateOrigin.COORDINATE_ORIGIN_TOP_LEFT,
        )

        override fun hasGyroscope(): Boolean = true
        override fun appVersionInfo(): AppVersionInfo = AppVersionInfo("1.2.3", "42")
    }

    private class FakeTimeSource(
        var elapsedNanos: Long = 100_000_000_000L,
        var wallMs: Long = 1_795_000_000_000L,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = 0
    }

    private class ScriptedTransport(
        private val actions: ArrayDeque<(HttpRequest) -> HttpResponse>,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val action = actions.removeFirstOrNull()
                ?: throw AssertionError("Unexpected extra HTTP request to ${request.url}")
            return action(request)
        }
    }

    private fun successResponseJson(
        batchEndpoint: String = "https://intake.stage.example/asparagus/v1/batches",
        accessEndpoint: String = "https://cp.stage.example/access",
        forgetEndpoint: String = "https://cp.stage.example/forget",
    ): String = """
        {
          "status": "success",
          "data": {
            "id_org": "made-up-org-1",
            "id_deployment": "made-up-dep-1",
            "id_source": "made-up-src-1",
            "id_session": "made-up-ses-1",
            "token": "synthetic-opaque-session-token",
            "token_expires_utc_ms": "1795086400000",
            "server_utc_ms": "1795000000500",
            "batch_endpoint": "$batchEndpoint",
            "access_request_endpoint": "$accessEndpoint",
            "forget_request_endpoint": "$forgetEndpoint",
            "settings": {
              "version": "settings-v3",
              "accelerometer_hz": 50,
              "gyroscope_hz": 0,
              "compress": false,
              "motion_source": "MOTION_SOURCE_UNITY"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `startSession defaults to Stage endpoint, switches to Production, merges settings, and respects empty batch_endpoint or demo`() = runBlocking {
        val time = FakeTimeSource()
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { req ->
                        // Simulate 65ms RTT on default Stage endpoint
                        time.elapsedNanos += 65_000_000L
                        assertEquals(SessionStartClient.STAGE_SESSION_START_ENDPOINT, req.url)
                        assertEquals("application/json", req.headers["content-type"])
                        val body = JsonCodec.parse(req.body.toString(Charsets.UTF_8)) as JsonValue.JsonObject
                        assertEquals(JsonValue.JsonString("synthetic-api-key"), body["api_key"])
                        assertEquals(JsonValue.JsonString("synthetic-android-id-1"), body["device_identifier"])
                        assertEquals(JsonValue.JsonString("PLATFORM_ANDROID"), body["platform"])
                        HttpResponse(200, body = successResponseJson().toByteArray(Charsets.UTF_8))
                    },
                    { req ->
                        // Production endpoint when useProduction = true
                        assertEquals(SessionStartClient.PRODUCTION_SESSION_START_ENDPOINT, req.url)
                        HttpResponse(200, body = successResponseJson(batchEndpoint = "").toByteArray(Charsets.UTF_8))
                    },
                    { req ->
                        // Stage default again with demo = true
                        assertEquals(SessionStartClient.STAGE_SESSION_START_ENDPOINT, req.url)
                        HttpResponse(200, body = successResponseJson().toByteArray(Charsets.UTF_8))
                    },
                ),
            ),
        )

        val client = SessionStartClient(
            deviceInfo = FakeDeviceInfo(),
            transport = transport,
            timeSource = time,
            ioDispatcher = Dispatchers.Unconfined,
            delayFn = {},
        )

        val res = client.startSession(
            apiKey = "synthetic-api-key",
            sessionProperties = mapOf("env" to Value.Text("test")),
        ) as SessionStartResult.Started

        val session = res.session
        assertTrue(session.collectionEnabled)
        assertEquals("made-up-org-1", session.idOrg)
        assertEquals("made-up-ses-1", session.idSession)
        assertEquals("synthetic-opaque-session-token", session.token)
        assertEquals(65, session.serverAnchor.roundTripMs)
        assertEquals(1_795_000_000_500L, session.serverAnchor.serverUtcMs)
        assertEquals("settings-v3", session.settings.version)
        assertEquals(50, session.settings.accelerometerHz)
        assertEquals(0, session.settings.gyroscopeHz)
        assertEquals(false, session.settings.compress)
        assertEquals(MotionSource.MOTION_SOURCE_UNITY, session.settings.motionSource)
        assertEquals(10, session.settings.batchLengthS) // default preserved

        // useProduction = true hits Production endpoint; empty batch_endpoint -> collectionEnabled = false
        val prodEmptyEndpointRes = client.startSession(
            apiKey = "synthetic-api-key",
            useProduction = true,
        ) as SessionStartResult.Started
        assertFalse(prodEmptyEndpointRes.session.collectionEnabled)

        // demo = true -> collectionEnabled = false even if endpoint returned
        val demoRes = client.startSession(
            apiKey = "synthetic-api-key",
            demo = true,
        ) as SessionStartResult.Started
        assertFalse(demoRes.session.collectionEnabled)
    }

    @Test
    fun `startSession retries on 503, 429, and IOException using built-in backoff waits then succeeds`() = runBlocking {
        val observedWaits = mutableListOf<Long>()
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { HttpResponse(503, body = """{"status":"error","errors":["unavailable"]}""".toByteArray()) },
                    { HttpResponse(429, body = """{"status":"error","errors":["rate_limited"]}""".toByteArray()) },
                    { throw IOException("simulated socket timeout") },
                    { HttpResponse(200, body = successResponseJson().toByteArray(Charsets.UTF_8)) },
                ),
            ),
        )

        val client = SessionStartClient(
            deviceInfo = FakeDeviceInfo(),
            transport = transport,
            timeSource = FakeTimeSource(),
            retryBackoff = RetryBackoff(jitterSource = { 0.0 }),
            ioDispatcher = Dispatchers.Unconfined,
            delayFn = { observedWaits.add(it) },
        )

        val result = client.startSession(apiKey = "synthetic-api-key")
        assertTrue(result is SessionStartResult.Started)
        assertEquals(4, transport.requests.size)
        assertTrue(transport.requests.all { it.url == SessionStartClient.STAGE_SESSION_START_ENDPOINT })
        assertEquals(listOf(2_000L, 4_000L, 8_000L), observedWaits)
    }

    @Test
    fun `startSession permanently refuses on 403 and makes no further network calls in same app run`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { HttpResponse(403, body = """{"status":"error","errors":["invalid_api_key"]}""".toByteArray()) },
                ),
            ),
        )
        val client = SessionStartClient(
            deviceInfo = FakeDeviceInfo(),
            transport = transport,
            timeSource = FakeTimeSource(),
            ioDispatcher = Dispatchers.Unconfined,
            delayFn = { throw AssertionError("Should not retry on 403") },
        )

        val first = client.startSession(apiKey = "bad-key")
        assertTrue(first is SessionStartResult.Refused)
        assertEquals(403, (first as SessionStartResult.Refused).statusCode)
        assertEquals(listOf("invalid_api_key"), first.errors)
        assertTrue(client.isRefusedInThisAppRun)

        // Second call must NOT touch the transport
        val second = client.startSession(apiKey = "bad-key")
        assertEquals(first, second)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `compliance endpoints send bearer auth and handle unavailable endpoints gracefully`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { req ->
                        assertEquals("https://cp.stage.example/access", req.url)
                        assertEquals("Bearer synthetic-opaque-session-token", req.headers["authorization"])
                        assertEquals("application/json", req.headers["content-type"])
                        HttpResponse(200, body = """{"status":"accepted"}""".toByteArray())
                    },
                    { req ->
                        assertEquals("https://cp.stage.example/forget", req.url)
                        assertEquals("Bearer synthetic-opaque-session-token", req.headers["authorization"])
                        HttpResponse(202, body = """{"status":"queued"}""".toByteArray())
                    },
                ),
            ),
        )
        val time = FakeTimeSource()
        val client = SessionStartClient(
            deviceInfo = FakeDeviceInfo(),
            transport = transport,
            timeSource = time,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val clock = SessionClock(timeSource = time, appStartElapsedNanos = time.elapsedNanos)
        val anchor = clock.anchor(serverUtcMs = time.wallMs, roundTripMs = 10)
        val session = ActiveSession(
            idOrg = "made-up-org-1",
            idDeployment = "made-up-dep-1",
            idSource = "made-up-src-1",
            idSession = "made-up-ses-1",
            token = "synthetic-opaque-session-token",
            tokenExpiresUtcMs = time.wallMs + 86_400_000L,
            batchEndpoint = "https://intake.stage.example/asparagus/v1/batches",
            accessRequestEndpoint = "https://cp.stage.example/access",
            forgetRequestEndpoint = "https://cp.stage.example/forget",
            settings = SdkSettings.loadDefaults(),
            models = emptyList(),
            serverAnchor = anchor,
            collectionEnabled = true,
            clock = clock,
        )

        val access = client.requestPersonalDataAccess(session)
        assertTrue(access is ComplianceResult.Success)
        assertEquals(200, (access as ComplianceResult.Success).statusCode)

        val forget = client.requestPersonalDataDeletion(session)
        assertTrue(forget is ComplianceResult.Success)
        assertEquals(202, (forget as ComplianceResult.Success).statusCode)

        // Blank endpoint -> Unavailable without hitting network
        val unavailable = client.requestPersonalDataAccess(session.copy(accessRequestEndpoint = ""))
        assertTrue(unavailable is ComplianceResult.Unavailable)
        assertEquals(2, transport.requests.size)
    }
}
