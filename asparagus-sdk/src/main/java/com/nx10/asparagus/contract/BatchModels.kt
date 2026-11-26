package com.nx10.asparagus.contract

/**
 * Contract schema version matching `asparagus-contract-v3` (`batch.proto`).
 */
const val ASPARAGUS_SCHEMA_VERSION: Int = 3

/**
 * Everything one device recorded in one window (`asparagus_contract.v1.Batch`).
 */
data class Batch(
    val header: Header? = null,
    val sensorBlocks: List<SensorBlock> = emptyList(),
    val touch: Touch? = null,
    val events: List<GameEvent> = emptyList(),
    val stateRecords: List<StateRecord> = emptyList(),
    val lifecycle: List<LifecycleRecord> = emptyList(),
    val gaps: List<GapRecord> = emptyList(),
    val screenChanges: List<ScreenRecord> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val carried: Carried? = null,
)

data class Header(
    val idOrg: String = "",
    val idDeployment: String = "",
    val idSource: String = "",
    val idSession: String = "",
    val sequence: Long = 0L,
    val schemaVersion: Int = 0,
    val sdkVersion: SdkVersion? = null,
    val platform: Platform = Platform.PLATFORM_UNSPECIFIED,
    val osVersion: String = "",
    val deviceModel: String = "",
    val settingsVersion: String = "",
    val windowStartUs: Long = 0L,
    val windowLengthS: Int = 0,
    val closedUs: Long = 0L,
    val closeReason: CloseReason = CloseReason.CLOSE_REASON_UNSPECIFIED,
    val clocks: ClockReading? = null,
    val serverAnchor: ServerAnchor? = null,
    val screen: ScreenRecord? = null,
    val hasGyroscope: Boolean = false,
    val batchesDropped: Int = 0,
    val utcOffsetMinutes: Int? = null,
)

data class SdkVersion(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
)

sealed class Platform(val protoNumber: Int, val protoName: String) {
    data object PLATFORM_UNSPECIFIED : Platform(0, "PLATFORM_UNSPECIFIED")
    data object PLATFORM_ANDROID : Platform(1, "PLATFORM_ANDROID")
    data object PLATFORM_IOS : Platform(2, "PLATFORM_IOS")
    data object PLATFORM_EDITOR : Platform(3, "PLATFORM_EDITOR")
    data object PLATFORM_OTHER : Platform(4, "PLATFORM_OTHER")
    data class Unknown(val number: Int) : Platform(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<Platform> by lazy {
            listOf(
                PLATFORM_UNSPECIFIED,
                PLATFORM_ANDROID,
                PLATFORM_IOS,
                PLATFORM_EDITOR,
                PLATFORM_OTHER,
            )
        }

        fun fromProtoNumber(number: Int): Platform = when (number) {
            0 -> PLATFORM_UNSPECIFIED
            1 -> PLATFORM_ANDROID
            2 -> PLATFORM_IOS
            3 -> PLATFORM_EDITOR
            4 -> PLATFORM_OTHER
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): Platform? = when (name) {
            "PLATFORM_UNSPECIFIED" -> PLATFORM_UNSPECIFIED
            "PLATFORM_ANDROID" -> PLATFORM_ANDROID
            "PLATFORM_IOS" -> PLATFORM_IOS
            "PLATFORM_EDITOR" -> PLATFORM_EDITOR
            "PLATFORM_OTHER" -> PLATFORM_OTHER
            else -> null
        }
    }
}

sealed class CloseReason(val protoNumber: Int, val protoName: String) {
    data object CLOSE_REASON_UNSPECIFIED : CloseReason(0, "CLOSE_REASON_UNSPECIFIED")
    data object CLOSE_REASON_WINDOW_FULL : CloseReason(1, "CLOSE_REASON_WINDOW_FULL")
    data object CLOSE_REASON_PAUSED : CloseReason(2, "CLOSE_REASON_PAUSED")
    data object CLOSE_REASON_QUIT : CloseReason(3, "CLOSE_REASON_QUIT")
    data object CLOSE_REASON_CAPTURE_STOPPED : CloseReason(4, "CLOSE_REASON_CAPTURE_STOPPED")
    data object CLOSE_REASON_SESSION_ENDED : CloseReason(5, "CLOSE_REASON_SESSION_ENDED")
    data class Unknown(val number: Int) : CloseReason(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<CloseReason> by lazy {
            listOf(
                CLOSE_REASON_UNSPECIFIED,
                CLOSE_REASON_WINDOW_FULL,
                CLOSE_REASON_PAUSED,
                CLOSE_REASON_QUIT,
                CLOSE_REASON_CAPTURE_STOPPED,
                CLOSE_REASON_SESSION_ENDED,
            )
        }

        fun fromProtoNumber(number: Int): CloseReason = when (number) {
            0 -> CLOSE_REASON_UNSPECIFIED
            1 -> CLOSE_REASON_WINDOW_FULL
            2 -> CLOSE_REASON_PAUSED
            3 -> CLOSE_REASON_QUIT
            4 -> CLOSE_REASON_CAPTURE_STOPPED
            5 -> CLOSE_REASON_SESSION_ENDED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): CloseReason? = when (name) {
            "CLOSE_REASON_UNSPECIFIED" -> CLOSE_REASON_UNSPECIFIED
            "CLOSE_REASON_WINDOW_FULL" -> CLOSE_REASON_WINDOW_FULL
            "CLOSE_REASON_PAUSED" -> CLOSE_REASON_PAUSED
            "CLOSE_REASON_QUIT" -> CLOSE_REASON_QUIT
            "CLOSE_REASON_CAPTURE_STOPPED" -> CLOSE_REASON_CAPTURE_STOPPED
            "CLOSE_REASON_SESSION_ENDED" -> CLOSE_REASON_SESSION_ENDED
            else -> null
        }
    }
}

