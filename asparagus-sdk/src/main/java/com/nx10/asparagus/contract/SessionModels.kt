package com.nx10.asparagus.contract

/**
 * What one install of one game has collected before this session (`asparagus_contract.v1.CollectionHistory`).
 */
data class CollectionHistory(
    val sessionsBefore: Int = 0,
    val collectedSecondsBefore: Long = 0L,
    val longestSessionSecondsBefore: Int = 0,
) {
    val hasAnyHistory: Boolean
        get() = sessionsBefore > 0 || collectedSecondsBefore > 0L || longestSessionSecondsBefore > 0
}

/**
 * Request body for `POST /routes/asparagus/sessions/start` (`asparagus_contract.v1.SessionStartRequest`).
 */
data class SessionStartRequest(
    val apiKey: String = "",
    val deviceIdentifier: String = "",
    val sdkVersion: SdkVersion? = null,
    val platform: Platform = Platform.PLATFORM_UNSPECIFIED,
    val device: DeviceProperties? = null,
    val appVersion: String = "",
    val buildNumber: String = "",
    val sessionProperties: Map<String, Value> = emptyMap(),
    val demo: Boolean = false,
    val history: CollectionHistory? = null,
)

/**
 * Hardware/OS description of the device (`asparagus_contract.v1.DeviceProperties`).
 * Never holds the device's personal name.
 */
data class DeviceProperties(
    val maker: String = "",
    val model: String = "",
    val osVersion: String = "",
    val deviceType: String = "",
    val screenWidthPx: Int = 0,
    val screenHeightPx: Int = 0,
    val screenDensityDpi: Float = 0f,
    val hasGyroscope: Boolean = false,
    val timezone: String = "",
    val locale: String = "",
)

/**
 * Session start response payload under `"data"` (`asparagus_contract.v1.SessionStartResponse`).
 */
data class SessionStartResponse(
    val idOrg: String = "",
    val idDeployment: String = "",
    val idSource: String = "",
    val idSession: String = "",
    val token: String = "",
    val tokenExpiresUtcMs: Long = 0L,
    val serverUtcMs: Long = 0L,
    val batchEndpoint: String = "",
    val accessRequestEndpoint: String = "",
    val forgetRequestEndpoint: String = "",
    val settings: Settings? = null,
    val models: List<ModelReference> = emptyList(),
    val settingsLabel: String = "",
)

sealed class MotionSource(val protoNumber: Int, val protoName: String) {
    data object MOTION_SOURCE_UNSPECIFIED : MotionSource(0, "MOTION_SOURCE_UNSPECIFIED")
    data object MOTION_SOURCE_NATIVE : MotionSource(1, "MOTION_SOURCE_NATIVE")
    data object MOTION_SOURCE_UNITY : MotionSource(2, "MOTION_SOURCE_UNITY")
    data class Unknown(val number: Int) : MotionSource(number, "UNKNOWN_$number")

    companion object {
        val knownEntries: List<MotionSource> by lazy {
            listOf(
                MOTION_SOURCE_UNSPECIFIED,
                MOTION_SOURCE_NATIVE,
                MOTION_SOURCE_UNITY,
            )
        }

        fun fromProtoNumber(number: Int): MotionSource = when (number) {
            0 -> MOTION_SOURCE_UNSPECIFIED
            1 -> MOTION_SOURCE_NATIVE
            2 -> MOTION_SOURCE_UNITY
            else -> Unknown(number)
        }

        fun fromProtoName(name: String): MotionSource? = when (name) {
            "MOTION_SOURCE_UNSPECIFIED" -> MOTION_SOURCE_UNSPECIFIED
            "MOTION_SOURCE_NATIVE" -> MOTION_SOURCE_NATIVE
            "MOTION_SOURCE_UNITY" -> MOTION_SOURCE_UNITY
            else -> null
        }
    }
}

/**
 * Wire settings from `session.proto`.
 * Every `optional` field (`2..25`) is nullable so omitted (`null`) is distinguished from explicit `0`/`false`.
 */
data class Settings(
    val version: String = "",
    val accelerometerHz: Int? = null,
    val gyroscopeHz: Int? = null,
    val gravityHz: Int? = null,
    val touchHz: Int? = null,
    val motionSource: MotionSource? = null,
    val nativeBufferS: Int? = null,
    val nativeStartLimitMs: Int? = null,
    val nativeStallLimitMs: Int? = null,
    val accelerometerHoldTolerance: Int? = null,
    val gyroscopeHoldTolerance: Int? = null,
    val gravityHoldTolerance: Int? = null,
    val touchHoldTolerance: Int? = null,
    val holdRunLength: Int? = null,
    val gapThresholdMs: Int? = null,
    val gapThresholdPeriods: Int? = null,
    val batchLengthS: Int? = null,
    val compress: Boolean? = null,
    val queueMaxBytes: Int? = null,
    val queueMaxAgeS: Int? = null,
    val retryFirstWaitMs: Int? = null,
    val retryLongestWaitMs: Int? = null,
    val pauseTouchOnKeyboard: Boolean? = null,
    val tokenLifeS: Int? = null,
    val newSessionAfterS: Int? = null,
    val magnetometerHz: Int? = null,
    val magnetometerHoldTolerance: Int? = null,
)

data class ModelReference(
    val purpose: String = "",
    val name: String = "",
    val version: String = "",
    val downloadUrl: String = "",
    val arguments: List<String> = emptyList(),
)

/**
 * Control Plane HTTP envelope for `POST /routes/asparagus/sessions/start`.
 */
data class SessionStartEnvelope(
    val status: String = "",
    val data: SessionStartResponse? = null,
    val errors: List<String> = emptyList(),
)
