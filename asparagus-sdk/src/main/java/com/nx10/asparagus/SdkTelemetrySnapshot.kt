package com.nx10.asparagus

import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.session.SessionStartClient

enum class SdkSessionState {
    IDLE,
    STARTING,
    ACTIVE,
    COLLECTION_DISABLED,
    REFUSED,
    STOPPED,
}

/**
 * Result for obsolete Turnip live-output calls (`RequestActivity`, `RequestAffect`) per
 * `02-unity-sdk.md` line 403 and Decision 79.
 */
sealed interface ObsoleteLiveOutputResult {
    data class NotAvailable(val reason: String = "not available") : ObsoleteLiveOutputResult
}

/**
 * Immutable snapshot of SDK session, window, disk queue, and upload telemetry for UI/diagnostics.
 * Never holds API keys, JWT tokens, or personal device names.
 */
data class SdkTelemetrySnapshot(
    val sessionState: SdkSessionState = SdkSessionState.IDLE,
    val useProduction: Boolean = false,
    val sessionStartEndpoint: String = SessionStartClient.STAGE_SESSION_START_ENDPOINT,
    val schemaVersion: Int = ASPARAGUS_SCHEMA_VERSION,
    val hasGyroscope: Boolean = false,
    val hasMagnetometer: Boolean = false,
    val screenBrightness: Double? = null,
    val screenLocked: Boolean? = null,
    val idOrg: String = "",
    val idDeployment: String = "",
    val idSource: String = "",
    val idSession: String = "",
    val batchEndpoint: String = "",
    val settingsVersion: String = "",
    val batchLengthS: Int = 0,
    val compress: Boolean = true,
    val collectionEnabled: Boolean = false,
    val telemetryEnabled: Boolean = false,
    val touchPaused: Boolean = false,
    val nextSequence: Long = 1L,
    val motionSamplesInWindow: Int = 0,
    val touchContactsInWindow: Int = 0,
    val touchSamplesInWindow: Int = 0,
    val eventsInWindow: Int = 0,
    val stateRecordsInWindow: Int = 0,
    val stateVersion: Int = 0,
    val openSpans: List<String> = emptyList(),
    val queuedBatchesOnDisk: Int = 0,
    val queuedBytesOnDisk: Long = 0L,
    val pendingDroppedBatches: Int = 0,
    val uploadedBatchesTotal: Int = 0,
    val refusedBatchesTotal: Int = 0,
    val recentLogs: List<String> = emptyList(),
)
