package com.nx10.asparagus.upload

import com.nx10.asparagus.batch.GzipCodec
import com.nx10.asparagus.batch.PreparedBatch
import com.nx10.asparagus.config.SdkSettings
import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.contract.UploadResponse
import com.nx10.asparagus.contract.UploadStatus
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.internal.net.HttpRequest
import com.nx10.asparagus.internal.net.HttpResponse
import com.nx10.asparagus.internal.net.HttpTransport
import com.nx10.asparagus.internal.net.RetryBackoff
import com.nx10.asparagus.lifecycle.DiagnosticsAggregator
import com.nx10.asparagus.queue.DiskBatchQueue
import com.nx10.asparagus.session.ActiveSession
import com.nx10.asparagus.time.MonotonicTimeSource
import com.nx10.asparagus.time.SessionClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class BatchUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeTimeSource(
        var elapsedNanos: Long = 10_000_000_000L,
        var wallMs: Long = 1_795_000_000_000L,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = 0
    }

    private class ScriptedTransport(
        private val handlers: ArrayDeque<(HttpRequest) -> HttpResponse>,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val h = handlers.removeFirstOrNull()
                ?: throw AssertionError("Unexpected extra request to ${request.url}")
            return h(request)
        }
    }

    private fun makeSession(
        time: FakeTimeSource,
        idSession: String,
        token: String,
        tokenExpiresUtcMs: Long = time.wallMs + 86_400_000L,
        batchEndpoint: String = "https://intake.stage.example/asparagus/v1/batches",
    ): ActiveSession {
        val clock = SessionClock(timeSource = time, appStartElapsedNanos = time.elapsedNanos)
        val anchor = clock.anchor(serverUtcMs = time.wallMs, roundTripMs = 15)
        return ActiveSession(
            idOrg = "made-up-org-1",
            idDeployment = "made-up-dep-1",
            idSource = "made-up-src-1",
            idSession = idSession,
            token = token,
            tokenExpiresUtcMs = tokenExpiresUtcMs,
            batchEndpoint = batchEndpoint,
            accessRequestEndpoint = "",
            forgetRequestEndpoint = "",
            settings = SdkSettings.loadDefaults(),
            models = emptyList(),
            serverAnchor = anchor,
            collectionEnabled = batchEndpoint.isNotBlank(),
            clock = clock,
        )
    }

    private fun makePreparedBatch(
        idSession: String,
        sequence: Long,
        compressed: Boolean,
    ): PreparedBatch {
        val batch = Batch(
            header = Header(
                idOrg = "made-up-org-1",
                idDeployment = "made-up-dep-1",
                idSource = "made-up-src-1",
                idSession = idSession,
                sequence = sequence,
                schemaVersion = ASPARAGUS_SCHEMA_VERSION,
                windowStartUs = 1_795_000_000_000_000L + sequence * 10_000_000L,
                windowLengthS = 10,
                closedUs = 1_795_000_010_000_000L + sequence * 10_000_000L,
            ),
        )
        val raw = BatchProtoCodec.encode(batch)
        val wire = if (compressed) GzipCodec.gzip(raw) else raw
        return PreparedBatch(batch = batch, rawProtoBytes = raw, wireBytes = wire, compressed = compressed)
    }

    private val storedResponseBody = BatchProtoCodec.encodeUploadResponse(
        UploadResponse(UploadStatus.UPLOAD_STATUS_STORED),
    )

    @Test
    fun `200 upload sets conditional content-encoding gzip header and deletes from queue`() = runBlocking {
        val time = FakeTimeSource()
        val session = makeSession(time, idSession = "ses-A", token = "tok-A")
        val queue = DiskBatchQueue(tempFolder.newFolder("q-200"), time)
        queue.enqueue(makePreparedBatch("ses-A", 1L, compressed = true), 50_000_000, 900)
        queue.enqueue(makePreparedBatch("ses-A", 2L, compressed = false), 50_000_000, 900)

        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { req ->
                        assertEquals("Bearer tok-A", req.headers["authorization"])
                        assertEquals("application/x-protobuf", req.headers["content-type"])
                        assertEquals("gzip", req.headers["content-encoding"])
                        assertTrue(GzipCodec.isGzipped(req.body))
                        HttpResponse(200, body = storedResponseBody)
                    },
                    { req ->
                        assertEquals("Bearer tok-A", req.headers["authorization"])
                        assertEquals("application/x-protobuf", req.headers["content-type"])
                        assertNull("content-encoding must be omitted when uncompressed", req.headers["content-encoding"])
                        HttpResponse(200, body = storedResponseBody)
                    },
                ),
            ),
        )

        val uploader = BatchUploader(
            queue = queue,
            transport = transport,
            ioDispatcher = Dispatchers.Unconfined,
            sleeper = {},
        )
        val summary = uploader.drainQueue(session, SessionRefresher { SessionRefreshOutcome.PermanentlyRefused })
        assertEquals(DrainStopReason.QUEUE_EMPTY, summary.stoppedReason)
        assertEquals(2, summary.uploadedCount)
        assertEquals(0, queue.size())
    }

    @Test
    fun `proactive session-clock token expiry refreshes session before upload`() = runBlocking {
        val time = FakeTimeSource()
        val expiringSession = makeSession(
            time = time,
            idSession = "ses-old",
            token = "tok-old",
            tokenExpiresUtcMs = time.wallMs + 10_000L,
        )
        val queue = DiskBatchQueue(tempFolder.newFolder("q-exp"), time)
        queue.enqueue(makePreparedBatch("ses-old", 1L, compressed = false), 50_000_000, 900)

        // Advance monotonic clock past token expiry (15s > 10s)
        time.elapsedNanos += 15_000_000_000L
        assertTrue(expiringSession.isTokenExpired())

        val refreshedSession = makeSession(time, idSession = "ses-new", token = "tok-new")
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { req ->
                        assertEquals("Bearer tok-new", req.headers["authorization"])
                        // Batch body still carries original ses-old!
                        val decoded = BatchProtoCodec.decode(req.body)
                        assertEquals("ses-old", decoded.header?.idSession)
                        HttpResponse(200, body = storedResponseBody)
                    },
                ),
            ),
        )

        var refreshCalls = 0
        val uploader = BatchUploader(
            queue = queue,
            transport = transport,
            ioDispatcher = Dispatchers.Unconfined,
            sleeper = {},
        )
        val summary = uploader.drainQueue(
            expiringSession,
            SessionRefresher {
                refreshCalls++
                SessionRefreshOutcome.Refreshed(refreshedSession)
            },
        )
        assertEquals(1, refreshCalls)
        assertEquals("ses-new", summary.activeSession.idSession)
        assertEquals(1, summary.uploadedCount)
    }

    @Test
    fun `401 waits backoff, refreshes session, and re-uploads leftover batch preserving original Header idSession`() = runBlocking {
        val time = FakeTimeSource()
        val sessionA = makeSession(time, idSession = "ses-A", token = "tok-A")
        val sessionB = makeSession(time, idSession = "ses-B", token = "tok-B")
        val queue = DiskBatchQueue(tempFolder.newFolder("q-401"), time)
        queue.enqueue(makePreparedBatch("ses-A", 7L, compressed = true), 50_000_000, 900)

        val observedWaits = mutableListOf<Long>()
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { req ->
                        assertEquals("Bearer tok-A", req.headers["authorization"])
                        HttpResponse(401, body = """{"message":"token rejected"}""".toByteArray())
                    },
                    { req ->
                        assertEquals("Bearer tok-B", req.headers["authorization"])
                        val decoded = BatchProtoCodec.decode(GzipCodec.gunzip(req.body))
                        assertEquals("Original Header.idSession must be preserved across 401 refresh", "ses-A", decoded.header?.idSession)
                        assertEquals(7L, decoded.header?.sequence)
                        HttpResponse(200, body = storedResponseBody)
                    },
                ),
            ),
        )

        val uploader = BatchUploader(
            queue = queue,
            transport = transport,
            retryBackoff = RetryBackoff(jitterSource = { 0.0 }),
            ioDispatcher = Dispatchers.Unconfined,
            sleeper = { observedWaits.add(it) },
        )

        val summary = uploader.drainQueue(sessionA, SessionRefresher { SessionRefreshOutcome.Refreshed(sessionB) })
        assertEquals(DrainStopReason.QUEUE_EMPTY, summary.stoppedReason)
        assertEquals("ses-B", summary.activeSession.idSession)
        assertEquals(1, summary.uploadedCount)
        assertEquals(listOf(2_000L), observedWaits)
    }

    @Test
    fun `transient 503, 429, IOException retry with backoff and reset backoff after 200`() = runBlocking {
        val time = FakeTimeSource()
        val session = makeSession(time, idSession = "ses-A", token = "tok-A")
        val queue = DiskBatchQueue(tempFolder.newFolder("q-retry"), time)
        queue.enqueue(makePreparedBatch("ses-A", 1L, compressed = false), 50_000_000, 900)
        queue.enqueue(makePreparedBatch("ses-A", 2L, compressed = false), 50_000_000, 900)

        val observedWaits = mutableListOf<Long>()
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { HttpResponse(503) },
                    { HttpResponse(429) },
                    { throw IOException("offline") },
                    { HttpResponse(200, body = storedResponseBody) }, // batch 1 succeeds -> resets backoff to 2000ms!
                    { HttpResponse(500) },
                    { HttpResponse(200, body = storedResponseBody) }, // batch 2 succeeds
                ),
            ),
        )

        val uploader = BatchUploader(
            queue = queue,
            transport = transport,
            retryBackoff = RetryBackoff(jitterSource = { 0.0 }),
            ioDispatcher = Dispatchers.Unconfined,
            sleeper = { observedWaits.add(it) },
        )

        val summary = uploader.drainQueue(session, SessionRefresher { SessionRefreshOutcome.PermanentlyRefused })
        assertEquals(2, summary.uploadedCount)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 2_000L), observedWaits)
    }

    @Test
    fun `400, 403, and 413 drop permanently with Diagnostics and do not block subsequent valid batches`() = runBlocking {
        val time = FakeTimeSource()
        val session = makeSession(time, idSession = "ses-A", token = "tok-A")
        val diagnostics = DiagnosticsAggregator(session.clock)
        val queue = DiskBatchQueue(tempFolder.newFolder("q-refused"), time)
        queue.enqueue(makePreparedBatch("ses-A", 1L, compressed = false), 50_000_000, 900)
        queue.enqueue(makePreparedBatch("ses-A", 2L, compressed = false), 50_000_000, 900)
        queue.enqueue(makePreparedBatch("ses-A", 3L, compressed = false), 50_000_000, 900)
        queue.enqueue(makePreparedBatch("ses-A", 4L, compressed = false), 50_000_000, 900)

        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    { HttpResponse(400, body = """{"message":"bad batch"}""".toByteArray()) },
                    { HttpResponse(403, body = """{"message":"wrong org"}""".toByteArray()) },
                    { HttpResponse(413, body = """{"message":"too big"}""".toByteArray()) },
                    { HttpResponse(200, body = storedResponseBody) },
                ),
            ),
        )

        val uploader = BatchUploader(
            queue = queue,
            transport = transport,
            diagnostics = diagnostics,
            ioDispatcher = Dispatchers.Unconfined,
            sleeper = { throw AssertionError("Must not sleep on permanent 400/403/413 refusals") },
        )

        val summary = uploader.drainQueue(session, SessionRefresher { SessionRefreshOutcome.PermanentlyRefused })
        assertEquals(DrainStopReason.QUEUE_EMPTY, summary.stoppedReason)
        assertEquals(1, summary.uploadedCount)
        assertEquals(3, summary.refusedCount)
        assertEquals(0, queue.size())

        val diagCodes = diagnostics.flushWindow().map { it.code }.toSet()
        assertEquals(setOf("upload_refused_400", "upload_refused_403", "upload_refused_413"), diagCodes)
    }

    @Test
    fun `disabled collection session with empty batch_endpoint does not prune queued batches from prior sessions`() = runBlocking {
        val time = FakeTimeSource()
        val queue = DiskBatchQueue(tempFolder.newFolder("q-disabled"), time)
        queue.enqueue(makePreparedBatch("ses-prev", 1L, compressed = false), 50_000_000, 900)
        assertEquals(1, queue.size())

        // Even with expired age or zero queue limits on a non-collecting session, queued batches remain intact!
        time.elapsedNanos += 2_000_000_000_000L
        val disabledSession = makeSession(
            time = time,
            idSession = "ses-off",
            token = "tok-off",
            batchEndpoint = "",
        ).copy(
            settings = SdkSettings.builtInDefaults().copy(queueMaxBytes = 0, queueMaxAgeS = 0),
        )

        val uploader = BatchUploader(
            queue = queue,
            transport = ScriptedTransport(ArrayDeque()),
            ioDispatcher = Dispatchers.Unconfined,
            sleeper = { throw AssertionError("Must not sleep when collection disabled") },
        )

        val summary = uploader.drainQueue(disabledSession, SessionRefresher { SessionRefreshOutcome.PermanentlyRefused })
        assertEquals(DrainStopReason.COLLECTION_DISABLED, summary.stoppedReason)
        assertEquals(0, summary.uploadedCount)
        assertEquals(1, summary.remainingInQueue)
        assertEquals(1, queue.size())
    }
}
