package com.nx10.asparagus.batch

import com.nx10.asparagus.capture.motion.MotionWindowCapture
import com.nx10.asparagus.capture.touch.ScreenChangeTracker
import com.nx10.asparagus.capture.touch.TouchWindowTracker
import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.CoordinateOrigin
import com.nx10.asparagus.contract.DeviceProperties
import com.nx10.asparagus.contract.GapCause
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.Settings
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.device.AppVersionInfo
import com.nx10.asparagus.device.DeviceInfoSource
import com.nx10.asparagus.events.SessionEventStateTracker
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.lifecycle.LifecycleAndTelemetryTracker
import com.nx10.asparagus.session.ActiveSession
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.SessionClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchBoundsAndAssemblerTest {

    private class FakeTimeSource(
        var elapsedNanos: Long = 50_000_000_000L,
        var wallMs: Long = 1_795_000_000_250L,
        var offsetMinutes: Int = 60,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = offsetMinutes
    }

    private class FakeDeviceInfo : DeviceInfoSource {
        override fun deviceIdentifier(): String = "synthetic-device-id"
        override fun deviceProperties(): DeviceProperties = DeviceProperties(
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

        override fun screenRecord(timeUs: Long): ScreenRecord = ScreenRecord(
            timeUs = timeUs,
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420f,
            orientation = Orientation.ORIENTATION_PORTRAIT,
            origin = CoordinateOrigin.COORDINATE_ORIGIN_TOP_LEFT,
        )

        override fun hasGyroscope(): Boolean = true
        override fun appVersionInfo(): AppVersionInfo = AppVersionInfo("1.0", "1")
    }

    private fun buildAssembler(
        time: FakeTimeSource = FakeTimeSource(),
        settingsOverride: Settings? = null,
    ): AssemblerFixture {
        val settings = SdkSettings.loadDefaults().merge(settingsOverride)
        val clock = SessionClock(timeSource = time, appStartElapsedNanos = time.elapsedNanos)
        val anchor = clock.anchor(serverUtcMs = time.wallMs, roundTripMs = 30, nativeSensorRunning = false)
        val session = ActiveSession(
            idOrg = "made-up-org-1",
            idDeployment = "made-up-dep-1",
            idSource = "made-up-src-1",
            idSession = "made-up-ses-1",
            token = "synthetic-token",
            tokenExpiresUtcMs = time.wallMs + 86_400_000L,
            batchEndpoint = "https://intake.stage.example/asparagus/v1/batches",
            accessRequestEndpoint = "",
            forgetRequestEndpoint = "",
            settings = settings,
            models = emptyList(),
            serverAnchor = anchor,
            collectionEnabled = true,
            clock = clock,
        )
        val deviceInfo = FakeDeviceInfo()
        val diagnostics = DiagnosticsAggregator(clock)
        val motion = MotionWindowCapture(settings = settings, hasGyroscope = true, hasGravity = true)
        val touch = TouchWindowTracker(settings = settings)
        val screen = ScreenChangeTracker(deviceInfo.screenRecord(0L))
        val events = SessionEventStateTracker(sessionClock = clock, diagnostics = diagnostics)
        val lifecycle = LifecycleAndTelemetryTracker(clock)
        val assembler = BatchAssembler(
            session = session,
            deviceInfo = deviceInfo,
            motionCapture = motion,
            touchTracker = touch,
            screenTracker = screen,
            eventStateTracker = events,
            lifecycleTracker = lifecycle,
            diagnosticsAggregator = diagnostics,
        )
        return AssemblerFixture(time, session, motion, touch, screen, events, lifecycle, diagnostics, assembler)
    }

    private data class AssemblerFixture(
        val time: FakeTimeSource,
        val session: ActiveSession,
        val motion: MotionWindowCapture,
        val touch: TouchWindowTracker,
        val screen: ScreenChangeTracker,
        val events: SessionEventStateTracker,
        val lifecycle: LifecycleAndTelemetryTracker,
        val diagnostics: DiagnosticsAggregator,
        val assembler: BatchAssembler,
    )

    @Test
    fun `gzipCodec produces 1f 8b magic bytes and round-trips accurately`() {
        val raw = "Asparagus telemetry payload Café 小店 🛒".repeat(50).toByteArray(Charsets.UTF_8)
        val gz = GzipCodec.gzip(raw)
        assertTrue(GzipCodec.isGzipped(gz))
        assertFalse(GzipCodec.isGzipped(raw))
        assertTrue(gz.size < raw.size)
        assertArrayEquals(raw, GzipCodec.gunzip(gz))
    }

    @Test
    fun `validateHeader catches every one of the 6 header rules`() {
        val valid = Header(
            idOrg = "org",
            idDeployment = "dep",
            idSource = "src",
            idSession = "ses",
            sequence = 1L,
            schemaVersion = ASPARAGUS_SCHEMA_VERSION,
            windowStartUs = 1_795_000_000_000_000L,
            windowLengthS = 10,
            osVersion = "15",
            deviceModel = "Phone",
            settingsVersion = "v1",
        )
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid).isEmpty())

        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(idOrg = "")).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(idSession = "x".repeat(1025))).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(sequence = 0L)).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(schemaVersion = 0)).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(windowStartUs = 1_795_000_000_000_500L)).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(windowLengthS = 0)).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(windowLengthS = 151)).isNotEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(valid.copy(deviceModel = "m".repeat(1025))).isNotEmpty())
    }

    @Test
    fun `assembler populates sensorUs when CLOCK_SENSOR samples arrive mid-window and embeds dropped summary`() {
        val f = buildAssembler()
        // Note: motion.isNativeSensorRunning was false when window opened -> openingClocks.sensorUs was null!
        f.motion.ringBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 50_010_000L, 0.1f, 0.2f, 9.8f)
        f.motion.ringBuffer.push(Sensor.SENSOR_ACCELEROMETER.protoNumber, 50_020_000L, 0.2f, 0.1f, 9.8f)

        f.time.elapsedNanos += 10_000_000_000L
        assertEquals(WindowCheckResult.CloseFullWindow, f.assembler.shouldCloseWindowEarly())

        val dropped = DroppedBatchesSummary(
            count = 3,
            firstDroppedSequence = 1L,
            startTimeUs = 1_794_999_900_000_000L,
            endTimeUs = 1_794_999_930_000_000L,
        )
        val prepared = f.assembler.closeWindow(CloseReason.CLOSE_REASON_WINDOW_FULL, droppedSummary = dropped)
        assertEquals(1, prepared.size)
        val p = prepared.single()

        assertTrue(BatchBoundsAndHeaderValidator.validateHeader(p.batch.header).isEmpty())
        assertTrue(BatchBoundsAndHeaderValidator.validateBounds(p.batch, p.rawProtoBytes, p.wireBytes).isEmpty())
        assertNotNull("clocks.sensorUs must be backfilled for CLOCK_SENSOR blocks", p.batch.header?.clocks?.sensorUs)
        assertEquals(3, p.batch.header?.batchesDropped)
        assertEquals(GapCause.GAP_CAUSE_BATCHES_DROPPED, p.batch.gaps.first().cause)
        assertEquals(3, p.batch.gaps.first().batchesDropped)
        assertTrue(GzipCodec.isGzipped(p.wireBytes))

        // Verify wire bytes unzip and decode back to identical Batch
        val decoded = BatchProtoCodec.decode(GzipCodec.gunzip(p.wireBytes))
        assertEquals(p.batch, decoded)
    }

    @Test
    fun `proactive early close and post-flush splitting keep 1600 events, 10000 motion samples, and over-262KB payloads inside W8-26 bounds`() {
        // 1. 1,600 events + interleaved state mutations -> split into <= 750 events per batch with accurate Carried & sequences
        val fEvents = buildAssembler(settingsOverride = Settings(compress = false))
        for (i in 1..1_600) {
            fEvents.time.elapsedNanos += 1_000_000L
            if (i == 500) {
                fEvents.events.setAttribute("milestone", Value.Text("halfway"))
            }
            fEvents.events.sendEvent("tick_$i", mapOf("i" to Value.Number(i.toDouble())))
        }
        assertTrue(fEvents.assembler.shouldCloseWindowEarly() is WindowCheckResult.CloseEarlyForBounds)

        val splitEventBatches = fEvents.assembler.closeWindow(CloseReason.CLOSE_REASON_WINDOW_FULL)
        assertTrue("1600 events must split into >= 3 batches", splitEventBatches.size >= 3)
        assertEquals((1L..splitEventBatches.size.toLong()).toList(), splitEventBatches.map { it.sequence })
        assertEquals(1_600, splitEventBatches.sumOf { it.batch.events.size })
        for (pb in splitEventBatches) {
            assertTrue(BatchBoundsAndHeaderValidator.validateHeader(pb.batch.header).isEmpty())
            assertTrue(BatchBoundsAndHeaderValidator.validateBounds(pb.batch, pb.rawProtoBytes, pb.wireBytes).isEmpty())
        }
        // Final chunk's Carried must reflect state mutation from i == 500
        assertEquals(
            Value.Text("halfway"),
            splitEventBatches.last().batch.carried?.state?.values?.get("milestone"),
        )

        // 2. 10,000 motion samples in a single SensorBlock -> split into valid batches <= 7,500 samples each
        val fMotion = buildAssembler(settingsOverride = Settings(holdRunLength = 0, compress = false))
        for (i in 0 until 10_000) {
            fMotion.motion.ringBuffer.push(
                Sensor.SENSOR_ACCELEROMETER.protoNumber,
                50_000_000L + i * 1_000L,
                (i % 17).toFloat() * 0.1f,
                (i % 13).toFloat() * -0.1f,
                9.8f,
            )
        }
        assertTrue(fMotion.assembler.shouldCloseWindowEarly() is WindowCheckResult.CloseEarlyForBounds)
        val splitMotionBatches = fMotion.assembler.closeWindow(CloseReason.CLOSE_REASON_WINDOW_FULL)
        assertTrue("10,000 motion samples must split into >= 2 batches", splitMotionBatches.size >= 2)
        assertEquals(10_000, splitMotionBatches.sumOf { BatchBoundsAndHeaderValidator.motionSampleCount(it.batch) })
        for (pb in splitMotionBatches) {
            assertTrue(BatchBoundsAndHeaderValidator.validateHeader(pb.batch.header).isEmpty())
            assertTrue(BatchBoundsAndHeaderValidator.validateBounds(pb.batch, pb.rawProtoBytes, pb.wireBytes).isEmpty())
            // Verify every split SensorBlock satisfies delta & timeStepsUs invariants
            for (block in pb.batch.sensorBlocks) {
                assertEquals(block.x.size, block.y.size)
                assertEquals(block.x.size, block.z.size)
                assertEquals(maxOf(0, block.x.size - 1), block.timeStepsUs.size)
            }
        }

        // 3. Large text payloads (> 262,144 bytes unzipped) -> split so rawProtoBytes <= 262,144 on every batch
        val fBytes = buildAssembler(settingsOverride = Settings(compress = true))
        val bigText = "A".repeat(4_000)
        for (i in 1..100) { // ~400 KB raw protobuf > 262,144
            fBytes.events.sendEvent("big_$i", mapOf("blob" to Value.Text(bigText)))
        }
        val splitByteBatches = fBytes.assembler.closeWindow(CloseReason.CLOSE_REASON_WINDOW_FULL)
        assertTrue("400 KB raw batch must split into >= 2 batches", splitByteBatches.size >= 2)
        assertEquals(100, splitByteBatches.sumOf { it.batch.events.size })
        for (pb in splitByteBatches) {
            assertTrue(pb.rawProtoBytes.size <= BatchBoundsAndHeaderValidator.MAX_UNZIPPED_BATCH_BYTES)
            assertTrue(pb.wireBytes.size <= BatchBoundsAndHeaderValidator.MAX_UPLOAD_BYTES)
            assertTrue(BatchBoundsAndHeaderValidator.validateBounds(pb.batch, pb.rawProtoBytes, pb.wireBytes).isEmpty())
        }
    }
}