sealed class Clock(val protoNumber: Int, val protoName: String) {
    data object CLOCK_UNSPECIFIED : Clock(0, "CLOCK_UNSPECIFIED")
    data object CLOCK_SESSION : Clock(1, "CLOCK_SESSION")
    data object CLOCK_UNITY : Clock(2, "CLOCK_UNITY")
    data object CLOCK_SENSOR : Clock(3, "CLOCK_SENSOR")
    data class Unknown(val number: Int) : Clock(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<Clock> by lazy {
            listOf(
                CLOCK_UNSPECIFIED,
                CLOCK_SESSION,
                CLOCK_UNITY,
                CLOCK_SENSOR,
            )
        }

        fun fromProtoNumber(number: Int): Clock = when (number) {
            0 -> CLOCK_UNSPECIFIED
            1 -> CLOCK_SESSION
            2 -> CLOCK_UNITY
            3 -> CLOCK_SENSOR
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): Clock? = when (name) {
            "CLOCK_UNSPECIFIED" -> CLOCK_UNSPECIFIED
            "CLOCK_SESSION" -> CLOCK_SESSION
            "CLOCK_UNITY" -> CLOCK_UNITY
            "CLOCK_SENSOR" -> CLOCK_SENSOR
            else -> null
        }
    }
}

data class ClockReading(
    val sessionUs: Long = 0L,
    val unityUs: Long = 0L,
    val sensorUs: Long? = null,
    val wallUtcMs: Long = 0L,
)

data class ServerAnchor(
    val serverUtcMs: Long = 0L,
    val atReceipt: ClockReading? = null,
    val roundTripMs: Int = 0,
)

sealed class Sensor(val protoNumber: Int, val protoName: String) {
    data object SENSOR_UNSPECIFIED : Sensor(0, "SENSOR_UNSPECIFIED")
    data object SENSOR_ACCELEROMETER : Sensor(1, "SENSOR_ACCELEROMETER")
    data object SENSOR_GYROSCOPE : Sensor(2, "SENSOR_GYROSCOPE")
    data object SENSOR_GRAVITY : Sensor(3, "SENSOR_GRAVITY")
    data object SENSOR_MAGNETOMETER : Sensor(4, "SENSOR_MAGNETOMETER")
    data class Unknown(val number: Int) : Sensor(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<Sensor> by lazy {
            listOf(
                SENSOR_UNSPECIFIED,
                SENSOR_ACCELEROMETER,
                SENSOR_GYROSCOPE,
                SENSOR_GRAVITY,
                SENSOR_MAGNETOMETER,
            )
        }

        fun fromProtoNumber(number: Int): Sensor = when (number) {
            0 -> SENSOR_UNSPECIFIED
            1 -> SENSOR_ACCELEROMETER
            2 -> SENSOR_GYROSCOPE
            3 -> SENSOR_GRAVITY
            4 -> SENSOR_MAGNETOMETER
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): Sensor? = when (name) {
            "SENSOR_UNSPECIFIED" -> SENSOR_UNSPECIFIED
            "SENSOR_ACCELEROMETER" -> SENSOR_ACCELEROMETER
            "SENSOR_GYROSCOPE" -> SENSOR_GYROSCOPE
            "SENSOR_GRAVITY" -> SENSOR_GRAVITY
            "SENSOR_MAGNETOMETER" -> SENSOR_MAGNETOMETER
            else -> null
        }
    }
}

sealed class CaptureSource(val protoNumber: Int, val protoName: String) {
    data object CAPTURE_SOURCE_UNSPECIFIED : CaptureSource(0, "CAPTURE_SOURCE_UNSPECIFIED")
    data object CAPTURE_SOURCE_NATIVE_ANDROID : CaptureSource(1, "CAPTURE_SOURCE_NATIVE_ANDROID")
    data object CAPTURE_SOURCE_NATIVE_IOS : CaptureSource(2, "CAPTURE_SOURCE_NATIVE_IOS")
    data object CAPTURE_SOURCE_UNITY_EVENTS : CaptureSource(3, "CAPTURE_SOURCE_UNITY_EVENTS")
    data object CAPTURE_SOURCE_UNITY_POLLED : CaptureSource(4, "CAPTURE_SOURCE_UNITY_POLLED")
    data class Unknown(val number: Int) : CaptureSource(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<CaptureSource> by lazy {
            listOf(
                CAPTURE_SOURCE_UNSPECIFIED,
                CAPTURE_SOURCE_NATIVE_ANDROID,
                CAPTURE_SOURCE_NATIVE_IOS,
                CAPTURE_SOURCE_UNITY_EVENTS,
                CAPTURE_SOURCE_UNITY_POLLED,
            )
        }

        fun fromProtoNumber(number: Int): CaptureSource = when (number) {
            0 -> CAPTURE_SOURCE_UNSPECIFIED
            1 -> CAPTURE_SOURCE_NATIVE_ANDROID
            2 -> CAPTURE_SOURCE_NATIVE_IOS
            3 -> CAPTURE_SOURCE_UNITY_EVENTS
            4 -> CAPTURE_SOURCE_UNITY_POLLED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): CaptureSource? = when (name) {
            "CAPTURE_SOURCE_UNSPECIFIED" -> CAPTURE_SOURCE_UNSPECIFIED
            "CAPTURE_SOURCE_NATIVE_ANDROID" -> CAPTURE_SOURCE_NATIVE_ANDROID
            "CAPTURE_SOURCE_NATIVE_IOS" -> CAPTURE_SOURCE_NATIVE_IOS
            "CAPTURE_SOURCE_UNITY_EVENTS" -> CAPTURE_SOURCE_UNITY_EVENTS
            "CAPTURE_SOURCE_UNITY_POLLED" -> CAPTURE_SOURCE_UNITY_POLLED
            else -> null
        }
    }
}

