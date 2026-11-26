package com.nx10.asparagus.upload

import com.nx10.asparagus.batch.PreparedBatch
import com.nx10.asparagus.contract.Severity
import com.nx10.asparagus.contract.UploadStatus
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.internal.net.HttpRequest
import com.nx10.asparagus.internal.net.HttpTransport
import com.nx10.asparagus.internal.net.RetryBackoff
import com.nx10.asparagus.internal.net.UrlConnectionHttpTransport
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.queue.DiskBatchQueue
import com.nx10.asparagus.queue.QueuedBatchEntry
import com.nx10.asparagus.session.ActiveSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SessionRefreshOutcome {
    data class Refreshed(val session: ActiveSession) : SessionRefreshOutcome
    data object TransientFailure : SessionRefreshOutcome
    data object PermanentlyRefused : SessionRefreshOutcome
}

fun interface SessionRefresher {
    suspend fun refreshSession(): SessionRefreshOutcome
}

sealed interface UploadEvent {
    data class Succeeded(
        val entry: QueuedBatchEntry,
        val statusCode: Int,
        val uploadStatus: UploadStatus,
    ) : UploadEvent

    data class RetryScheduled(
        val entry: QueuedBatchEntry,
        val statusCode: Int?,
        val waitMs: Long,
        val reason: String,
    ) : UploadEvent

    data class RefusedDropped(
        val entry: QueuedBatchEntry,
        val statusCode: Int?,
        val code: String,
    ) : UploadEvent

    data class TokenRefreshed(
        val previousSessionId: String,
        val newSessionId: String,
    ) : UploadEvent
}

fun interface UploadEventListener {
    fun onUploadEvent(event: UploadEvent)
}

enum class DrainStopReason {
    QUEUE_EMPTY,
    COLLECTION_DISABLED,
    SESSION_REFUSED,
    MAX_RETRIES_REACHED,
}

data class DrainSummary(
    val activeSession: ActiveSession,
    val uploadedCount: Int,
    val refusedCount: Int,
    val remainingInQueue: Int,
    val stoppedReason: DrainStopReason,
)

/**
 * Drains [DiskBatchQueue] to `POST <batch_endpoint>` implementing all contract upload rules
 * (`session.proto` lines 107-130 & `BUILDING_AN_SDK.md` lines 195, 203-230).
 */
