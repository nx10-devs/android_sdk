package com.nx10.asparagus.queue

import com.nx10.asparagus.batch.DroppedBatchesSummary
import com.nx10.asparagus.batch.PreparedBatch
import com.nx10.asparagus.internal.codec.JsonCodec
import com.nx10.asparagus.internal.codec.JsonValue
import com.nx10.asparagus.time.AndroidTimeSource
import com.nx10.asparagus.time.MonotonicTimeSource
import java.io.File
import java.io.IOException

data class QueuedBatchEntry(
    val queueId: Long,
    val idOrg: String,
    val idDeployment: String,
    val idSource: String,
    val idSession: String,
    val sequence: Long,
    val windowStartUs: Long,
    val closedUs: Long,
    val compressed: Boolean,
    val payloadBytes: Int,
    val createdWallUtcMs: Long,
    val carriedDroppedSummary: DroppedBatchesSummary? = null,
)

sealed interface EnqueueResult {
    data class Queued(val entry: QueuedBatchEntry) : EnqueueResult
    data object QueueDisabled : EnqueueResult
    data object DroppedImmediately : EnqueueResult
}

/**
 * Persistent FIFO disk queue for closed batches (`02-unity-sdk.md` lines 26-27, 348-349).
 *
 * - Writes payload `.bin` and metadata `.meta.json` atomically via `.tmp` + rename (`java.io.File`, API 24+ safe).
 * - Enforces `queueMaxBytes` and `queueMaxAgeS` by evicting oldest batches first and folding
 *   evicted entries (including any `carriedDroppedSummary` they held) into persisted `dropped_state.json`.
 */