sealed class FallbackReason(val protoNumber: Int, val protoName: String) {
    data object FALLBACK_REASON_UNSPECIFIED : FallbackReason(0, "FALLBACK_REASON_UNSPECIFIED")
    data object FALLBACK_REASON_PLUGIN_MISSING : FallbackReason(1, "FALLBACK_REASON_PLUGIN_MISSING")
    data object FALLBACK_REASON_SENSOR_REFUSED : FallbackReason(2, "FALLBACK_REASON_SENSOR_REFUSED")
    data object FALLBACK_REASON_NO_FIRST_SAMPLE : FallbackReason(3, "FALLBACK_REASON_NO_FIRST_SAMPLE")
    data object FALLBACK_REASON_SAMPLES_STOPPED : FallbackReason(4, "FALLBACK_REASON_SAMPLES_STOPPED")
    data object FALLBACK_REASON_NATIVE_ERROR : FallbackReason(5, "FALLBACK_REASON_NATIVE_ERROR")
    data object FALLBACK_REASON_FORCED_BY_SETTINGS : FallbackReason(6, "FALLBACK_REASON_FORCED_BY_SETTINGS")
    data class Unknown(val number: Int) : FallbackReason(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<FallbackReason> by lazy {
            listOf(
                FALLBACK_REASON_UNSPECIFIED,
                FALLBACK_REASON_PLUGIN_MISSING,
                FALLBACK_REASON_SENSOR_REFUSED,
                FALLBACK_REASON_NO_FIRST_SAMPLE,
                FALLBACK_REASON_SAMPLES_STOPPED,
                FALLBACK_REASON_NATIVE_ERROR,
                FALLBACK_REASON_FORCED_BY_SETTINGS,
            )
        }

        fun fromProtoNumber(number: Int): FallbackReason = when (number) {
            0 -> FALLBACK_REASON_UNSPECIFIED
            1 -> FALLBACK_REASON_PLUGIN_MISSING
            2 -> FALLBACK_REASON_SENSOR_REFUSED
            3 -> FALLBACK_REASON_NO_FIRST_SAMPLE
            4 -> FALLBACK_REASON_SAMPLES_STOPPED
            5 -> FALLBACK_REASON_NATIVE_ERROR
            6 -> FALLBACK_REASON_FORCED_BY_SETTINGS
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): FallbackReason? = when (name) {
            "FALLBACK_REASON_UNSPECIFIED" -> FALLBACK_REASON_UNSPECIFIED
            "FALLBACK_REASON_PLUGIN_MISSING" -> FALLBACK_REASON_PLUGIN_MISSING
            "FALLBACK_REASON_SENSOR_REFUSED" -> FALLBACK_REASON_SENSOR_REFUSED
            "FALLBACK_REASON_NO_FIRST_SAMPLE" -> FALLBACK_REASON_NO_FIRST_SAMPLE
            "FALLBACK_REASON_SAMPLES_STOPPED" -> FALLBACK_REASON_SAMPLES_STOPPED
            "FALLBACK_REASON_NATIVE_ERROR" -> FALLBACK_REASON_NATIVE_ERROR
            "FALLBACK_REASON_FORCED_BY_SETTINGS" -> FALLBACK_REASON_FORCED_BY_SETTINGS
            else -> null
        }
    }
}

sealed class ValueUnit(val protoNumber: Int, val protoName: String) {
    data object VALUE_UNIT_UNSPECIFIED : ValueUnit(0, "VALUE_UNIT_UNSPECIFIED")
    data object VALUE_UNIT_METRES_PER_SECOND_SQUARED : ValueUnit(1, "VALUE_UNIT_METRES_PER_SECOND_SQUARED")
    data object VALUE_UNIT_G : ValueUnit(2, "VALUE_UNIT_G")
    data object VALUE_UNIT_RADIANS_PER_SECOND : ValueUnit(3, "VALUE_UNIT_RADIANS_PER_SECOND")
    data object VALUE_UNIT_MICROTESLA : ValueUnit(4, "VALUE_UNIT_MICROTESLA")
    data class Unknown(val number: Int) : ValueUnit(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<ValueUnit> by lazy {
            listOf(
                VALUE_UNIT_UNSPECIFIED,
                VALUE_UNIT_METRES_PER_SECOND_SQUARED,
                VALUE_UNIT_G,
                VALUE_UNIT_RADIANS_PER_SECOND,
                VALUE_UNIT_MICROTESLA,
            )
        }

        fun fromProtoNumber(number: Int): ValueUnit = when (number) {
            0 -> VALUE_UNIT_UNSPECIFIED
            1 -> VALUE_UNIT_METRES_PER_SECOND_SQUARED
            2 -> VALUE_UNIT_G
            3 -> VALUE_UNIT_RADIANS_PER_SECOND
            4 -> VALUE_UNIT_MICROTESLA
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): ValueUnit? = when (name) {
            "VALUE_UNIT_UNSPECIFIED" -> VALUE_UNIT_UNSPECIFIED
            "VALUE_UNIT_METRES_PER_SECOND_SQUARED" -> VALUE_UNIT_METRES_PER_SECOND_SQUARED
            "VALUE_UNIT_G" -> VALUE_UNIT_G
            "VALUE_UNIT_RADIANS_PER_SECOND" -> VALUE_UNIT_RADIANS_PER_SECOND
            "VALUE_UNIT_MICROTESLA" -> VALUE_UNIT_MICROTESLA
            else -> null
        }
    }
}

