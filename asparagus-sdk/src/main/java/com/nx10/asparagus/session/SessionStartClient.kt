package com.nx10.asparagus.session

import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.Platform
import com.nx10.asparagus.contract.SdkVersion
import com.nx10.asparagus.contract.SessionStartRequest
import com.nx10.asparagus.contract.Value
import com.nx10.asparagus.device.DEFAULT_SDK_VERSION
import com.nx10.asparagus.device.DeviceInfoSource
import com.nx10.asparagus.internal.codec.JsonCodec
import com.nx10.asparagus.internal.codec.JsonParseException
import com.nx10.asparagus.internal.net.HttpRequest
import com.nx10.asparagus.internal.net.HttpTransport
import com.nx10.asparagus.internal.net.RetryBackoff
import com.nx10.asparagus.internal.net.UrlConnectionHttpTransport
import com.nx10.asparagus.internal.util.Utf8Bounds
import com.nx10.asparagus.time.AndroidTimeSource
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.SessionClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SessionStartResult {
    data class Started(val session: ActiveSession) : SessionStartResult
    data class Refused(
        val statusCode: Int?,
        val errors: List<String> = emptyList(),
        val message: String,
    ) : SessionStartResult
}

sealed interface ComplianceResult {
    data class Success(val statusCode: Int, val responseBody: String) : ComplianceResult
    data class Failure(val statusCode: Int?, val message: String) : ComplianceResult
    data class Unavailable(val reason: String) : ComplianceResult
}

/**
 * Control Plane client for `POST /routes/asparagus/sessions/start` and player compliance endpoints.
 */
