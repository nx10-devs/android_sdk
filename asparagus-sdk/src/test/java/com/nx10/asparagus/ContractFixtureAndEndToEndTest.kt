package com.nx10.asparagus

import com.nx10.asparagus.batch.BatchBoundsAndHeaderValidator
import com.nx10.asparagus.batch.GzipCodec
import com.nx10.asparagus.capture.touch.RawTouchSample
import com.nx10.asparagus.capture.touch.TouchPhase
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.DeviceProperties
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.SpanMarker
import com.nx10.asparagus.contract.StateKind
import com.nx10.asparagus.contract.UploadResponse
import com.nx10.asparagus.contract.UploadStatus
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.device.AppVersionInfo
import com.nx10.asparagus.device.DeviceInfoSource
import com.nx10.asparagus.device.SystemStateCallback
import com.nx10.asparagus.device.SystemStateController
import com.nx10.asparagus.device.SystemStateControllerFactory
import com.nx10.asparagus.device.normalizeAndroidBrightness
import com.nx10.asparagus.events.SdkStateKeys
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.internal.codec.JsonCodec
import com.nx10.asparagus.internal.net.HttpRequest
import com.nx10.asparagus.internal.net.HttpResponse
import com.nx10.asparagus.internal.net.HttpTransport
import com.nx10.asparagus.session.SessionStartClient
import com.nx10.asparagus.session.SessionStartResult
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.ResumeDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContractFixtureAndEndToEndTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeTimeSource(
        var elapsedNanos: Long = 100_000_000_000L,
        var wallMs: Long = 1_795_000_000_500L,
        var offsetMinutes: Int = 60,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = offsetMinutes
    }

    private class FakeDeviceInfo(
        private val throwOnProperties: Boolean = false,
    ) : DeviceInfoSource {
        override fun deviceIdentifier(): String = "synthetic-device-id-1"
        override fun deviceProperties(): DeviceProperties {
            if (throwOnProperties) throw RuntimeException("simulated device failure")
            return DeviceProperties(
                maker = "MadeUp",
                model = "MadeUp Phone 3",
                osVersion = "15",
                deviceType = "Handheld",
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                screenDensityDpi = 420f,
                hasGyroscope = true,
                timezone = "Europe/London",
                locale = "en-GB",
            )
        }

        override fun screenRecord(timeUs: Long): ScreenRecord = ScreenRecord(
            timeUs = timeUs,
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420f,
            orientation = Orientation.ORIENTATION_PORTRAIT,
            origin = CoordinateOrigin.COORDINATE_ORIGIN_TOP_LEFT,
        )

        override fun hasGyroscope(): Boolean = true
        override fun hasMagnetometer(): Boolean = true
        override fun appVersionInfo(): AppVersionInfo = AppVersionInfo("1.0.0", "10")
    }

    private class RecordingTransport(
        private val handler: (HttpRequest) -> HttpResponse,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return handler(request)
        }
    }

    @Test
    fun `batchSchema2FixtureFullRoundTripAndValidation`() {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/batch_schema_2.json")
        assertNotNull("Missing vendored test resource fixtures/batch_schema_2.json", stream)
        val json = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val parsed = JsonCodec.decodeBatchFromJson(json)
        assertEquals(3, parsed.header?.schemaVersion)
        assertEquals(214L, parsed.header?.sequence)
        assertEquals(9007199254740993L, parsed.header?.clocks?.sensorUs)
        assertEquals(4, parsed.sensorBlocks.size)
        assertEquals(Sensor.SENSOR_MAGNETOMETER, parsed.sensorBlocks[3].sensor)
        assertEquals(com.nx10.asparagus.contract.ValueUnit.VALUE_UNIT_MICROTESLA, parsed.sensorBlocks[3].unit)
        assertEquals(0.01, parsed.sensorBlocks[3].scale, 1e-9)
        assertEquals(3, parsed.gaps.size)
        assertEquals(com.nx10.asparagus.contract.GapSignal.GAP_SIGNAL_MAGNETOMETER, parsed.gaps[2].signal)
        assertEquals(Int.MIN_VALUE, parsed.sensorBlocks[0].x[2])
        assertEquals(Int.MAX_VALUE, parsed.sensorBlocks[0].x[3])
        assertEquals(3, parsed.sensorBlocks[0].holds[0].stats.size)
        assertEquals(5, parsed.touch!!.contacts[0].holds[0].stats.size)
        assertNotNull(parsed.touch!!.contacts[0].before)
        assertEquals(Value.Empty, parsed.events[0].fields["bonus"])
        assertEquals(Value.Text("Café 小店 🛒"), parsed.events[2].fields["button"])
        assertEquals(12, parsed.carried?.state?.version)
        assertEquals(7, parsed.carried?.openSpans?.spans?.single()?.number)

        val rawBytes = BatchProtoCodec.encode(parsed)
        val gzippedBytes = GzipCodec.gzip(rawBytes)
        assertTrue(GzipCodec.isGzipped(gzippedBytes))
        val unzipped = GzipCodec.gunzip(gzippedBytes)
        val decoded = BatchProtoCodec.decode(unzipped)

        assertEquals(parsed, decoded)
        assertEquals(emptyList<String>(), BatchBoundsAndHeaderValidator.validateHeader(decoded.header))
        assertEquals(
            emptyList<String>(),
            BatchBoundsAndHeaderValidator.validateBounds(decoded, rawBytes, gzippedBytes),
        )
    }

    @Test
    fun `endToEndEngineSessionCaptureFlushAndUpload`() = runBlocking {
        val time = FakeTimeSource()
        val storedBody = BatchProtoCodec.encodeUploadResponse(UploadResponse(UploadStatus.UPLOAD_STATUS_STORED))
        val uploadedBatches = mutableListOf<ByteArray>()

        val transport = RecordingTransport { req ->
            when {
                req.url == SessionStartClient.STAGE_SESSION_START_ENDPOINT -> {
                    HttpResponse(
                        statusCode = 200,
                        body = """
                            {
                              "status": "success",
                              "data": {
                                "id_org": "made-up-org-1",
                                "id_deployment": "made-up-dep-1",
                                "id_source": "made-up-src-1",
                                "id_session": "made-up-ses-1",
                                "token": "synthetic-session-token-1",
                                "token_expires_utc_ms": "${time.wallMs + 86_400_000L}",
                                "server_utc_ms": "${time.wallMs}",
                                "batch_endpoint": "https://intake.stage.example/asparagus/v1/batches",
                                "settings": { "version": "s-e2e-1", "batch_length_s": 10, "compress": true }
                              }
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                }
                req.url == "https://intake.stage.example/asparagus/v1/batches" -> {
                    assertEquals("Bearer synthetic-session-token-1", req.headers["authorization"])
                    assertEquals("application/x-protobuf", req.headers["content-type"])
                    assertEquals("gzip", req.headers["content-encoding"])
                    uploadedBatches.add(req.body)
                    HttpResponse(200, body = storedBody)
                }
                else -> throw AssertionError("Unexpected request URL: ${req.url}")
            }
        }

        val engine = AsparagusEngine(
            deviceInfo = FakeDeviceInfo(),
            queueDir = tempFolder.newFolder("e2e-queue"),
            transport = transport,
            timeSource = time,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            enablePeriodicTicker = false,
            sleeper = {},
        )

        val startRes = engine.startSessionAwait("synthetic-api-key")
        assertTrue(startRes is SessionStartResult.Started)
        assertEquals(SdkSessionState.ACTIVE, engine.statusFlow.value.sessionState)

        // Feed motion samples (accelerometer + magnetometer)
        val motion = engine.activeMotionCapture()!!
        motion.ringBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 100_010_000L, 0.2f, -0.1f, 9.8f)
        motion.ringBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 100_020_000L, 0.4f, -0.2f, 9.7f)
        motion.ringBuffer.push(Sensor.SENSOR_MAGNETOMETER.protoNumber, 100_010_000L, 20.00f, -5.00f, -40.00f)
        motion.ringBuffer.push(Sensor.SENSOR_MAGNETOMETER.protoNumber, 100_110_000L, 20.03f, -5.01f, -39.98f)

        // Feed multi-touch contact
        engine.recordRawTouchSample(RawTouchSample(1, TouchPhase.DOWN, 10_000L, 200f, 400f, 10f, 9f, 0.5f))
        engine.recordRawTouchSample(RawTouchSample(1, TouchPhase.UP, 60_000L, 240f, 420f, 10f, 9f, 0.0f))

        // Interleaved core events & state mutation
        time.elapsedNanos += 100_000_000L
        engine.coreEvents.levelStarted(level = "1", attemptNumber = 1.0) // order 1, open span 1
        time.elapsedNanos += 50_000_000L
        engine.setAttribute("level", Value.Text("1")) // order 2, version 1
        time.elapsedNanos += 50_000_000L
        engine.coreEvents.levelSucceeded(level = "1", score = 500.0) // order 3, close span 1

        // Flush window & drain queue
        time.elapsedNanos += 9_800_000_000L
        engine.flushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = true)
        engine.drainQueueNow()

        assertEquals(1, uploadedBatches.size)
        val raw = GzipCodec.gunzip(uploadedBatches.single())
        val decoded = BatchProtoCodec.decode(raw)

        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(decoded.header).isEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateBounds(decoded, raw, uploadedBatches.single()).isEmpty())

        assertEquals("made-up-org-1", decoded.header?.idOrg)
        assertEquals("made-up-dep-1", decoded.header?.idDeployment)
        assertEquals("made-up-src-1", decoded.header?.idSource)
        assertEquals("made-up-ses-1", decoded.header?.idSession)
        assertEquals(1L, decoded.header?.sequence)
        assertEquals(3, decoded.header?.schemaVersion)
        assertEquals("s-e2e-1", decoded.header?.settingsVersion)
        assertEquals(2, decoded.sensorBlocks.size)
        assertEquals(2, decoded.sensorBlocks[0].sampleCount)
        assertEquals(Sensor.SENSOR_MAGNETOMETER, decoded.sensorBlocks[1].sensor)
        assertEquals(com.nx10.asparagus.contract.ValueUnit.VALUE_UNIT_MICROTESLA, decoded.sensorBlocks[1].unit)
        assertEquals(0.01, decoded.sensorBlocks[1].scale, 1e-9)
        assertEquals(listOf(2000, 3), decoded.sensorBlocks[1].x)
        assertEquals(listOf(-500, -1), decoded.sensorBlocks[1].y)
        assertEquals(listOf(-4000, 2), decoded.sensorBlocks[1].z)
        assertEquals(1, decoded.touch?.contacts?.size)
        assertEquals(listOf(1, 3), decoded.events.map { it.order })
        assertEquals(SpanMarker.SPAN_MARKER_OPEN, decoded.events[0].spanMarker)
        assertEquals(SpanMarker.SPAN_MARKER_CLOSE, decoded.events[1].spanMarker)
        assertEquals(1, decoded.events[1].spanNumber)
        assertEquals(listOf(2), decoded.stateRecords.map { it.order })
        assertEquals(StateKind.STATE_KIND_PATCH, decoded.stateRecords.single().kind)
        assertNotNull(decoded.carried?.state)
        assertNotNull(decoded.carried?.openSpans)
        assertEquals(0, engine.queue.size())
        assertEquals(1, engine.statusFlow.value.uploadedBatchesTotal)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `endToEndEnvironmentSwitchStopTelemetryRotationAndFaultSafety`() = runBlocking {
        val time = FakeTimeSource()
        val storedBody = BatchProtoCodec.encodeUploadResponse(UploadResponse(UploadStatus.UPLOAD_STATUS_STORED))
        var sessionCounter = 0
        val sessionStartUrls = mutableListOf<String>()
        val uploadedDecodedBatches = mutableListOf<com.nx10.asparagus.contract.Batch>()

        val transport = RecordingTransport { req ->
            when {
                req.url.endsWith("/routes/asparagus/sessions/start") -> {
                    sessionCounter++
                    sessionStartUrls.add(req.url)
                    HttpResponse(
                        statusCode = 200,
                        body = """
                            {
                              "status": "success",
                              "data": {
                                "id_org": "made-up-org-1",
                                "id_deployment": "made-up-dep-1",
                                "id_source": "made-up-src-1",
                                "id_session": "ses-$sessionCounter",
                                "token": "synthetic-token-$sessionCounter",
                                "token_expires_utc_ms": "${time.wallMs + 86_400_000L}",
                                "server_utc_ms": "${time.wallMs}",
                                "batch_endpoint": "https://intake.stage.example/asparagus/v1/batches",
                                "settings": { "new_session_after_s": 60, "compress": false }
                              }
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                }
                else -> {
                    uploadedDecodedBatches.add(BatchProtoCodec.decode(req.body))
                    HttpResponse(200, body = storedBody)
                }
            }
        }

        val engine = AsparagusEngine(
            deviceInfo = FakeDeviceInfo(),
            queueDir = tempFolder.newFolder("e2e-env-rotate"),
            transport = transport,
            timeSource = time,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            enablePeriodicTicker = false,
            sleeper = {},
        )

        // 1. Default environment is Stage
        assertFalse(engine.useProduction)
        assertFalse(engine.statusFlow.value.useProduction)
        assertEquals(SessionStartClient.STAGE_SESSION_START_ENDPOINT, engine.statusFlow.value.sessionStartEndpoint)

        val stageStart = engine.startSessionAwait("synthetic-api-key")
        assertTrue(stageStart is SessionStartResult.Started)
        assertEquals("ses-1", engine.activeSession()?.idSession)
        assertEquals(SessionStartClient.STAGE_SESSION_START_ENDPOINT, sessionStartUrls.last())

        // Obsolete live outputs make zero network requests and return NotAvailable
        assertEquals(ObsoleteLiveOutputResult.NotAvailable(), engine.requestActivity())
        assertEquals(ObsoleteLiveOutputResult.NotAvailable(), engine.requestAffect())

        // Obsolete fireEvent forwards to sendEvent; stopTelemetry / startTelemetry emits GAP_CAUSE_CAPTURE_STOPPED
        engine.fireEvent("legacy_ping", mapOf("k" to Value.Text("v")))
        engine.stopTelemetryAwait()
        time.elapsedNanos += 3_000_000_000L
        engine.startTelemetry()
        engine.flushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
        engine.drainQueueNow()

        assertTrue(
            "Expected GAP_CAUSE_CAPTURE_STOPPED after stopTelemetry -> startTelemetry",
            uploadedDecodedBatches.any { b -> b.gaps.any { it.cause == GapCause.GAP_CAUSE_CAPTURE_STOPPED } },
        )
        assertTrue(
            "Expected legacy_ping forwarded from fireEvent",
            uploadedDecodedBatches.any { b -> b.events.any { it.name == "legacy_ping" } },
        )

        // 2. Toggle to Production -> updates snapshot and routes start + rotation to Production endpoint
        engine.useProduction = true
        assertTrue(engine.statusFlow.value.useProduction)
        assertEquals(SessionStartClient.PRODUCTION_SESSION_START_ENDPOINT, engine.statusFlow.value.sessionStartEndpoint)

        engine.startSessionAwait("synthetic-api-key")
        assertEquals("ses-2", engine.activeSession()?.idSession)
        assertEquals(SessionStartClient.PRODUCTION_SESSION_START_ENDPOINT, sessionStartUrls.last())

        engine.coreEvents.levelStarted(level = "99") // span 1 in session 2
        engine.setAttribute("old_key", Value.Text("old_val"))
        assertEquals(1, engine.statusFlow.value.stateVersion)

        // Background away > new_session_after_s (60s) -> RotateSession hits Production with fresh span/state!
        engine.onAppBackgroundedAwait()
        time.elapsedNanos += 65_000_000_000L // 65s > 60s
        time.wallMs += 65_000L
        val resumeDecision = engine.onAppForegroundedAwait()
        assertTrue(resumeDecision is ResumeDecision.RotateSession)

        assertEquals("ses-3", engine.activeSession()?.idSession)
        assertEquals(SessionStartClient.PRODUCTION_SESSION_START_ENDPOINT, sessionStartUrls.last())
        assertEquals(0, engine.statusFlow.value.stateVersion)
        assertTrue(engine.statusFlow.value.openSpans.isEmpty())
        engine.coreEvents.levelStarted(level = "1")
        assertEquals(listOf("level#1"), engine.statusFlow.value.openSpans)

        // Toggle back to Stage -> snapshot and next start route back to Stage endpoint
        engine.useProduction = false
        assertFalse(engine.statusFlow.value.useProduction)
        assertEquals(SessionStartClient.STAGE_SESSION_START_ENDPOINT, engine.statusFlow.value.sessionStartEndpoint)
        engine.startSessionAwait("synthetic-api-key")
        assertEquals("ses-4", engine.activeSession()?.idSession)
        assertEquals(SessionStartClient.STAGE_SESSION_START_ENDPOINT, sessionStartUrls.last())

        // 3. Fault isolation (Priority 2): broken DeviceInfoSource must never crash host app
        val faultyEngine = AsparagusEngine(
            deviceInfo = FakeDeviceInfo(throwOnProperties = true),
            queueDir = tempFolder.newFolder("e2e-faulty"),
            transport = transport,
            timeSource = time,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            enablePeriodicTicker = false,
            sleeper = {},
        )
        val refused = faultyEngine.startSessionAwait("synthetic-api-key")
        assertTrue(refused is SessionStartResult.Refused)
        assertFalse(faultyEngine.statusFlow.value.collectionEnabled)
    }

    @Test
    fun `normalizeAndroidBrightnessCoversRangeClampingAndQuantization`() {
        assertEquals(0.0, normalizeAndroidBrightness(0), 1e-9)
        assertEquals(1.0, normalizeAndroidBrightness(255), 1e-9)
        assertEquals(0.5, normalizeAndroidBrightness(128), 1e-9)
        assertEquals(0.25, normalizeAndroidBrightness(64), 1e-9)
        assertEquals(0.0, normalizeAndroidBrightness(-10), 1e-9)
        assertEquals(1.0, normalizeAndroidBrightness(300), 1e-9)
    }

    @Test
    fun `endToEndSdkSystemStateLifecycleObservationProtectionAndSnapshot`() = runBlocking {
        val time = FakeTimeSource()
        val storedBody = BatchProtoCodec.encodeUploadResponse(UploadResponse(UploadStatus.UPLOAD_STATUS_STORED))
        val uploadedDecodedBatches = mutableListOf<com.nx10.asparagus.contract.Batch>()

        val transport = RecordingTransport { req ->
            when {
                req.url.endsWith("/routes/asparagus/sessions/start") -> {
                    HttpResponse(
                        statusCode = 200,
                        body = """
                            {
                              "status": "success",
                              "data": {
                                "id_org": "made-up-org-1",
                                "id_deployment": "made-up-dep-1",
                                "id_source": "made-up-src-1",
                                "id_session": "sys-state-ses-1",
                                "token": "synthetic-sys-token",
                                "token_expires_utc_ms": "${time.wallMs + 86_400_000L}",
                                "server_utc_ms": "${time.wallMs}",
                                "batch_endpoint": "https://intake.stage.example/asparagus/v1/batches",
                                "settings": { "version": "s-v3-sys", "new_session_after_s": 60, "compress": false }
                              }
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                }
                else -> {
                    uploadedDecodedBatches.add(BatchProtoCodec.decode(req.body))
                    HttpResponse(200, body = storedBody)
                }
            }
        }

        var capturedCallback: SystemStateCallback? = null
        var startCount = 0
        var resumedCount = 0
        var stopCount = 0
        var resumedBrightness = 0.80
        var resumedLocked = true

        val fakeSystemStateFactory = SystemStateControllerFactory { callback ->
            capturedCallback = callback
            object : SystemStateController {
                override fun start() {
                    startCount++
                    callback.onSystemStateObserved(screenBrightness = 0.75, screenLocked = false)
                }

                override fun onResumed() {
                    resumedCount++
                    callback.onSystemStateObserved(screenBrightness = resumedBrightness, screenLocked = resumedLocked)
                }

                override fun stop() {
                    stopCount++
                }
            }
        }

        val engine = AsparagusEngine(
            deviceInfo = FakeDeviceInfo(),
            queueDir = tempFolder.newFolder("e2e-sys-state"),
            transport = transport,
            timeSource = time,
            systemStateControllerFactory = fakeSystemStateFactory,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            enablePeriodicTicker = false,
            sleeper = {},
        )

        val startRes = engine.startSessionAwait("synthetic-api-key")
        assertTrue(startRes is SessionStartResult.Started)
        assertEquals(1, startCount)
        assertEquals(0, stopCount)

        // Verify live SdkTelemetrySnapshot reflects Schema v3, hardware sensors, and initial SDK state
        val initialSnap = engine.statusFlow.value
        assertEquals(3, initialSnap.schemaVersion)
        assertTrue(initialSnap.hasGyroscope)
        assertTrue(initialSnap.hasMagnetometer)
        assertEquals(0.75, initialSnap.screenBrightness!!, 1e-9)
        assertEquals(false, initialSnap.screenLocked)
        assertEquals(1, initialSnap.stateVersion)

        // Game sets an attribute in Batch 1 -> order 2, version 2
        time.elapsedNanos += 100_000_000L
        engine.setAttribute("level", Value.Text("2"))

        // Flush Batch 1 and drain
        time.elapsedNanos += 5_000_000_000L
        engine.flushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
        engine.drainQueueNow()

        assertEquals(1, uploadedDecodedBatches.size)
        val batch1 = uploadedDecodedBatches[0]
        assertEquals(3, batch1.header?.schemaVersion)
        assertEquals(2, batch1.stateRecords.size)
        assertEquals(1, batch1.stateRecords[0].order)
        assertEquals(1, batch1.stateRecords[0].version)
        assertEquals(StateKind.STATE_KIND_PATCH, batch1.stateRecords[0].kind)
        assertEquals(
            linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.75),
                SdkStateKeys.SCREEN_LOCKED to Value.Flag(false),
            ),
            batch1.stateRecords[0].values,
        )
        assertEquals(2, batch1.stateRecords[1].order)
        assertEquals(2, batch1.stateRecords[1].version)
        assertEquals(mapOf("level" to Value.Text("2")), batch1.stateRecords[1].values)

        // Batch 2: screen locks, then game calls clearAttributes() -> PUT must preserve nx10_* keys!
        time.elapsedNanos += 100_000_000L
        capturedCallback!!.onSystemStateObserved(screenBrightness = 0.75, screenLocked = true)
        assertEquals(true, engine.statusFlow.value.screenLocked)

        time.elapsedNanos += 100_000_000L
        engine.clearAttributes()

        // Flush Batch 2 and drain
        time.elapsedNanos += 5_000_000_000L
        engine.flushWindowNow(CloseReason.CLOSE_REASON_WINDOW_FULL, isFullWindowDuration = false)
        engine.drainQueueNow()

        assertEquals(2, uploadedDecodedBatches.size)
        val batch2 = uploadedDecodedBatches[1]
        // Batch 2 opening Carried.state matches end of Batch 1
        assertEquals(2, batch2.carried?.state?.version)
        assertEquals(Value.Number(0.75), batch2.carried?.state?.values?.get(SdkStateKeys.SCREEN_BRIGHTNESS))
        assertEquals(Value.Flag(false), batch2.carried?.state?.values?.get(SdkStateKeys.SCREEN_LOCKED))
        assertEquals(Value.Text("2"), batch2.carried?.state?.values?.get("level"))

        // Batch 2 state records: (1) PATCH for nx10_screen_locked=true, (2) PUT preserving both nx10_* keys without "level"
        assertEquals(2, batch2.stateRecords.size)
        assertEquals(StateKind.STATE_KIND_PATCH, batch2.stateRecords[0].kind)
        assertEquals(mapOf(SdkStateKeys.SCREEN_LOCKED to Value.Flag(true)), batch2.stateRecords[0].values)
        assertEquals(StateKind.STATE_KIND_PUT, batch2.stateRecords[1].kind)
        assertEquals(
            linkedMapOf(
                SdkStateKeys.SCREEN_BRIGHTNESS to Value.Number(0.75),
                SdkStateKeys.SCREEN_LOCKED to Value.Flag(true),
            ),
            batch2.stateRecords[1].values,
        )

        // Short background + foreground (ContinueSession) calls onResumed() and syncs brightness -> 0.80
        engine.onAppBackgroundedAwait()
        time.elapsedNanos += 5_000_000_000L
        time.wallMs += 5_000L
        val decision = engine.onAppForegroundedAwait()
        assertTrue(decision is ResumeDecision.ContinueSession)
        assertEquals(1, resumedCount)
        assertEquals(0.80, engine.statusFlow.value.screenBrightness!!, 1e-9)
        assertEquals(true, engine.statusFlow.value.screenLocked)

        // Stopping session cleanly stops the system state controller
        engine.stopSessionAwait()
        assertEquals(1, stopCount)
    }
}