data class SensorBlock(
    val sensor: Sensor = Sensor.SENSOR_UNSPECIFIED,
    val source: CaptureSource = CaptureSource.CAPTURE_SOURCE_UNSPECIFIED,
    val fallbackReason: FallbackReason = FallbackReason.FALLBACK_REASON_UNSPECIFIED,
    val clock: Clock = Clock.CLOCK_UNSPECIFIED,
    val unit: ValueUnit = ValueUnit.VALUE_UNIT_UNSPECIFIED,
    val scale: Double = 0.0,
    val requestedHz: Float = 0f,
    val sampleCount: Int = 0,
    val longestGapUs: Int = 0,
    val firstTimeUs: Long = 0L,
    val timeStepsUs: List<Int> = emptyList(),
    val x: List<Int> = emptyList(),
    val y: List<Int> = emptyList(),
    val z: List<Int> = emptyList(),
    val holds: List<Hold> = emptyList(),
)

data class Hold(
    val startTimeUs: Long = 0L,
    val endTimeUs: Long = 0L,
    val sampleCount: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0,
    val stats: List<HoldStats> = emptyList(),
)

data class HoldStats(
    val sum: Long = 0L,
    val sumOfSquares: Double = 0.0,
    val minimum: Int = 0,
    val maximum: Int = 0,
)

sealed class TouchMode(val protoNumber: Int, val protoName: String) {
    data object TOUCH_MODE_UNSPECIFIED : TouchMode(0, "TOUCH_MODE_UNSPECIFIED")
    data object TOUCH_MODE_EVENTS : TouchMode(1, "TOUCH_MODE_EVENTS")
    data object TOUCH_MODE_EVENTS_MERGED : TouchMode(2, "TOUCH_MODE_EVENTS_MERGED")
    data object TOUCH_MODE_POLLED : TouchMode(3, "TOUCH_MODE_POLLED")
    data class Unknown(val number: Int) : TouchMode(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<TouchMode> by lazy {
            listOf(
                TOUCH_MODE_UNSPECIFIED,
                TOUCH_MODE_EVENTS,
                TOUCH_MODE_EVENTS_MERGED,
                TOUCH_MODE_POLLED,
            )
        }

        fun fromProtoNumber(number: Int): TouchMode = when (number) {
            0 -> TOUCH_MODE_UNSPECIFIED
            1 -> TOUCH_MODE_EVENTS
            2 -> TOUCH_MODE_EVENTS_MERGED
            3 -> TOUCH_MODE_POLLED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): TouchMode? = when (name) {
            "TOUCH_MODE_UNSPECIFIED" -> TOUCH_MODE_UNSPECIFIED
            "TOUCH_MODE_EVENTS" -> TOUCH_MODE_EVENTS
            "TOUCH_MODE_EVENTS_MERGED" -> TOUCH_MODE_EVENTS_MERGED
            "TOUCH_MODE_POLLED" -> TOUCH_MODE_POLLED
            else -> null
        }
    }
}

data class Touch(
    val mode: TouchMode = TouchMode.TOUCH_MODE_UNSPECIFIED,
    val requestedHz: Float = 0f,
    val positionScale: Double = 0.0,
    val sizeScale: Double = 0.0,
    val pressureScale: Double = 0.0,
    val contacts: List<TouchContact> = emptyList(),
)

sealed class TouchEnd(val protoNumber: Int, val protoName: String) {
    data object TOUCH_END_UNSPECIFIED : TouchEnd(0, "TOUCH_END_UNSPECIFIED")
    data object TOUCH_END_STILL_DOWN : TouchEnd(1, "TOUCH_END_STILL_DOWN")
    data object TOUCH_END_LIFTED : TouchEnd(2, "TOUCH_END_LIFTED")
    data object TOUCH_END_CANCELLED : TouchEnd(3, "TOUCH_END_CANCELLED")
    data class Unknown(val number: Int) : TouchEnd(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<TouchEnd> by lazy {
            listOf(
                TOUCH_END_UNSPECIFIED,
                TOUCH_END_STILL_DOWN,
                TOUCH_END_LIFTED,
                TOUCH_END_CANCELLED,
            )
        }

        fun fromProtoNumber(number: Int): TouchEnd = when (number) {
            0 -> TOUCH_END_UNSPECIFIED
            1 -> TOUCH_END_STILL_DOWN
            2 -> TOUCH_END_LIFTED
            3 -> TOUCH_END_CANCELLED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): TouchEnd? = when (name) {
            "TOUCH_END_UNSPECIFIED" -> TOUCH_END_UNSPECIFIED
            "TOUCH_END_STILL_DOWN" -> TOUCH_END_STILL_DOWN
            "TOUCH_END_LIFTED" -> TOUCH_END_LIFTED
            "TOUCH_END_CANCELLED" -> TOUCH_END_CANCELLED
            else -> null
        }
    }
}

