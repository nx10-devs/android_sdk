package com.nx10.asparagus.batch

import com.nx10.asparagus.contract.Batch

/**
 * Aggregated dropped-batch statistics carried into the next batch's `Header.batches_dropped`
 * and `GAP_CAUSE_BATCHES_DROPPED` record (`batch.proto` lines 92-94, 523-525).
 */
data class DroppedBatchesSummary(
    val count: Int,
    val firstDroppedSequence: Long,
    val startTimeUs: Long,
    val endTimeUs: Long,
) {
    fun merge(other: DroppedBatchesSummary): DroppedBatchesSummary {
        if (count <= 0) return other
        if (other.count <= 0) return this
        return DroppedBatchesSummary(
            count = (count.toLong() + other.count.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            firstDroppedSequence = when {
                firstDroppedSequence <= 0L -> other.firstDroppedSequence
                other.firstDroppedSequence <= 0L -> firstDroppedSequence
                else -> minOf(firstDroppedSequence, other.firstDroppedSequence)
            },
            startTimeUs = minOf(startTimeUs, other.startTimeUs),
            endTimeUs = maxOf(endTimeUs, other.endTimeUs),
        )
    }
}

/**
 * Validated, serialized, and optionally gzipped batch ready for disk persistence and upload.
 */
data class PreparedBatch(
    val batch: Batch,
    val rawProtoBytes: ByteArray,
    val wireBytes: ByteArray,
    val compressed: Boolean,
    val carriedDroppedSummary: DroppedBatchesSummary? = null,
) {
    val idOrg: String get() = batch.header?.idOrg.orEmpty()
    val idDeployment: String get() = batch.header?.idDeployment.orEmpty()
    val idSource: String get() = batch.header?.idSource.orEmpty()
    val idSession: String get() = batch.header?.idSession.orEmpty()
    val sequence: Long get() = batch.header?.sequence ?: 0L
    val windowStartUs: Long get() = batch.header?.windowStartUs ?: 0L
    val closedUs: Long get() = batch.header?.closedUs ?: 0L
    val batchesDropped: Int get() = batch.header?.batchesDropped ?: 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedBatch) return false
        return batch == other.batch &&
            rawProtoBytes.contentEquals(other.rawProtoBytes) &&
            wireBytes.contentEquals(other.wireBytes) &&
            compressed == other.compressed &&
            carriedDroppedSummary == other.carriedDroppedSummary
    }

    override fun hashCode(): Int {
        var result = batch.hashCode()
        result = 31 * result + rawProtoBytes.contentHashCode()
        result = 31 * result + wireBytes.contentHashCode()
        result = 31 * result + compressed.hashCode()
        result = 31 * result + (carriedDroppedSummary?.hashCode() ?: 0)
        return result
    }
}
