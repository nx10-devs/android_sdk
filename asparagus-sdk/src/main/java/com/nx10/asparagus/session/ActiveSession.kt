package com.nx10.asparagus.session

import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.ModelReference
import com.nx10.asparagus.contract.ServerAnchor
import com.nx10.asparagus.time.SessionClock

/**
 * Validated runtime session state established by session start.
 */
data class ActiveSession(
    val idOrg: String,
    val idDeployment: String,
    val idSource: String,
    val idSession: String,
    val token: String,
    val tokenExpiresUtcMs: Long,
    val batchEndpoint: String,
    val accessRequestEndpoint: String,
    val forgetRequestEndpoint: String,
    val settings: SdkSettings,
    val models: List<ModelReference>,
    val serverAnchor: ServerAnchor,
    val collectionEnabled: Boolean,
    val clock: SessionClock,
    val settingsLabel: String = "",
) {
    fun isTokenExpired(): Boolean = clock.isTokenExpired(tokenExpiresUtcMs)
}