data class TouchContact(
    val fingerId: Int = 0,
    val continued: Boolean = false,
    val end: TouchEnd = TouchEnd.TOUCH_END_UNSPECIFIED,
    val firstTimeUs: Long = 0L,
    val timeStepsUs: List<Int> = emptyList(),
    val x: List<Int> = emptyList(),
    val y: List<Int> = emptyList(),
    val sizeMajor: List<Int> = emptyList(),
    val sizeMinor: List<Int> = emptyList(),
    val pressure: List<Int> = emptyList(),
    val holds: List<TouchHold> = emptyList(),
    val eventsSeen: Int = 0,
    val before: ContactBefore? = null,
)

data class ContactBefore(
    val downTimeUs: Long = 0L,
    val minX: Int = 0,
    val maxX: Int = 0,
    val minY: Int = 0,
    val maxY: Int = 0,
)

data class TouchHold(
    val startTimeUs: Long = 0L,
    val endTimeUs: Long = 0L,
    val sampleCount: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val sizeMajor: Int = 0,
    val sizeMinor: Int = 0,
    val pressure: Int = 0,
    val stats: List<HoldStats> = emptyList(),
)

sealed class Orientation(val protoNumber: Int, val protoName: String) {
    data object ORIENTATION_UNSPECIFIED : Orientation(0, "ORIENTATION_UNSPECIFIED")
    data object ORIENTATION_PORTRAIT : Orientation(1, "ORIENTATION_PORTRAIT")
    data object ORIENTATION_PORTRAIT_UPSIDE_DOWN : Orientation(2, "ORIENTATION_PORTRAIT_UPSIDE_DOWN")
    data object ORIENTATION_LANDSCAPE_LEFT : Orientation(3, "ORIENTATION_LANDSCAPE_LEFT")
    data object ORIENTATION_LANDSCAPE_RIGHT : Orientation(4, "ORIENTATION_LANDSCAPE_RIGHT")
    data class Unknown(val number: Int) : Orientation(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<Orientation> by lazy {
            listOf(
                ORIENTATION_UNSPECIFIED,
                ORIENTATION_PORTRAIT,
                ORIENTATION_PORTRAIT_UPSIDE_DOWN,
                ORIENTATION_LANDSCAPE_LEFT,
                ORIENTATION_LANDSCAPE_RIGHT,
            )
        }

        fun fromProtoNumber(number: Int): Orientation = when (number) {
            0 -> ORIENTATION_UNSPECIFIED
            1 -> ORIENTATION_PORTRAIT
            2 -> ORIENTATION_PORTRAIT_UPSIDE_DOWN
            3 -> ORIENTATION_LANDSCAPE_LEFT
            4 -> ORIENTATION_LANDSCAPE_RIGHT
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): Orientation? = when (name) {
            "ORIENTATION_UNSPECIFIED" -> ORIENTATION_UNSPECIFIED
            "ORIENTATION_PORTRAIT" -> ORIENTATION_PORTRAIT
            "ORIENTATION_PORTRAIT_UPSIDE_DOWN" -> ORIENTATION_PORTRAIT_UPSIDE_DOWN
            "ORIENTATION_LANDSCAPE_LEFT" -> ORIENTATION_LANDSCAPE_LEFT
            "ORIENTATION_LANDSCAPE_RIGHT" -> ORIENTATION_LANDSCAPE_RIGHT
            else -> null
        }
    }
}

sealed class CoordinateOrigin(val protoNumber: Int, val protoName: String) {
    data object COORDINATE_ORIGIN_UNSPECIFIED : CoordinateOrigin(0, "COORDINATE_ORIGIN_UNSPECIFIED")
    data object COORDINATE_ORIGIN_BOTTOM_LEFT : CoordinateOrigin(1, "COORDINATE_ORIGIN_BOTTOM_LEFT")
    data object COORDINATE_ORIGIN_TOP_LEFT : CoordinateOrigin(2, "COORDINATE_ORIGIN_TOP_LEFT")
    data class Unknown(val number: Int) : CoordinateOrigin(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<CoordinateOrigin> by lazy {
            listOf(
                COORDINATE_ORIGIN_UNSPECIFIED,
                COORDINATE_ORIGIN_BOTTOM_LEFT,
                COORDINATE_ORIGIN_TOP_LEFT,
            )
        }

        fun fromProtoNumber(number: Int): CoordinateOrigin = when (number) {
            0 -> COORDINATE_ORIGIN_UNSPECIFIED
            1 -> COORDINATE_ORIGIN_BOTTOM_LEFT
            2 -> COORDINATE_ORIGIN_TOP_LEFT
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): CoordinateOrigin? = when (name) {
            "COORDINATE_ORIGIN_UNSPECIFIED" -> COORDINATE_ORIGIN_UNSPECIFIED
            "COORDINATE_ORIGIN_BOTTOM_LEFT" -> COORDINATE_ORIGIN_BOTTOM_LEFT
            "COORDINATE_ORIGIN_TOP_LEFT" -> COORDINATE_ORIGIN_TOP_LEFT
            else -> null
        }
    }
}

