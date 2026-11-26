package com.nx10.asparagus.internal.codec

import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.CaptureSource
import com.nx10.asparagus.contract.Carried
import com.nx10.asparagus.contract.Clock
import com.nx10.asparagus.contract.ClockReading
import com.nx10.asparagus.contract.CloseReason
import com.nx10.asparagus.contract.ContactBefore
import com.nx10.asparagus.contract.CoordinateOrigin
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
import com.nx10.asparagus.contract.OpenSpan
import com.nx10.asparagus.contract.OpenSpans
import com.nx10.asparagus.contract.Orientation
import com.nx10.asparagus.contract.Platform
import com.nx10.asparagus.contract.ScreenRecord
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.contract.Sensor
import com.nx10.asparagus.contract.SensorBlock
import com.nx10.asparagus.contract.ServerAnchor
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
import com.nx10.asparagus.contract.UploadResponse
import com.nx10.asparagus.contract.UploadStatus
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.contract.ValueUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchProtoCodecTest {

    @Test
    fun `full-format batch matching batch_schema_2 round-trips identically`() {
        val original = buildFullFormatBatch()
        val encoded = BatchProtoCodec.encode(original)
        val decoded = BatchProtoCodec.decode(encoded)

        assertEquals(original, decoded)
        assertEquals(9007199254740993L, decoded.header?.clocks?.sensorUs)
        assertEquals(Int.MIN_VALUE, decoded.sensorBlocks[0].x[2])
        assertEquals(Int.MAX_VALUE, decoded.sensorBlocks[0].x[3])
        assertEquals(Value.Text("Café 小店 🛒"), decoded.events[2].fields["button"])
        assertEquals(Value.Empty, decoded.events[0].fields["bonus"])

        // Also verify against the workspace specs/batch_schema_2.json fixture if available
        val fixture = java.io.File("../../specs/batch_schema_2.json")
        if (fixture.isFile) {
            val fromFixture = JsonCodec.decodeBatchFromJson(fixture.readText(Charsets.UTF_8))
            assertEquals(original, fromFixture)
            val roundTripped = BatchProtoCodec.decode(BatchProtoCodec.encode(fromFixture))
            assertEquals(fromFixture, roundTripped)
        }
    }

    @Test
    fun `sint32 zigzag encodes Int MIN_VALUE and MAX_VALUE in 5 bytes without 64-bit sign extension`() {
        val writerMin = ProtoWriter()
        writerMin.writeRawSInt32(Int.MIN_VALUE)
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0x0F.toByte(),
            ),
            writerMin.toByteArray(),
        )
        assertEquals(Int.MIN_VALUE, ProtoReader(writerMin.toByteArray()).readSInt32())

        val writerMax = ProtoWriter()
        writerMax.writeRawSInt32(Int.MAX_VALUE)
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0x0F.toByte(),
            ),
            writerMax.toByteArray(),
        )
        assertEquals(Int.MAX_VALUE, ProtoReader(writerMax.toByteArray()).readSInt32())
    }

    @Test
    fun `value oneof preserves explicit default values and empty value`() {
        val event = GameEvent(
            timeUs = 1234L,
            name = "edge_values",
            fields = linkedMapOf(
                "empty_text" to Value.Text(""),
                "zero_number" to Value.Number(0.0),
                "false_flag" to Value.Flag(false),
                "empty_val" to Value.Empty,
            ),
            order = 1,
        )
        val batch = Batch(events = listOf(event))
        val decoded = BatchProtoCodec.decode(BatchProtoCodec.encode(batch))
        val fields = decoded.events.single().fields

        assertEquals(Value.Text(""), fields["empty_text"])
        assertEquals(Value.Number(0.0), fields["zero_number"])
        assertEquals(Value.Flag(false), fields["false_flag"])
        assertEquals(Value.Empty, fields["empty_val"])
    }

    @Test
    fun `optional scalars distinguish explicit zero or empty string from null`() {
        val withExplicitDefaults = Batch(
            header = Header(
                utcOffsetMinutes = 0,
                clocks = ClockReading(sessionUs = 100L, unityUs = 50L, sensorUs = 0L, wallUtcMs = 1L),
            ),
            events = listOf(GameEvent(name = "e", outcome = "")),
            carried = Carried(lastEventTimeUs = 0L),
        )
        val decodedPresent = BatchProtoCodec.decode(BatchProtoCodec.encode(withExplicitDefaults))
        assertEquals(0, decodedPresent.header?.utcOffsetMinutes)
        assertEquals(0L, decodedPresent.header?.clocks?.sensorUs)
        assertEquals("", decodedPresent.events.single().outcome)
        assertEquals(0L, decodedPresent.carried?.lastEventTimeUs)

        val withNulls = Batch(
            header = Header(
                utcOffsetMinutes = null,
                clocks = ClockReading(sessionUs = 100L, unityUs = 50L, sensorUs = null, wallUtcMs = 1L),
            ),
            events = listOf(GameEvent(name = "e", outcome = null)),
            carried = Carried(lastEventTimeUs = null),
        )
        val decodedAbsent = BatchProtoCodec.decode(BatchProtoCodec.encode(withNulls))
        assertNull(decodedAbsent.header?.utcOffsetMinutes)
        assertNull(decodedAbsent.header?.clocks?.sensorUs)
        assertNull(decodedAbsent.events.single().outcome)
        assertNull(decodedAbsent.carried?.lastEventTimeUs)
    }

    @Test
    fun `present-and-empty submessages in Carried are preserved distinct from null`() {
        val presentEmpty = Batch(
            carried = Carried(
                state = State(values = emptyMap(), version = 0),
                openSpans = OpenSpans(spans = emptyList()),
                lastEventTimeUs = null,
            ),
        )
        val encoded = BatchProtoCodec.encode(presentEmpty)
        val decoded = BatchProtoCodec.decode(encoded)

        assertNotNull(decoded.carried)
        assertEquals(State(emptyMap(), 0), decoded.carried?.state)
        assertEquals(OpenSpans(emptyList()), decoded.carried?.openSpans)
        assertNull(decoded.carried?.lastEventTimeUs)

        val absentSubmessages = Batch(
            carried = Carried(state = null, openSpans = null, lastEventTimeUs = null),
        )
        val decodedAbsent = BatchProtoCodec.decode(BatchProtoCodec.encode(absentSubmessages))
        assertNotNull(decodedAbsent.carried)
        assertNull(decodedAbsent.carried?.state)
        assertNull(decodedAbsent.carried?.openSpans)
    }

    @Test
    fun `upload response encodes STORED as exact bytes 08 01 and ignores unknown fields`() {
        val bytes = BatchProtoCodec.encodeUploadResponse(UploadResponse(UploadStatus.UPLOAD_STATUS_STORED))
        assertArrayEquals(byteArrayOf(0x08, 0x01), bytes)

        val decoded = BatchProtoCodec.decodeUploadResponse(byteArrayOf(0x08, 0x01))
        assertEquals(UploadResponse(UploadStatus.UPLOAD_STATUS_STORED), decoded)

        // Append unknown fields across wire types 0, 1, 2, 5
        val extended = ProtoWriter()
        extended.writeEnum(1, UploadStatus.UPLOAD_STATUS_STORED.protoNumber)
        extended.writeUInt64(90, 123456L) // wire type 0
        extended.writeDouble(91, 3.14159) // wire type 1
        extended.writeString(92, "future-field") // wire type 2
        extended.writeFloat(93, 2.5f) // wire type 5

        val decodedExtended = BatchProtoCodec.decodeUploadResponse(extended.toByteArray())
        assertEquals(UploadStatus.UPLOAD_STATUS_STORED, decodedExtended.status)
    }

    @Test
    fun `decoder accepts both packed and unpacked repeated sint32`() {
        val unpackedBlock = ProtoWriter()
        unpackedBlock.writeEnum(1, Sensor.SENSOR_ACCELEROMETER.protoNumber)
        // Unpacked wire type 0 entries for field 12 (x)
        unpackedBlock.writeSInt32(12, 100, writeDefault = true)
        unpackedBlock.writeSInt32(12, -50, writeDefault = true)
        unpackedBlock.writeSInt32(12, Int.MIN_VALUE, writeDefault = true)
        unpackedBlock.writeSInt32(12, 0, writeDefault = true)

        val batchWriter = ProtoWriter()
        batchWriter.writeBytes(2, unpackedBlock.toByteArray(), writeEmpty = true)

        val decoded = BatchProtoCodec.decode(batchWriter.toByteArray())
        assertEquals(listOf(100, -50, Int.MIN_VALUE, 0), decoded.sensorBlocks.single().x)
    }

    @Test
    fun `unknown enum numbers are preserved through decode and re-encode`() {
        val batch = Batch(
            sensorBlocks = listOf(
                SensorBlock(
                    sensor = Sensor.Unknown(99),
                    source = CaptureSource.Unknown(42),
                    sampleCount = 1,
                ),
            ),
        )
        val encoded = BatchProtoCodec.encode(batch)
        val decoded = BatchProtoCodec.decode(encoded)

        assertEquals(Sensor.Unknown(99), decoded.sensorBlocks.single().sensor)
        assertEquals(99, decoded.sensorBlocks.single().sensor.protoNumber)
        assertEquals(CaptureSource.Unknown(42), decoded.sensorBlocks.single().source)
        assertTrue(BatchProtoCodec.encode(decoded).contentEquals(encoded))
    }

    private fun buildFullFormatBatch(): Batch = Batch(
        header = Header(
            idOrg = "made-up-org-1",
            idDeployment = "made-up-deployment-1",
            idSource = "made-up-device-1",
            idSession = "made-up-session-1",
            sequence = 214L,
            schemaVersion = ASPARAGUS_SCHEMA_VERSION,
            sdkVersion = SdkVersion(major = 1, minor = 2, patch = 3),
            platform = Platform.PLATFORM_ANDROID,
            osVersion = "15",
            deviceModel = "Made-up Phone 3",
            settingsVersion = "made-up-settings-9",
            windowStartUs = 1794736860000000L,
            windowLengthS = 10,
            closedUs = 1794736870000000L,
            closeReason = CloseReason.CLOSE_REASON_WINDOW_FULL,
            clocks = ClockReading(
                sessionUs = 1794736860000000L,
                unityUs = 2961250000L,
                sensorUs = 9007199254740993L,
                wallUtcMs = 1794737040412L,
            ),
            serverAnchor = ServerAnchor(
                serverUtcMs = 1794733920250L,
                atReceipt = ClockReading(
                    sessionUs = 1794733920250000L,
                    unityUs = 23500000L,
                    sensorUs = null,
                    wallUtcMs = 1794734100662L,
                ),
                roundTripMs = 84,
            ),
            screen = ScreenRecord(
                widthPx = 1080,
                heightPx = 2400,
                densityDpi = 420f,
                orientation = Orientation.ORIENTATION_PORTRAIT,
                origin = CoordinateOrigin.COORDINATE_ORIGIN_BOTTOM_LEFT,
            ),
            hasGyroscope = true,
            batchesDropped = 2,
            utcOffsetMinutes = -300,
        ),
        sensorBlocks = listOf(
            SensorBlock(
                sensor = Sensor.SENSOR_ACCELEROMETER,
                source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
                clock = Clock.CLOCK_SENSOR,
                unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
                scale = 1e-8,
                requestedHz = 100f,
                sampleCount = 1000,
                longestGapUs = 10012,
                firstTimeUs = 9007199254744993L,
                timeStepsUs = listOf(10012, 9988, 10004, 9996, 10000),
                x = listOf(1200000, 1072541824, -2147483648, 2147483647, -1033741823, -38500000),
                y = listOf(-800000, 250800000, -560000000, 500000000, -220000000, 29100000),
                z = listOf(980665000, 519335000, -1080000000, 890000000, -300000000, -29300000),
                holds = listOf(
                    Hold(
                        startTimeUs = 9007199254804993L,
                        endTimeUs = 9007199264734993L,
                        sampleCount = 994,
                        x = 1300000,
                        y = -700000,
                        z = 980660000,
                        stats = listOf(
                            HoldStats(1292200000L, 1689800000000000.0, 1100000, 1500000),
                            HoldStats(-695800000L, 497000000000000.0, -900000, -500000),
                            HoldStats(974776040000L, 9.559238813264e20, 980460000, 980860000),
                        ),
                    ),
                ),
            ),
            SensorBlock(
                sensor = Sensor.SENSOR_GYROSCOPE,
                source = CaptureSource.CAPTURE_SOURCE_UNITY_EVENTS,
                fallbackReason = FallbackReason.FALLBACK_REASON_SENSOR_REFUSED,
                clock = Clock.CLOCK_UNITY,
                unit = ValueUnit.VALUE_UNIT_RADIANS_PER_SECOND,
                scale = 0.000001,
                requestedHz = 100f,
                sampleCount = 4,
                longestGapUs = 10300,
                firstTimeUs = 2961255000L,
                timeStepsUs = listOf(9800, 10300, 9900),
                x = listOf(1200, -46200, 75000, -30500),
                y = listOf(-800, 22800, -37000, 15400),
                z = listOf(300, -9300, 15000, -6100),
            ),
            SensorBlock(
                sensor = Sensor.SENSOR_GRAVITY,
                source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
                clock = Clock.CLOCK_SENSOR,
                unit = ValueUnit.VALUE_UNIT_METRES_PER_SECOND_SQUARED,
                scale = 1e-8,
                requestedHz = 5f,
                sampleCount = 50,
                longestGapUs = 200000,
                holds = listOf(
                    Hold(
                        startTimeUs = 9007199254890993L,
                        endTimeUs = 9007199264690993L,
                        sampleCount = 50,
                        x = -1250000,
                        y = 830000,
                        z = 980664000,
                    ),
                ),
            ),
            SensorBlock(
                sensor = Sensor.SENSOR_MAGNETOMETER,
                source = CaptureSource.CAPTURE_SOURCE_NATIVE_ANDROID,
                clock = Clock.CLOCK_SENSOR,
                unit = ValueUnit.VALUE_UNIT_MICROTESLA,
                scale = 0.01,
                requestedHz = 10f,
                sampleCount = 3,
                longestGapUs = 100000,
                firstTimeUs = 9007199255740993L,
                timeStepsUs = listOf(100000, 100000),
                x = listOf(2000, 3, -2),
                y = listOf(-500, -1, 1),
                z = listOf(-4000, 2, 0),
            ),
        ),
        touch = Touch(
            mode = TouchMode.TOUCH_MODE_EVENTS,
            requestedHz = 60f,
            positionScale = 0.1,
            sizeScale = 0.01,
            pressureScale = 0.001,
            contacts = listOf(
                TouchContact(
                    fingerId = 1,
                    continued = true,
                    end = TouchEnd.TOUCH_END_LIFTED,
                    firstTimeUs = 2961252000L,
                    timeStepsUs = listOf(16600, 16700, 16700, 510000),
                    x = listOf(5400, 25, 45, 20, 0),
                    y = listOf(12000, -50, -110, -40, 0),
                    sizeMajor = listOf(1250, 10, -5, -5, -350),
                    sizeMinor = listOf(1100, -5, 5, 0, -300),
                    pressure = listOf(620, 20, 15, -5, -650),
                    holds = listOf(
                        TouchHold(
                            startTimeUs = 2961318700L,
                            endTimeUs = 2961803000L,
                            sampleCount = 30,
                            x = 5490,
                            y = 11800,
                            sizeMajor = 1250,
                            sizeMinor = 1100,
                            pressure = 650,
                            stats = listOf(
                                HoldStats(164790L, 905191650.0, 5490, 5496),
                                HoldStats(353880L, 4174368800.0, 11792, 11800),
                                HoldStats(37350L, 46501250.0, 1240, 1250),
                                HoldStats(33020L, 36344200.0, 1098, 1104),
                                HoldStats(19550L, 12741250.0, 645, 660),
                            ),
                        ),
                    ),
                    eventsSeen = 68,
                    before = ContactBefore(2961000000L, 5300, 5400, 12000, 12100),
                ),
                TouchContact(
                    fingerId = 2,
                    end = TouchEnd.TOUCH_END_STILL_DOWN,
                    firstTimeUs = 2971240000L,
                    x = listOf(8000),
                    y = listOf(3000),
                    sizeMajor = listOf(1300),
                    sizeMinor = listOf(1150),
                    pressure = listOf(600),
                    eventsSeen = 1,
                ),
            ),
        ),
        events = listOf(
            GameEvent(
                timeUs = 2962250000L,
                name = "game_level_succeeded",
                fields = linkedMapOf(
                    "level" to Value.Text("3"),
                    "score" to Value.Number(1250.5),
                    "perfect" to Value.Flag(true),
                    "bonus" to Value.Empty,
                ),
                outcome = "succeeded",
                spanName = "level",
                spanMarker = SpanMarker.SPAN_MARKER_CLOSE,
                spanNumber = 7,
                order = 1,
            ),
            GameEvent(
                timeUs = 2962251000L,
                name = "game_level_started",
                fields = linkedMapOf(
                    "level" to Value.Text("4"),
                    "attempt" to Value.Number(1.0),
                ),
                spanName = "level",
                spanMarker = SpanMarker.SPAN_MARKER_OPEN,
                spanNumber = 8,
                order = 3,
            ),
            GameEvent(
                timeUs = 2967250000L,
                name = "button_clicked",
                fields = linkedMapOf("button" to Value.Text("Café 小店 🛒")),
                order = 5,
            ),
        ),
        stateRecords = listOf(
            StateRecord(
                timeUs = 2962250400L,
                kind = StateKind.STATE_KIND_PATCH,
                values = linkedMapOf("level" to Value.Text("4")),
                removedKeys = listOf("boss"),
                order = 2,
                version = 13,
            ),
            StateRecord(
                timeUs = 2967250000L,
                kind = StateKind.STATE_KIND_PUT,
                values = linkedMapOf(
                    "level" to Value.Text("4"),
                    "screen" to Value.Text("shop"),
                    "lives" to Value.Number(3.0),
                ),
                order = 4,
                version = 14,
            ),
        ),
        lifecycle = listOf(
            LifecycleRecord(2961250000L, LifecycleKind.LIFECYCLE_KIND_CLOCK_STEPPED, 2L),
            LifecycleRecord(2968250000L, LifecycleKind.LIFECYCLE_KIND_FOCUS_LOST, 0L),
            LifecycleRecord(2968650000L, LifecycleKind.LIFECYCLE_KIND_FOCUS_GAINED, 0L),
        ),
        gaps = listOf(
            GapRecord(
                signal = GapSignal.GAP_SIGNAL_EVERYTHING,
                clock = Clock.CLOCK_SESSION,
                startTimeUs = 1794734320000000L,
                endTimeUs = 1794734340000000L,
                cause = GapCause.GAP_CAUSE_BATCHES_DROPPED,
                batchesDropped = 2,
                firstDroppedSequence = 41L,
            ),
            GapRecord(
                signal = GapSignal.GAP_SIGNAL_GYROSCOPE,
                clock = Clock.CLOCK_UNITY,
                startTimeUs = 2961285000L,
                endTimeUs = 2971250000L,
                cause = GapCause.GAP_CAUSE_SENSOR_SILENT,
            ),
            GapRecord(
                signal = GapSignal.GAP_SIGNAL_MAGNETOMETER,
                clock = Clock.CLOCK_SENSOR,
                startTimeUs = 9007199255940993L,
                endTimeUs = 9007199258940993L,
                cause = GapCause.GAP_CAUSE_SENSOR_SILENT,
            ),
        ),
        screenChanges = listOf(
            ScreenRecord(
                timeUs = 2967250000L,
                widthPx = 2400,
                heightPx = 1080,
                densityDpi = 420f,
                orientation = Orientation.ORIENTATION_LANDSCAPE_LEFT,
                origin = CoordinateOrigin.COORDINATE_ORIGIN_BOTTOM_LEFT,
            ),
        ),
        diagnostics = listOf(
            Diagnostic(
                timeUs = 2961535000L,
                severity = Severity.SEVERITY_WARNING,
                code = "sensor_silent",
                detail = "gyroscope: no sample for 250 ms",
                count = 1,
            ),
        ),
        carried = Carried(
            state = State(
                values = linkedMapOf(
                    "level" to Value.Text("3"),
                    "screen" to Value.Text("game"),
                    "boss" to Value.Flag(true),
                    "lives" to Value.Number(2.0),
                ),
                version = 12,
            ),
            openSpans = OpenSpans(
                spans = listOf(
                    OpenSpan(name = "level", number = 7, openedTimeUs = 2920250000L),
                ),
            ),
            lastEventTimeUs = 2955125000L,
        ),
    )
}