class DiskBatchQueue(
    private val queueDir: File,
    private val timeSource: MonotonicTimeSource = AndroidTimeSource,
) {
    private val lock = Any()
    private val entries = ArrayDeque<QueuedBatchEntry>()
    private var nextQueueId: Long = 1L
    private var pendingDropped: DroppedBatchesSummary? = null

    init {
        synchronized(lock) {
            runCatching { queueDir.mkdirs() }
            rehydrateLocked()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun totalBytes(): Long = synchronized(lock) { totalBytesLocked() }

    fun hasPendingDroppedSummary(): Boolean = synchronized(lock) { pendingDropped != null }

    fun peekPendingDroppedSummary(): DroppedBatchesSummary? = synchronized(lock) { pendingDropped }

    /**
     * Atomically takes any pending dropped-batch summary so [com.nx10.asparagus.batch.BatchAssembler]
     * can embed it into the next batch header and `GAP_CAUSE_BATCHES_DROPPED` record.
     */
    fun consumePendingDroppedSummary(): DroppedBatchesSummary? = synchronized(lock) {
        val current = pendingDropped
        if (current != null) {
            pendingDropped = null
            persistDroppedStateLocked()
        }
        current
    }

    /**
     * Restores/merges a dropped-batch summary (e.g. if the batch carrying it failed to persist).
     */
    fun restoreDroppedSummary(summary: DroppedBatchesSummary) = synchronized(lock) {
        if (summary.count <= 0) return@synchronized
        mergePendingDroppedLocked(summary)
        persistDroppedStateLocked()
    }

    /**
     * Records a batch that could not be queued or sent when queueing is disabled (`0` limit).
     */
    fun recordUnqueuedDrop(batch: PreparedBatch) = synchronized(lock) {
        foldBatchIntoDroppedLocked(batch)
        persistDroppedStateLocked()
    }

    /**
     * Enforces [maxBytes] and [maxAgeS] limits against currently queued batches.
     */
    fun prune(maxBytes: Int, maxAgeS: Int) = synchronized(lock) {
        if (maxBytes <= 0 || maxAgeS <= 0) {
            evictAllLocked()
            return@synchronized
        }
        pruneExpiredLocked(maxAgeS)
        while (entries.isNotEmpty() && totalBytesLocked() > maxBytes.toLong()) {
            evictOldestLocked()
        }
    }

    /**
     * Persists [batch] to disk under [maxBytes] and [maxAgeS] limits.
     */
    fun enqueue(
        batch: PreparedBatch,
        maxBytes: Int,
        maxAgeS: Int,
    ): EnqueueResult = synchronized(lock) {
        if (maxBytes <= 0 || maxAgeS <= 0) {
            evictAllLocked()
            return EnqueueResult.QueueDisabled
        }

        pruneExpiredLocked(maxAgeS)

        val sizeBytes = batch.wireBytes.size
        if (sizeBytes > maxBytes) {
            foldBatchIntoDroppedLocked(batch)
            persistDroppedStateLocked()
            return EnqueueResult.DroppedImmediately
        }

        while (entries.isNotEmpty() && (totalBytesLocked() + sizeBytes.toLong() > maxBytes.toLong())) {
            evictOldestLocked()
        }

        val queueId = nextQueueId++
        val entry = QueuedBatchEntry(
            queueId = queueId,
            idOrg = batch.idOrg,
            idDeployment = batch.idDeployment,
            idSource = batch.idSource,
            idSession = batch.idSession,
            sequence = batch.sequence,
            windowStartUs = batch.windowStartUs,
            closedUs = batch.closedUs,
            compressed = batch.compressed,
            payloadBytes = sizeBytes,
            createdWallUtcMs = timeSource.wallUtcMs(),
            carriedDroppedSummary = batch.carriedDroppedSummary,
        )

        try {
            if (!queueDir.exists()) {
                queueDir.mkdirs()
            }
            writeFileAtomic(binFile(queueId), batch.wireBytes)
            writeFileAtomic(metaFile(queueId), encodeEntryMeta(entry).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            runCatching { binFile(queueId).delete() }
            runCatching { metaFile(queueId).delete() }
            foldBatchIntoDroppedLocked(batch)
            persistDroppedStateLocked()
            return EnqueueResult.DroppedImmediately
        }

        entries.addLast(entry)
        EnqueueResult.Queued(entry)
    }

    /**
     * Prunes expired/oversized entries and returns the oldest valid entry without removing it.
     */
    fun peekOldest(maxBytes: Int, maxAgeS: Int): QueuedBatchEntry? = synchronized(lock) {
        if (maxBytes <= 0 || maxAgeS <= 0) {
            evictAllLocked()
            return null
        }
        pruneExpiredLocked(maxAgeS)
        while (entries.isNotEmpty() && totalBytesLocked() > maxBytes.toLong()) {
            evictOldestLocked()
        }
        entries.firstOrNull()
    }

    fun readPayload(entry: QueuedBatchEntry): ByteArray? = synchronized(lock) {
        val file = binFile(entry.queueId)
        if (!file.isFile) return null
        runCatching { file.readBytes() }.getOrNull()
    }

    fun removeAcked(queueId: Long) = synchronized(lock) {
        removeByIdLocked(queueId)
    }

    fun removeRefused(queueId: Long) = synchronized(lock) {
        removeByIdLocked(queueId)
    }

    private fun removeByIdLocked(queueId: Long) {
        val iter = entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().queueId == queueId) {
                iter.remove()
                break
            }
        }
        runCatching { binFile(queueId).delete() }
        runCatching { metaFile(queueId).delete() }
    }

    private fun pruneExpiredLocked(maxAgeS: Int) {
        val nowMs = timeSource.wallUtcMs()
        val maxAgeMs = maxAgeS.toLong() * 1_000L
        var changed = false
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val ageMs = nowMs - entry.createdWallUtcMs
            if (ageMs > maxAgeMs) {
                iter.remove()
                evictEntryLocked(entry)
                changed = true
            }
        }
        if (changed) {
            persistDroppedStateLocked()
        }
    }

    private fun evictAllLocked() {
        if (entries.isEmpty()) return
        while (entries.isNotEmpty()) {
            evictEntryLocked(entries.removeFirst())
        }
        persistDroppedStateLocked()
    }

    private fun evictOldestLocked() {
        if (entries.isEmpty()) return
        evictEntryLocked(entries.removeFirst())
        persistDroppedStateLocked()
    }

    private fun evictEntryLocked(entry: QueuedBatchEntry) {
        runCatching { binFile(entry.queueId).delete() }
        runCatching { metaFile(entry.queueId).delete() }
        mergePendingDroppedLocked(
            DroppedBatchesSummary(
                count = 1,
                firstDroppedSequence = entry.sequence,
                startTimeUs = entry.windowStartUs,
                endTimeUs = entry.closedUs,
            ),
        )
        entry.carriedDroppedSummary?.let { carried ->
            mergePendingDroppedLocked(carried)
        }
    }

    private fun foldBatchIntoDroppedLocked(batch: PreparedBatch) {
        mergePendingDroppedLocked(
            DroppedBatchesSummary(
                count = 1,
                firstDroppedSequence = batch.sequence,
                startTimeUs = batch.windowStartUs,
                endTimeUs = batch.closedUs,
            ),
        )
        batch.carriedDroppedSummary?.let { carried ->
            mergePendingDroppedLocked(carried)
        }
    }

    private fun mergePendingDroppedLocked(summary: DroppedBatchesSummary) {
        val current = pendingDropped
        pendingDropped = if (current == null) summary else current.merge(summary)
    }

    private fun totalBytesLocked(): Long = entries.sumOf { it.payloadBytes.toLong() }

    private fun rehydrateLocked() {
        val files = queueDir.listFiles() ?: return

        // Clean up any stray temp files from interrupted writes
        for (file in files) {
            if (file.name.endsWith(TMP_SUFFIX)) {
                runCatching { file.delete() }
            }
        }

        // Load persisted dropped_state.json
        val droppedFile = droppedStateFile()
        if (droppedFile.isFile) {
            pendingDropped = runCatching {
                decodeDroppedSummary(droppedFile.readText(Charsets.UTF_8))
            }.getOrNull()
        }

        // Rehydrate valid FIFO queue entries
        val loaded = mutableListOf<QueuedBatchEntry>()
        for (file in files) {
            if (!file.name.endsWith(META_SUFFIX)) continue
            val entry = runCatching {
                decodeEntryMeta(file.readText(Charsets.UTF_8))
            }.getOrNull()
            val bin = entry?.let { binFile(it.queueId) }
            if (entry != null && bin != null && bin.isFile && bin.length().toInt() == entry.payloadBytes) {
                loaded.add(entry)
            } else {
                runCatching { file.delete() }
                if (entry != null) {
                    runCatching { binFile(entry.queueId).delete() }
                }
            }
        }

        loaded.sortBy { it.queueId }
        entries.clear()
        entries.addAll(loaded)
        nextQueueId = (loaded.maxOfOrNull { it.queueId } ?: 0L) + 1L
    }

    private fun persistDroppedStateLocked() {
        val file = droppedStateFile()
        val summary = pendingDropped
        if (summary == null || summary.count <= 0) {
            runCatching { if (file.exists()) file.delete() }
            return
        }
        runCatching {
            if (!queueDir.exists()) queueDir.mkdirs()
            writeFileAtomic(file, encodeDroppedSummary(summary).toByteArray(Charsets.UTF_8))
        }
    }

    private fun binFile(queueId: Long): File = File(queueDir, "$queueId$BIN_SUFFIX")

    private fun metaFile(queueId: Long): File = File(queueDir, "$queueId$META_SUFFIX")

    private fun droppedStateFile(): File = File(queueDir, DROPPED_STATE_FILE)

    @Throws(IOException::class)
    private fun writeFileAtomic(target: File, bytes: ByteArray) {
        val tmp = File(queueDir, "${target.name}$TMP_SUFFIX")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.flush()
        }
        if (target.exists() && !target.delete()) {
            // Fallback overwrite if existing target could not be unlinked first
            target.writeBytes(bytes)
            runCatching { tmp.delete() }
            return
        }
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            runCatching { tmp.delete() }
        }
    }

    private fun encodeEntryMeta(e: QueuedBatchEntry): String {
        val map = linkedMapOf<String, JsonValue>(
            "queue_id" to JsonValue.JsonString(e.queueId.toString()),
            "id_org" to JsonValue.JsonString(e.idOrg),
            "id_deployment" to JsonValue.JsonString(e.idDeployment),
            "id_source" to JsonValue.JsonString(e.idSource),
            "id_session" to JsonValue.JsonString(e.idSession),
            "sequence" to JsonValue.JsonString(e.sequence.toString()),
            "window_start_us" to JsonValue.JsonString(e.windowStartUs.toString()),
            "closed_us" to JsonValue.JsonString(e.closedUs.toString()),
            "compressed" to JsonValue.JsonBool(e.compressed),
            "payload_bytes" to JsonValue.JsonNumber(e.payloadBytes.toString()),
            "created_wall_utc_ms" to JsonValue.JsonString(e.createdWallUtcMs.toString()),
        )
        e.carriedDroppedSummary?.let { c ->
            map["carried_dropped_count"] = JsonValue.JsonNumber(c.count.toString())
            map["carried_first_dropped_seq"] = JsonValue.JsonString(c.firstDroppedSequence.toString())
            map["carried_dropped_start_us"] = JsonValue.JsonString(c.startTimeUs.toString())
            map["carried_dropped_end_us"] = JsonValue.JsonString(c.endTimeUs.toString())
        }
        return JsonCodec.stringify(JsonValue.JsonObject(map))
    }

    private fun decodeEntryMeta(json: String): QueuedBatchEntry {
        val obj = JsonCodec.parse(json) as JsonValue.JsonObject
        val carriedCount = obj.intOrDefault("carried_dropped_count", 0)
        val carried = if (carriedCount > 0) {
            DroppedBatchesSummary(
                count = carriedCount,
                firstDroppedSequence = obj.longOrDefault("carried_first_dropped_seq", 0L),
                startTimeUs = obj.longOrDefault("carried_dropped_start_us", 0L),
                endTimeUs = obj.longOrDefault("carried_dropped_end_us", 0L),
            )
        } else {
            null
        }
        return QueuedBatchEntry(
            queueId = obj.reqLong("queue_id"),
            idOrg = obj.strOrEmpty("id_org"),
            idDeployment = obj.strOrEmpty("id_deployment"),
            idSource = obj.strOrEmpty("id_source"),
            idSession = obj.strOrEmpty("id_session"),
            sequence = obj.reqLong("sequence"),
            windowStartUs = obj.longOrDefault("window_start_us", 0L),
            closedUs = obj.longOrDefault("closed_us", 0L),
            compressed = (obj["compressed"] as? JsonValue.JsonBool)?.value ?: false,
            payloadBytes = obj.intOrDefault("payload_bytes", 0),
            createdWallUtcMs = obj.longOrDefault("created_wall_utc_ms", 0L),
            carriedDroppedSummary = carried,
        )
    }

    private fun encodeDroppedSummary(s: DroppedBatchesSummary): String = JsonCodec.stringify(
        JsonValue.JsonObject(
            linkedMapOf(
                "count" to JsonValue.JsonNumber(s.count.toString()),
                "first_dropped_sequence" to JsonValue.JsonString(s.firstDroppedSequence.toString()),
                "start_time_us" to JsonValue.JsonString(s.startTimeUs.toString()),
                "end_time_us" to JsonValue.JsonString(s.endTimeUs.toString()),
            ),
        ),
    )

    private fun decodeDroppedSummary(json: String): DroppedBatchesSummary? {
        val obj = JsonCodec.parse(json) as? JsonValue.JsonObject ?: return null
        val count = obj.intOrDefault("count", 0)
        if (count <= 0) return null
        return DroppedBatchesSummary(
            count = count,
            firstDroppedSequence = obj.longOrDefault("first_dropped_sequence", 0L),
            startTimeUs = obj.longOrDefault("start_time_us", 0L),
            endTimeUs = obj.longOrDefault("end_time_us", 0L),
        )
    }

    private fun JsonValue.JsonObject.strOrEmpty(key: String): String =
        (entries[key] as? JsonValue.JsonString)?.value.orEmpty()

    private fun JsonValue.JsonObject.reqLong(key: String): Long =
        longOrNull(key) ?: error("Missing long key '$key'")

    private fun JsonValue.JsonObject.longOrNull(key: String): Long? = when (val v = entries[key]) {
        is JsonValue.JsonString -> v.value.toLongOrNull()
        is JsonValue.JsonNumber -> v.raw.toLongOrNull()
        else -> null
    }

    private fun JsonValue.JsonObject.longOrDefault(key: String, default: Long): Long =
        longOrNull(key) ?: default

    private fun JsonValue.JsonObject.intOrDefault(key: String, default: Int): Int = when (val v = entries[key]) {
        is JsonValue.JsonNumber -> v.raw.toIntOrNull() ?: default
        is JsonValue.JsonString -> v.value.toIntOrNull() ?: default
        else -> default
    }

    companion object {
        private const val BIN_SUFFIX = ".bin"
        private const val META_SUFFIX = ".meta.json"
        private const val TMP_SUFFIX = ".tmp"
        private const val DROPPED_STATE_FILE = "dropped_state.json"
    }
}