class BatchUploader(
    private val queue: DiskBatchQueue,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val retryBackoff: RetryBackoff = RetryBackoff(),
    private val diagnostics: DiagnosticsAggregator? = null,
    private val listener: UploadEventListener? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sleeper: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    /**
     * Drains queued batches FIFO using [initialSession], handling proactive session-clock token
     * expiry, `401` session refresh preserving original `Header.idSession` bytes, transient
     * jittered exponential backoff (`408`/`429`/`5xx`/`IOException`), and permanent drops with
     * diagnostics (`400`/`403`/`413`/other 4xx).
     */
    suspend fun drainQueue(
        initialSession: ActiveSession,
        sessionRefresher: SessionRefresher,
        maxRetriesPerBatch: Int = Int.MAX_VALUE,
    ): DrainSummary {
        var session = initialSession
        var uploadedCount = 0
        var refusedCount = 0
        var transientAttempt = 0
        var authAttempt = 0

        if (!session.collectionEnabled || session.batchEndpoint.isBlank()) {
            queue.prune(session.settings.queueMaxBytes, session.settings.queueMaxAgeS)
            return DrainSummary(
                activeSession = session,
                uploadedCount = 0,
                refusedCount = 0,
                remainingInQueue = queue.size(),
                stoppedReason = DrainStopReason.COLLECTION_DISABLED,
            )
        }

        while (true) {
            val entry = queue.peekOldest(
                maxBytes = session.settings.queueMaxBytes,
                maxAgeS = session.settings.queueMaxAgeS,
            ) ?: return DrainSummary(
                activeSession = session,
                uploadedCount = uploadedCount,
                refusedCount = refusedCount,
                remainingInQueue = 0,
                stoppedReason = DrainStopReason.QUEUE_EMPTY,
            )

            // Org / deployment / device identity check (id_session is intentionally NOT compared!)
            if (entry.idOrg != session.idOrg ||
                entry.idDeployment != session.idDeployment ||
                entry.idSource != session.idSource
            ) {
                queue.removeRefused(entry.queueId)
                diagnostics?.record(
                    severity = Severity.SEVERITY_WARNING,
                    code = DIAGNOSTIC_IDENTITY_MISMATCH,
                    detail = "Queued batch identity differs from active session",
                )
                listener?.onUploadEvent(UploadEvent.RefusedDropped(entry, null, DIAGNOSTIC_IDENTITY_MISMATCH))
                refusedCount++
                continue
            }

            // Proactive session-clock token expiry check (never wall clock)
            if (session.isTokenExpired()) {
                when (val outcome = sessionRefresher.refreshSession()) {
                    is SessionRefreshOutcome.Refreshed -> {
                        val prevId = session.idSession
                        session = outcome.session
                        listener?.onUploadEvent(UploadEvent.TokenRefreshed(prevId, session.idSession))
                        if (!session.collectionEnabled || session.batchEndpoint.isBlank()) {
                            queue.prune(session.settings.queueMaxBytes, session.settings.queueMaxAgeS)
                            return DrainSummary(
                                activeSession = session,
                                uploadedCount = uploadedCount,
                                refusedCount = refusedCount,
                                remainingInQueue = queue.size(),
                                stoppedReason = DrainStopReason.COLLECTION_DISABLED,
                            )
                        }
                    }

                    SessionRefreshOutcome.TransientFailure -> {
                        if (authAttempt >= maxRetriesPerBatch) {
                            return DrainSummary(
                                activeSession = session,
                                uploadedCount = uploadedCount,
                                refusedCount = refusedCount,
                                remainingInQueue = queue.size(),
                                stoppedReason = DrainStopReason.MAX_RETRIES_REACHED,
                            )
                        }
                        val waitMs = retryBackoff.computeDelayMs(
                            attempt = authAttempt++,
                            firstWaitMs = session.settings.retryFirstWaitMs,
                            longestWaitMs = session.settings.retryLongestWaitMs,
                        )
                        listener?.onUploadEvent(UploadEvent.RetryScheduled(entry, null, waitMs, "token_refresh_transient"))
                        sleeper(waitMs)
                        continue
                    }

                    SessionRefreshOutcome.PermanentlyRefused -> {
                        return DrainSummary(
                            activeSession = session,
                            uploadedCount = uploadedCount,
                            refusedCount = refusedCount,
                            remainingInQueue = queue.size(),
                            stoppedReason = DrainStopReason.SESSION_REFUSED,
                        )
                    }
                }
            }

            val payloadBytes = queue.readPayload(entry)
            if (payloadBytes == null) {
                queue.removeRefused(entry.queueId)
                diagnostics?.record(
                    severity = Severity.SEVERITY_WARNING,
                    code = DIAGNOSTIC_PAYLOAD_MISSING,
                    detail = "Queued payload file missing for queueId=${entry.queueId}",
                )
                listener?.onUploadEvent(UploadEvent.RefusedDropped(entry, null, DIAGNOSTIC_PAYLOAD_MISSING))
                refusedCount++
                continue
            }

            val request = buildUploadRequest(
                endpoint = session.batchEndpoint,
                token = session.token,
                payloadBytes = payloadBytes,
                compressed = entry.compressed,
            )

            val response = try {
                withContext(ioDispatcher) {
                    transport.execute(request)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                if (transientAttempt >= maxRetriesPerBatch) {
                    return DrainSummary(
                        activeSession = session,
                        uploadedCount = uploadedCount,
                        refusedCount = refusedCount,
                        remainingInQueue = queue.size(),
                        stoppedReason = DrainStopReason.MAX_RETRIES_REACHED,
                    )
                }
                val waitMs = retryBackoff.computeDelayMs(
                    attempt = transientAttempt++,
                    firstWaitMs = session.settings.retryFirstWaitMs,
                    longestWaitMs = session.settings.retryLongestWaitMs,
                )
                listener?.onUploadEvent(UploadEvent.RetryScheduled(entry, null, waitMs, "network_io"))
                sleeper(waitMs)
                continue
            } catch (_: Throwable) {
                if (transientAttempt >= maxRetriesPerBatch) {
                    return DrainSummary(
                        activeSession = session,
                        uploadedCount = uploadedCount,
                        refusedCount = refusedCount,
                        remainingInQueue = queue.size(),
                        stoppedReason = DrainStopReason.MAX_RETRIES_REACHED,
                    )
                }
                val waitMs = retryBackoff.computeDelayMs(
                    attempt = transientAttempt++,
                    firstWaitMs = session.settings.retryFirstWaitMs,
                    longestWaitMs = session.settings.retryLongestWaitMs,
                )
                listener?.onUploadEvent(UploadEvent.RetryScheduled(entry, null, waitMs, "unexpected_error"))
                sleeper(waitMs)
                continue
            }

            val status = response.statusCode
            when {
                status in 200..299 -> {
                    val uploadStatus = runCatching {
                        if (response.body.isNotEmpty()) {
                            BatchProtoCodec.decodeUploadResponse(response.body).status
                        } else {
                            UploadStatus.UPLOAD_STATUS_STORED
                        }
                    }.getOrDefault(UploadStatus.UPLOAD_STATUS_STORED)

                    queue.removeAcked(entry.queueId)
                    transientAttempt = 0
                    authAttempt = 0
                    uploadedCount++
                    listener?.onUploadEvent(UploadEvent.Succeeded(entry, status, uploadStatus))
                }

                status == 401 -> {
                    if (authAttempt >= maxRetriesPerBatch) {
                        return DrainSummary(
                            activeSession = session,
                            uploadedCount = uploadedCount,
                            refusedCount = refusedCount,
                            remainingInQueue = queue.size(),
                            stoppedReason = DrainStopReason.MAX_RETRIES_REACHED,
                        )
                    }
                    val waitMs = retryBackoff.computeDelayMs(
                        attempt = authAttempt++,
                        firstWaitMs = session.settings.retryFirstWaitMs,
                        longestWaitMs = session.settings.retryLongestWaitMs,
                    )
                    listener?.onUploadEvent(UploadEvent.RetryScheduled(entry, 401, waitMs, "http_401"))
                    sleeper(waitMs)

                    when (val outcome = sessionRefresher.refreshSession()) {
                        is SessionRefreshOutcome.Refreshed -> {
                            val prevId = session.idSession
                            session = outcome.session
                            listener?.onUploadEvent(UploadEvent.TokenRefreshed(prevId, session.idSession))
                            if (!session.collectionEnabled || session.batchEndpoint.isBlank()) {
                                queue.prune(session.settings.queueMaxBytes, session.settings.queueMaxAgeS)
                                return DrainSummary(
                                    activeSession = session,
                                    uploadedCount = uploadedCount,
                                    refusedCount = refusedCount,
                                    remainingInQueue = queue.size(),
                                    stoppedReason = DrainStopReason.COLLECTION_DISABLED,
                                )
                            }
                        }

                        SessionRefreshOutcome.TransientFailure -> {
                            // Next iteration retries with increased authAttempt
                        }

                        SessionRefreshOutcome.PermanentlyRefused -> {
                            return DrainSummary(
                                activeSession = session,
                                uploadedCount = uploadedCount,
                                refusedCount = refusedCount,
                                remainingInQueue = queue.size(),
                                stoppedReason = DrainStopReason.SESSION_REFUSED,
                            )
                        }
                    }
                }

                isRetryableUploadStatus(status) -> {
                    if (transientAttempt >= maxRetriesPerBatch) {
                        return DrainSummary(
                            activeSession = session,
                            uploadedCount = uploadedCount,
                            refusedCount = refusedCount,
                            remainingInQueue = queue.size(),
                            stoppedReason = DrainStopReason.MAX_RETRIES_REACHED,
                        )
                    }
                    val waitMs = retryBackoff.computeDelayMs(
                        attempt = transientAttempt++,
                        firstWaitMs = session.settings.retryFirstWaitMs,
                        longestWaitMs = session.settings.retryLongestWaitMs,
                    )
                    listener?.onUploadEvent(UploadEvent.RetryScheduled(entry, status, waitMs, "http_$status"))
                    sleeper(waitMs)
                }

                else -> {
                    // 400, 403, 413, or any other non-retryable status: permanent drop + diagnostic
                    queue.removeRefused(entry.queueId)
                    entry.carriedDroppedSummary?.let { queue.restoreDroppedSummary(it) }
                    val code = "upload_refused_$status"
                    diagnostics?.record(
                        severity = Severity.SEVERITY_WARNING,
                        code = code,
                        detail = "HTTP $status",
                    )
                    listener?.onUploadEvent(UploadEvent.RefusedDropped(entry, status, code))
                    transientAttempt = 0
                    refusedCount++
                }
            }
        }
    }

    /**
     * Handles the `queueMaxBytes == 0 || queueMaxAgeS == 0` case (`session.proto` lines 208-209):
     * attempts a single immediate upload and drops + counts the batch if it cannot be sent at once.
     */
    suspend fun uploadImmediateWhenQueueDisabled(
        batch: PreparedBatch,
        session: ActiveSession,
    ): Boolean {
        if (!session.collectionEnabled || session.batchEndpoint.isBlank()) {
            queue.recordUnqueuedDrop(batch)
            return false
        }

        val request = buildUploadRequest(
            endpoint = session.batchEndpoint,
            token = session.token,
            payloadBytes = batch.wireBytes,
            compressed = batch.compressed,
        )

        val response = try {
            withContext(ioDispatcher) {
                transport.execute(request)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            queue.recordUnqueuedDrop(batch)
            return false
        }

        val status = response.statusCode
        if (status in 200..299) {
            return true
        }
        if (status == 401 || isRetryableUploadStatus(status)) {
            queue.recordUnqueuedDrop(batch)
        } else {
            diagnostics?.record(
                severity = Severity.SEVERITY_WARNING,
                code = "upload_refused_$status",
                detail = "HTTP $status",
            )
        }
        return false
    }

    private fun buildUploadRequest(
        endpoint: String,
        token: String,
        payloadBytes: ByteArray,
        compressed: Boolean,
    ): HttpRequest {
        val headers = linkedMapOf(
            "authorization" to "Bearer $token",
            "content-type" to CONTENT_TYPE_PROTOBUF,
        )
        if (compressed) {
            headers["content-encoding"] = "gzip"
        }
        return HttpRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = payloadBytes,
        )
    }

    companion object {
        const val CONTENT_TYPE_PROTOBUF: String = "application/x-protobuf"
        const val DIAGNOSTIC_IDENTITY_MISMATCH: String = "upload_identity_mismatch"
        const val DIAGNOSTIC_PAYLOAD_MISSING: String = "upload_payload_missing"

        fun isRetryableUploadStatus(statusCode: Int): Boolean =
            statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }
}