class SessionStartClient(
    private val deviceInfo: DeviceInfoSource,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeSource: MonotonicTimeSource = AndroidTimeSource,
    private val defaults: SdkSettings = SdkSettings.loadDefaults(),
    private val retryBackoff: RetryBackoff = RetryBackoff(),
    private val sdkVersion: SdkVersion = DEFAULT_SDK_VERSION,
    private val appStartElapsedNanos: Long = timeSource.elapsedRealtimeNanos(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    private val refusalLock = Any()

    @Volatile
    private var latchedRefusal: SessionStartResult.Refused? = null

    val isRefusedInThisAppRun: Boolean
        get() = latchedRefusal != null

    /**
     * Builds the `SessionStartRequest` using [DeviceInfoSource] without exposing personal device names.
     */
    fun buildRequest(
        apiKey: String,
        sessionProperties: Map<String, Value> = emptyMap(),
        demo: Boolean = false,
    ): SessionStartRequest {
        val appVersionInfo = deviceInfo.appVersionInfo()
        return SessionStartRequest(
            apiKey = apiKey,
            deviceIdentifier = deviceInfo.deviceIdentifier(),
            sdkVersion = sdkVersion,
            platform = Platform.PLATFORM_ANDROID,
            device = deviceInfo.deviceProperties(),
            appVersion = appVersionInfo.appVersion,
            buildNumber = appVersionInfo.buildNumber,
            sessionProperties = sessionProperties,
            demo = demo,
        )
    }

    /**
     * Starts an asparagus session against the built-in Stage (default) or Production endpoint.
     *
     * Retry rules (`session.proto` lines 18-28):
     * - `200`: Starts session (merges settings, anchors clock, never inspects token).
     * - `408`, `429`, `5xx`, or network `IOException`: Retries with jittered built-in backoff waits.
     * - Any other answer: Refused permanently for this app run; subsequent calls make no network request.
     */
    suspend fun startSession(
        apiKey: String,
        useProduction: Boolean = false,
        sessionProperties: Map<String, Value> = emptyMap(),
        demo: Boolean = false,
        maxAttempts: Int = Int.MAX_VALUE,
    ): SessionStartResult {
        latchedRefusal?.let { return it }

        val url = sessionStartEndpoint(useProduction)
        val requestModel = buildRequest(
            apiKey = apiKey,
            sessionProperties = sessionProperties,
            demo = demo,
        )
        val payloadBytes = JsonCodec.encodeSessionStartRequest(requestModel).toByteArray(Charsets.UTF_8)
        val httpRequest = HttpRequest(
            url = url,
            method = "POST",
            headers = mapOf("content-type" to "application/json"),
            body = payloadBytes,
        )

        var attempt = 0
        while (attempt < maxAttempts) {
            latchedRefusal?.let { return it }

            val startNanos = timeSource.elapsedRealtimeNanos()
            val response = try {
                withContext(ioDispatcher) {
                    transport.execute(httpRequest)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioe: IOException) {
                if (attempt + 1 >= maxAttempts) {
                    return SessionStartResult.Refused(
                        statusCode = null,
                        message = "Session start network failure after ${attempt + 1} attempts: ${ioe.message}",
                    )
                }
                val waitMs = retryBackoff.computeDelayMs(
                    attempt = attempt,
                    firstWaitMs = defaults.retryFirstWaitMs,
                    longestWaitMs = defaults.retryLongestWaitMs,
                )
                delayFn(waitMs)
                attempt++
                continue
            } catch (t: Throwable) {
                return latchRefusal(
                    SessionStartResult.Refused(
                        statusCode = null,
                        message = "Unexpected session start failure: ${t.message}",
                    ),
                )
            }
            val roundTripMs = ((timeSource.elapsedRealtimeNanos() - startNanos) / 1_000_000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()

            val bodyText = response.body.toString(Charsets.UTF_8)
            when {
                response.statusCode == 200 -> {
                    val envelope = try {
                        JsonCodec.decodeSessionStartEnvelope(bodyText)
                    } catch (e: JsonParseException) {
                        return latchRefusal(
                            SessionStartResult.Refused(
                                statusCode = 200,
                                message = "Malformed session start response JSON: ${e.message}",
                            ),
                        )
                    }

                    val data = envelope.data
                    if (envelope.status != "success" || data == null) {
                        return latchRefusal(
                            SessionStartResult.Refused(
                                statusCode = 200,
                                errors = envelope.errors,
                                message = "Session start returned non-success status '${envelope.status}'",
                            ),
                        )
                    }

                    val idOrg = Utf8Bounds.truncateUtf8(data.idOrg)
                    val idDeployment = Utf8Bounds.truncateUtf8(data.idDeployment)
                    val idSource = Utf8Bounds.truncateUtf8(data.idSource)
                    val idSession = Utf8Bounds.truncateUtf8(data.idSession)
                    val collectionEnabled = data.batchEndpoint.isNotBlank() && !requestModel.demo

                    if (collectionEnabled && (idOrg.isEmpty() || idDeployment.isEmpty() || idSource.isEmpty() || idSession.isEmpty())) {
                        return latchRefusal(
                            SessionStartResult.Refused(
                                statusCode = 200,
                                message = "Session start response missing required header identifiers",
                            ),
                        )
                    }

                    val resolvedSettings = defaults.merge(data.settings)
                    val clock = SessionClock(
                        timeSource = timeSource,
                        appStartElapsedNanos = appStartElapsedNanos,
                    )
                    val anchor = clock.anchor(
                        serverUtcMs = data.serverUtcMs,
                        roundTripMs = roundTripMs,
                        nativeSensorRunning = false,
                    )

                    return SessionStartResult.Started(
                        ActiveSession(
                            idOrg = idOrg,
                            idDeployment = idDeployment,
                            idSource = idSource,
                            idSession = idSession,
                            token = data.token,
                            tokenExpiresUtcMs = data.tokenExpiresUtcMs,
                            batchEndpoint = data.batchEndpoint,
                            accessRequestEndpoint = data.accessRequestEndpoint,
                            forgetRequestEndpoint = data.forgetRequestEndpoint,
                            settings = resolvedSettings,
                            models = data.models,
                            serverAnchor = anchor,
                            collectionEnabled = collectionEnabled,
                            clock = clock,
                        ),
                    )
                }

                isRetryableStatus(response.statusCode) -> {
                    if (attempt + 1 >= maxAttempts) {
                        return SessionStartResult.Refused(
                            statusCode = response.statusCode,
                            errors = parseErrorsOrEmpty(bodyText),
                            message = "Session start failed with HTTP ${response.statusCode} after ${attempt + 1} attempts",
                        )
                    }
                    val waitMs = retryBackoff.computeDelayMs(
                        attempt = attempt,
                        firstWaitMs = defaults.retryFirstWaitMs,
                        longestWaitMs = defaults.retryLongestWaitMs,
                    )
                    delayFn(waitMs)
                    attempt++
                }

                else -> {
                    val errors = parseErrorsOrEmpty(bodyText)
                    return latchRefusal(
                        SessionStartResult.Refused(
                            statusCode = response.statusCode,
                            errors = errors,
                            message = "Session start refused with HTTP ${response.statusCode}",
                        ),
                    )
                }
            }
        }

        return SessionStartResult.Refused(
            statusCode = null,
            message = "Session start exhausted maximum attempts ($maxAttempts)",
        )
    }

    /**
     * Calls the player personal-data access compliance endpoint returned by session start.
     */
    suspend fun requestPersonalDataAccess(
        session: ActiveSession,
        payloadJson: String = "{}",
    ): ComplianceResult = executeComplianceCall(
        endpoint = session.accessRequestEndpoint,
        token = session.token,
        payloadJson = payloadJson,
        endpointName = "access_request_endpoint",
    )

    /**
     * Calls the player personal-data deletion ("forget") compliance endpoint returned by session start.
     */
    suspend fun requestPersonalDataDeletion(
        session: ActiveSession,
        payloadJson: String = "{}",
    ): ComplianceResult = executeComplianceCall(
        endpoint = session.forgetRequestEndpoint,
        token = session.token,
        payloadJson = payloadJson,
        endpointName = "forget_request_endpoint",
    )

    private suspend fun executeComplianceCall(
        endpoint: String,
        token: String,
        payloadJson: String,
        endpointName: String,
    ): ComplianceResult {
        if (endpoint.isBlank()) {
            return ComplianceResult.Unavailable("$endpointName is empty")
        }
        if (token.isBlank()) {
            return ComplianceResult.Unavailable("Session token is empty")
        }

        val request = HttpRequest(
            url = endpoint,
            method = "POST",
            headers = mapOf(
                "authorization" to "Bearer $token",
                "content-type" to "application/json",
            ),
            body = payloadJson.toByteArray(Charsets.UTF_8),
        )

        return try {
            val response = withContext(ioDispatcher) {
                transport.execute(request)
            }
            val bodyText = response.body.toString(Charsets.UTF_8)
            if (response.statusCode in 200..299) {
                ComplianceResult.Success(response.statusCode, bodyText)
            } else {
                ComplianceResult.Failure(
                    statusCode = response.statusCode,
                    message = "Compliance request failed with HTTP ${response.statusCode}",
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ComplianceResult.Failure(
                statusCode = null,
                message = "Compliance request error: ${t.message}",
            )
        }
    }

    private fun latchRefusal(refusal: SessionStartResult.Refused): SessionStartResult.Refused {
        synchronized(refusalLock) {
            if (latchedRefusal == null) {
                latchedRefusal = refusal
            }
            return latchedRefusal!!
        }
    }

    private fun parseErrorsOrEmpty(bodyText: String): List<String> {
        if (bodyText.isBlank()) return emptyList()
        return runCatching {
            JsonCodec.decodeSessionStartEnvelope(bodyText).errors
        }.getOrDefault(emptyList())
    }

    companion object {
        const val SESSION_START_PATH = "/routes/asparagus/sessions/start"
        const val STAGE_SESSION_START_ENDPOINT = "https://control-plane.affectstack-stage.com/routes/asparagus/sessions/start"
        const val PRODUCTION_SESSION_START_ENDPOINT = "https://control-plane.affectstack.com/routes/asparagus/sessions/start"

        fun sessionStartEndpoint(useProduction: Boolean): String =
            if (useProduction) PRODUCTION_SESSION_START_ENDPOINT else STAGE_SESSION_START_ENDPOINT

        fun isRetryableStatus(statusCode: Int): Boolean =
            statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }
}
