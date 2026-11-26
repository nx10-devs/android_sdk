package com.nx10.asparagus.queue

import com.nx10.asparagus.batch.DroppedBatchesSummary
import com.nx10.asparagus.batch.PreparedBatch
import com.nx10.asparagus.contract.ASPARAGUS_SCHEMA_VERSION
import com.nx10.asparagus.contract.Batch
import com.nx10.asparagus.contract.Header
import com.nx10.asparagus.internal.codec.BatchProtoCodec
import com.nx10.asparagus.time.MonotonicTimeSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskBatchQueueTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeTimeSource(
        var elapsedNanos: Long = 1_000_000_000L,
        var wallMs: Long = 1_795_000_000_000L,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
        override fun wallUtcMs(): Long = wallMs
        override fun utcOffsetMinutes(wallUtcMs: Long): Int = 0
    }

    private fun makePreparedBatch(
        sequence: Long,
        windowStartUs: Long,
        closedUs: Long,
        wireSizeBytes: Int = 100,
        carriedDropped: DroppedBatchesSummary? = null,
    ): PreparedBatch {
        val batch = Batch(
            header = Header(
                idOrg = "org-1",
                idDeployment = "dep-1",
                idSource = "src-1",
                idSession = "ses-1",
                sequence = sequence,
                schemaVersion = ASPARAGUS_SCHEMA_VERSION,
                windowStartUs = windowStartUs,
                windowLengthS = 10,
                closedUs = closedUs,
                batchesDropped = carriedDropped?.count ?: 0,
            ),
        )
        val raw = BatchProtoCodec.encode(batch)
        val wire = ByteArray(wireSizeBytes) { (sequence + it).toByte() }
        return PreparedBatch(
            batch = batch,
            rawProtoBytes = raw,
            wireBytes = wire,
            compressed = true,
            carriedDroppedSummary = carriedDropped,
        )
    }

    @Test
    fun `enqueue persists atomically in FIFO order and rehydrates cleanly across restarts`() {
        val dir = tempFolder.newFolder("queue-rehydrate")
        val time = FakeTimeSource()
        val queue1 = DiskBatchQueue(dir, time)

        val b1 = makePreparedBatch(1L, 1_795_000_000_000_000L, 1_795_000_010_000_000L, wireSizeBytes = 120)
        val b2 = makePreparedBatch(2L, 1_795_000_010_000_000L, 1_795_000_020_000_000L, wireSizeBytes = 150)

        assertTrue(queue1.enqueue(b1, maxBytes = 50_000_000, maxAgeS = 900) is EnqueueResult.Queued)
        assertTrue(queue1.enqueue(b2, maxBytes = 50_000_000, maxAgeS = 900) is EnqueueResult.Queued)
        assertEquals(2, queue1.size())
        assertEquals(270L, queue1.totalBytes())

        // Simulate app restart with a fresh DiskBatchQueue instance on the same directory
        val queue2 = DiskBatchQueue(dir, time)
        assertEquals(2, queue2.size())
        assertEquals(270L, queue2.totalBytes())

        val first = queue2.peekOldest(maxBytes = 50_000_000, maxAgeS = 900)!!
        assertEquals(1L, first.sequence)
        assertArrayEquals(b1.wireBytes, queue2.readPayload(first))

        queue2.removeAcked(first.queueId)
        val second = queue2.peekOldest(maxBytes = 50_000_000, maxAgeS = 900)!!
        assertEquals(2L, second.sequence)
        assertArrayEquals(b2.wireBytes, queue2.readPayload(second))
    }

    @Test
    fun `size eviction, age expiration, zero-limit disable, and carried dropped restoration preserve exact dropped stats`() {
        val dir = tempFolder.newFolder("queue-evict")
        val time = FakeTimeSource(wallMs = 1_795_000_000_000L)
        val queue = DiskBatchQueue(dir, time)

        // Enqueue 2 batches of 100 bytes each under maxBytes = 200
        val b1 = makePreparedBatch(10L, 1_795_000_000_000_000L, 1_795_000_010_000_000L, wireSizeBytes = 100)
        val b2 = makePreparedBatch(11L, 1_795_000_010_000_000L, 1_795_000_020_000_000L, wireSizeBytes = 100)
        assertTrue(queue.enqueue(b1, maxBytes = 200, maxAgeS = 900) is EnqueueResult.Queued)
        assertTrue(queue.enqueue(b2, maxBytes = 200, maxAgeS = 900) is EnqueueResult.Queued)

        // Enqueue b3 (100 bytes) -> evicts b1 (seq 10)
        val b3 = makePreparedBatch(12L, 1_795_000_020_000_000L, 1_795_000_030_000_000L, wireSizeBytes = 100)
        assertTrue(queue.enqueue(b3, maxBytes = 200, maxAgeS = 900) is EnqueueResult.Queued)
        assertEquals(2, queue.size())

        val droppedAfterSizeEvict = queue.consumePendingDroppedSummary()
        assertNotNull(droppedAfterSizeEvict)
        assertEquals(1, droppedAfterSizeEvict!!.count)
        assertEquals(10L, droppedAfterSizeEvict.firstDroppedSequence)
        assertEquals(1_795_000_000_000_000L, droppedAfterSizeEvict.startTimeUs)
        assertEquals(1_795_000_010_000_000L, droppedAfterSizeEvict.endTimeUs)
        assertNull(queue.consumePendingDroppedSummary())

        // Enqueue b4 carrying droppedAfterSizeEvict (count=1, seq=10) -> evicts b2 (seq 11)
        val b4 = makePreparedBatch(
            sequence = 13L,
            windowStartUs = 1_795_000_030_000_000L,
            closedUs = 1_795_000_040_000_000L,
            wireSizeBytes = 100,
            carriedDropped = droppedAfterSizeEvict,
        )
        assertTrue(queue.enqueue(b4, maxBytes = 200, maxAgeS = 900) is EnqueueResult.Queued)
        // Consume b2 drop (count=1) so we can verify b4's carried drop gets restored when b4 itself expires!
        assertEquals(11L, queue.consumePendingDroppedSummary()!!.firstDroppedSequence)

        // Advance time by 901 seconds (> maxAgeS = 900) -> expires both b3 (seq 12) and b4 (seq 13 + carried seq 10)!
        time.wallMs += 901_000L
        assertNull(queue.peekOldest(maxBytes = 200, maxAgeS = 900))
        assertEquals(0, queue.size())

        // Verify persisted dropped_state.json survives restart AND includes b3 + b4 + b4's carried drop (total = 3, firstSeq = 10)!
        val restarted = DiskBatchQueue(dir, time)
        val mergedDropped = restarted.consumePendingDroppedSummary()
        assertNotNull(mergedDropped)
        assertEquals(3, mergedDropped!!.count)
        assertEquals(10L, mergedDropped.firstDroppedSequence)
        assertEquals(1_795_000_000_000_000L, mergedDropped.startTimeUs)
        assertEquals(1_795_000_040_000_000L, mergedDropped.endTimeUs)

        // Zero-limit queue returns QueueDisabled
        val b5 = makePreparedBatch(14L, 1_795_000_040_000_000L, 1_795_000_050_000_000L, wireSizeBytes = 50)
        assertEquals(EnqueueResult.QueueDisabled, restarted.enqueue(b5, maxBytes = 0, maxAgeS = 900))
        assertEquals(EnqueueResult.QueueDisabled, restarted.enqueue(b5, maxBytes = 50_000, maxAgeS = 0))
    }
}