data class ScreenRecord(
    val timeUs: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val densityDpi: Float = 0f,
    val orientation: Orientation = Orientation.ORIENTATION_UNSPECIFIED,
    val origin: CoordinateOrigin = CoordinateOrigin.COORDINATE_ORIGIN_UNSPECIFIED,
)

/**
 * Text, a number, or true or false. With nothing set it is [Empty] (`{}`).
 */
sealed interface Value {
    data class Text(val value: String) : Value
    data class Number(val value: Double) : Value
    data class Flag(val value: Boolean) : Value
    data object Empty : Value
}

sealed class SpanMarker(val protoNumber: Int, val protoName: String) {
    data object SPAN_MARKER_UNSPECIFIED : SpanMarker(0, "SPAN_MARKER_UNSPECIFIED")
    data object SPAN_MARKER_OPEN : SpanMarker(1, "SPAN_MARKER_OPEN")
    data object SPAN_MARKER_CLOSE : SpanMarker(2, "SPAN_MARKER_CLOSE")
    data class Unknown(val number: Int) : SpanMarker(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<SpanMarker> by lazy {
            listOf(
                SPAN_MARKER_UNSPECIFIED,
                SPAN_MARKER_OPEN,
                SPAN_MARKER_CLOSE,
            )
        }

        fun fromProtoNumber(number: Int): SpanMarker = when (number) {
            0 -> SPAN_MARKER_UNSPECIFIED
            1 -> SPAN_MARKER_OPEN
            2 -> SPAN_MARKER_CLOSE
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): SpanMarker? = when (name) {
            "SPAN_MARKER_UNSPECIFIED" -> SPAN_MARKER_UNSPECIFIED
            "SPAN_MARKER_OPEN" -> SPAN_MARKER_OPEN
            "SPAN_MARKER_CLOSE" -> SPAN_MARKER_CLOSE
            else -> null
        }
    }
}

data class GameEvent(
    val timeUs: Long = 0L,
    val name: String = "",
    val fields: Map<String, Value> = emptyMap(),
    val outcome: String? = null,
    val spanName: String = "",
    val spanMarker: SpanMarker = SpanMarker.SPAN_MARKER_UNSPECIFIED,
    val spanNumber: Int = 0,
    val order: Int = 0,
)

sealed class StateKind(val protoNumber: Int, val protoName: String) {
    data object STATE_KIND_UNSPECIFIED : StateKind(0, "STATE_KIND_UNSPECIFIED")
    data object STATE_KIND_PUT : StateKind(1, "STATE_KIND_PUT")
    data object STATE_KIND_PATCH : StateKind(2, "STATE_KIND_PATCH")
    data class Unknown(val number: Int) : StateKind(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<StateKind> by lazy {
            listOf(
                STATE_KIND_UNSPECIFIED,
                STATE_KIND_PUT,
                STATE_KIND_PATCH,
            )
        }

        fun fromProtoNumber(number: Int): StateKind = when (number) {
            0 -> STATE_KIND_UNSPECIFIED
            1 -> STATE_KIND_PUT
            2 -> STATE_KIND_PATCH
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): StateKind? = when (name) {
            "STATE_KIND_UNSPECIFIED" -> STATE_KIND_UNSPECIFIED
            "STATE_KIND_PUT" -> STATE_KIND_PUT
            "STATE_KIND_PATCH" -> STATE_KIND_PATCH
            else -> null
        }
    }
}

data class StateRecord(
    val timeUs: Long = 0L,
    val kind: StateKind = StateKind.STATE_KIND_UNSPECIFIED,
    val values: Map<String, Value> = emptyMap(),
    val removedKeys: List<String> = emptyList(),
    val order: Int = 0,
    val version: Int = 0,
)

data class Carried(
    val state: State? = null,
    val openSpans: OpenSpans? = null,
    val lastEventTimeUs: Long? = null,
)

data class State(
    val values: Map<String, Value> = emptyMap(),
    val version: Int = 0,
)

data class OpenSpans(
    val spans: List<OpenSpan> = emptyList(),
)

data class OpenSpan(
    val name: String = "",
    val number: Int = 0,
    val openedTimeUs: Long = 0L,
)

sealed class LifecycleKind(val protoNumber: Int, val protoName: String) {
    data object LIFECYCLE_KIND_UNSPECIFIED : LifecycleKind(0, "LIFECYCLE_KIND_UNSPECIFIED")
    data object LIFECYCLE_KIND_PAUSED : LifecycleKind(1, "LIFECYCLE_KIND_PAUSED")
    data object LIFECYCLE_KIND_RESUMED : LifecycleKind(2, "LIFECYCLE_KIND_RESUMED")
    data object LIFECYCLE_KIND_FOCUS_LOST : LifecycleKind(3, "LIFECYCLE_KIND_FOCUS_LOST")
    data object LIFECYCLE_KIND_FOCUS_GAINED : LifecycleKind(4, "LIFECYCLE_KIND_FOCUS_GAINED")
    data object LIFECYCLE_KIND_QUITTING : LifecycleKind(5, "LIFECYCLE_KIND_QUITTING")
    data object LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME : LifecycleKind(6, "LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME")
    data object LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME : LifecycleKind(7, "LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME")
    data object LIFECYCLE_KIND_CLOCK_STEPPED : LifecycleKind(8, "LIFECYCLE_KIND_CLOCK_STEPPED")
    data class Unknown(val number: Int) : LifecycleKind(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<LifecycleKind> by lazy {
            listOf(
                LIFECYCLE_KIND_UNSPECIFIED,
                LIFECYCLE_KIND_PAUSED,
                LIFECYCLE_KIND_RESUMED,
                LIFECYCLE_KIND_FOCUS_LOST,
                LIFECYCLE_KIND_FOCUS_GAINED,
                LIFECYCLE_KIND_QUITTING,
                LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME,
                LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME,
                LIFECYCLE_KIND_CLOCK_STEPPED,
            )
        }

        fun fromProtoNumber(number: Int): LifecycleKind = when (number) {
            0 -> LIFECYCLE_KIND_UNSPECIFIED
            1 -> LIFECYCLE_KIND_PAUSED
            2 -> LIFECYCLE_KIND_RESUMED
            3 -> LIFECYCLE_KIND_FOCUS_LOST
            4 -> LIFECYCLE_KIND_FOCUS_GAINED
            5 -> LIFECYCLE_KIND_QUITTING
            6 -> LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME
            7 -> LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME
            8 -> LIFECYCLE_KIND_CLOCK_STEPPED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): LifecycleKind? = when (name) {
            "LIFECYCLE_KIND_UNSPECIFIED" -> LIFECYCLE_KIND_UNSPECIFIED
            "LIFECYCLE_KIND_PAUSED" -> LIFECYCLE_KIND_PAUSED
            "LIFECYCLE_KIND_RESUMED" -> LIFECYCLE_KIND_RESUMED
            "LIFECYCLE_KIND_FOCUS_LOST" -> LIFECYCLE_KIND_FOCUS_LOST
            "LIFECYCLE_KIND_FOCUS_GAINED" -> LIFECYCLE_KIND_FOCUS_GAINED
            "LIFECYCLE_KIND_QUITTING" -> LIFECYCLE_KIND_QUITTING
            "LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME" -> LIFECYCLE_KIND_CAPTURE_STOPPED_BY_GAME
            "LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME" -> LIFECYCLE_KIND_CAPTURE_STARTED_BY_GAME
            "LIFECYCLE_KIND_CLOCK_STEPPED" -> LIFECYCLE_KIND_CLOCK_STEPPED
            else -> null
        }
    }
}

data class LifecycleRecord(
    val timeUs: Long = 0L,
    val kind: LifecycleKind = LifecycleKind.LIFECYCLE_KIND_UNSPECIFIED,
    val clockStepS: Long = 0L,
)

sealed class GapSignal(val protoNumber: Int, val protoName: String) {
    data object GAP_SIGNAL_UNSPECIFIED : GapSignal(0, "GAP_SIGNAL_UNSPECIFIED")
    data object GAP_SIGNAL_ACCELEROMETER : GapSignal(1, "GAP_SIGNAL_ACCELEROMETER")
    data object GAP_SIGNAL_GYROSCOPE : GapSignal(2, "GAP_SIGNAL_GYROSCOPE")
    data object GAP_SIGNAL_GRAVITY : GapSignal(3, "GAP_SIGNAL_GRAVITY")
    data object GAP_SIGNAL_TOUCH : GapSignal(4, "GAP_SIGNAL_TOUCH")
    data object GAP_SIGNAL_EVERYTHING : GapSignal(5, "GAP_SIGNAL_EVERYTHING")
    data object GAP_SIGNAL_MAGNETOMETER : GapSignal(6, "GAP_SIGNAL_MAGNETOMETER")
    data class Unknown(val number: Int) : GapSignal(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<GapSignal> by lazy {
            listOf(
                GAP_SIGNAL_UNSPECIFIED,
                GAP_SIGNAL_ACCELEROMETER,
                GAP_SIGNAL_GYROSCOPE,
                GAP_SIGNAL_GRAVITY,
                GAP_SIGNAL_TOUCH,
                GAP_SIGNAL_EVERYTHING,
                GAP_SIGNAL_MAGNETOMETER,
            )
        }

        fun fromProtoNumber(number: Int): GapSignal = when (number) {
            0 -> GAP_SIGNAL_UNSPECIFIED
            1 -> GAP_SIGNAL_ACCELEROMETER
            2 -> GAP_SIGNAL_GYROSCOPE
            3 -> GAP_SIGNAL_GRAVITY
            4 -> GAP_SIGNAL_TOUCH
            5 -> GAP_SIGNAL_EVERYTHING
            6 -> GAP_SIGNAL_MAGNETOMETER
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): GapSignal? = when (name) {
            "GAP_SIGNAL_UNSPECIFIED" -> GAP_SIGNAL_UNSPECIFIED
            "GAP_SIGNAL_ACCELEROMETER" -> GAP_SIGNAL_ACCELEROMETER
            "GAP_SIGNAL_GYROSCOPE" -> GAP_SIGNAL_GYROSCOPE
            "GAP_SIGNAL_GRAVITY" -> GAP_SIGNAL_GRAVITY
            "GAP_SIGNAL_TOUCH" -> GAP_SIGNAL_TOUCH
            "GAP_SIGNAL_EVERYTHING" -> GAP_SIGNAL_EVERYTHING
            "GAP_SIGNAL_MAGNETOMETER" -> GAP_SIGNAL_MAGNETOMETER
            else -> null
        }
    }
}

sealed class GapCause(val protoNumber: Int, val protoName: String) {
    data object GAP_CAUSE_UNSPECIFIED : GapCause(0, "GAP_CAUSE_UNSPECIFIED")
    data object GAP_CAUSE_SENSOR_SILENT : GapCause(1, "GAP_CAUSE_SENSOR_SILENT")
    data object GAP_CAUSE_BUFFER_OVERFLOWED : GapCause(2, "GAP_CAUSE_BUFFER_OVERFLOWED")
    data object GAP_CAUSE_BATCHES_DROPPED : GapCause(3, "GAP_CAUSE_BATCHES_DROPPED")
    data object GAP_CAUSE_CAPTURE_STOPPED : GapCause(4, "GAP_CAUSE_CAPTURE_STOPPED")
    data class Unknown(val number: Int) : GapCause(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<GapCause> by lazy {
            listOf(
                GAP_CAUSE_UNSPECIFIED,
                GAP_CAUSE_SENSOR_SILENT,
                GAP_CAUSE_BUFFER_OVERFLOWED,
                GAP_CAUSE_BATCHES_DROPPED,
                GAP_CAUSE_CAPTURE_STOPPED,
            )
        }

        fun fromProtoNumber(number: Int): GapCause = when (number) {
            0 -> GAP_CAUSE_UNSPECIFIED
            1 -> GAP_CAUSE_SENSOR_SILENT
            2 -> GAP_CAUSE_BUFFER_OVERFLOWED
            3 -> GAP_CAUSE_BATCHES_DROPPED
            4 -> GAP_CAUSE_CAPTURE_STOPPED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): GapCause? = when (name) {
            "GAP_CAUSE_UNSPECIFIED" -> GAP_CAUSE_UNSPECIFIED
            "GAP_CAUSE_SENSOR_SILENT" -> GAP_CAUSE_SENSOR_SILENT
            "GAP_CAUSE_BUFFER_OVERFLOWED" -> GAP_CAUSE_BUFFER_OVERFLOWED
            "GAP_CAUSE_BATCHES_DROPPED" -> GAP_CAUSE_BATCHES_DROPPED
            "GAP_CAUSE_CAPTURE_STOPPED" -> GAP_CAUSE_CAPTURE_STOPPED
            else -> null
        }
    }
}

data class GapRecord(
    val signal: GapSignal = GapSignal.GAP_SIGNAL_UNSPECIFIED,
    val clock: Clock = Clock.CLOCK_UNSPECIFIED,
    val startTimeUs: Long = 0L,
    val endTimeUs: Long = 0L,
    val cause: GapCause = GapCause.GAP_CAUSE_UNSPECIFIED,
    val batchesDropped: Int = 0,
    val firstDroppedSequence: Long = 0L,
)

sealed class Severity(val protoNumber: Int, val protoName: String) {
    data object SEVERITY_UNSPECIFIED : Severity(0, "SEVERITY_UNSPECIFIED")
    data object SEVERITY_WARNING : Severity(1, "SEVERITY_WARNING")
    data object SEVERITY_ERROR : Severity(2, "SEVERITY_ERROR")
    data class Unknown(val number: Int) : Severity(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<Severity> by lazy {
            listOf(
                SEVERITY_UNSPECIFIED,
                SEVERITY_WARNING,
                SEVERITY_ERROR,
            )
        }

        fun fromProtoNumber(number: Int): Severity = when (number) {
            0 -> SEVERITY_UNSPECIFIED
            1 -> SEVERITY_WARNING
            2 -> SEVERITY_ERROR
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): Severity? = when (name) {
            "SEVERITY_UNSPECIFIED" -> SEVERITY_UNSPECIFIED
            "SEVERITY_WARNING" -> SEVERITY_WARNING
            "SEVERITY_ERROR" -> SEVERITY_ERROR
            else -> null
        }
    }
}

data class Diagnostic(
    val timeUs: Long = 0L,
    val severity: Severity = Severity.SEVERITY_UNSPECIFIED,
    val code: String = "",
    val detail: String = "",
    val count: Int = 0,
)

sealed class UploadStatus(val protoNumber: Int, val protoName: String) {
    data object UPLOAD_STATUS_UNSPECIFIED : UploadStatus(0, "UPLOAD_STATUS_UNSPECIFIED")
    data object UPLOAD_STATUS_STORED : UploadStatus(1, "UPLOAD_STATUS_STORED")
    data class Unknown(val number: Int) : UploadStatus(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<UploadStatus> by lazy {
            listOf(
                UPLOAD_STATUS_UNSPECIFIED,
                UPLOAD_STATUS_STORED,
            )
        }

        fun fromProtoNumber(number: Int): UploadStatus = when (number) {
            0 -> UPLOAD_STATUS_UNSPECIFIED
            1 -> UPLOAD_STATUS_STORED
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): UploadStatus? = when (name) {
            "UPLOAD_STATUS_UNSPECIFIED" -> UPLOAD_STATUS_UNSPECIFIED
            "UPLOAD_STATUS_STORED" -> UPLOAD_STATUS_STORED
            else -> null
        }
    }
}

data class UploadResponse(
    val status: UploadStatus = UploadStatus.UPLOAD_STATUS_UNSPECIFIED,
)
