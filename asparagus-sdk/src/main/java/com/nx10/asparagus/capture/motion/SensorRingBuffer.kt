package com.nx10.asparagus.capture.motion

/**
 * Overflow window recorded when [SensorRingBuffer] overwrites oldest unread samples.
 */
data class RingOverflow(
    val startTimeUs: Long,
    val endTimeUs: Long,
    val droppedCount: Int,
)

/**
 * Thread-safe, fixed-capacity primitive ring buffer for high-frequency native sensor callbacks.
 *
 * Uses parallel primitive arrays so sensor callbacks allocate zero objects on the sensor thread.
 */
class SensorRingBuffer(
    val capacity: Int,
    val nativeBufferS: Int,
) {
    init {
        require(capacity >= 1) { "Ring buffer capacity must be >= 1: $capacity" }
        require(nativeBufferS >= 0) { "nativeBufferS must be >= 0: $nativeBufferS" }
    }

    private val lock = Any()
    private val sensorNumbers = IntArray(capacity)
    private val timestampsUs = LongArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)

    private var head: Int = 0
    private var tail: Int = 0
    private var count: Int = 0
    private var paused: Boolean = false

    private var hasOverflow: Boolean = false
    private var overflowStartUs: Long = 0L
    private var overflowEndUs: Long = 0L
    private var overflowDroppedCount: Int = 0

    val size: Int
        get() = synchronized(lock) { count }

    fun setPaused(isPaused: Boolean) = synchronized(lock) {
        paused = isPaused
    }

    /**
     * Pushes a raw sensor sample into the ring buffer with zero allocations.
     * Returns `false` if dropped because the app is paused and `nativeBufferS == 0`.
     */
    fun push(
        sensorProtoNumber: Int,
        timeUs: Long,
        x: Float,
        y: Float,
        z: Float,
    ): Boolean = synchronized(lock) {
        if (paused && nativeBufferS == 0) {
            return false
        }

        if (count == capacity) {
            val overwrittenTs = timestampsUs[head]
            if (!hasOverflow) {
                overflowStartUs = overwrittenTs
                overflowEndUs = overwrittenTs
                hasOverflow = true
            } else {
                if (overwrittenTs < overflowStartUs) overflowStartUs = overwrittenTs
                if (overwrittenTs > overflowEndUs) overflowEndUs = overwrittenTs
            }
            overflowDroppedCount++
            head = (head + 1) % capacity
            count--
        }

        sensorNumbers[tail] = sensorProtoNumber
        timestampsUs[tail] = timeUs
        xs[tail] = x
        ys[tail] = y
        zs[tail] = z
        tail = (tail + 1) % capacity
        count++
        true
    }

    /**
     * Atomically drains all queued samples and returns any [RingOverflow] that occurred since the last drain.
     */
    fun drainTo(
        consumer: (sensorProtoNumber: Int, timeUs: Long, x: Float, y: Float, z: Float) -> Unit,
    ): RingOverflow? {
        val snapCount: Int
        val snapSensors: IntArray
        val snapTimes: LongArray
        val snapX: FloatArray
        val snapY: FloatArray
        val snapZ: FloatArray
        val overflow: RingOverflow?

        synchronized(lock) {
            overflow = if (hasOverflow) {
                RingOverflow(overflowStartUs, overflowEndUs, overflowDroppedCount)
            } else {
                null
            }
            hasOverflow = false
            overflowStartUs = 0L
            overflowEndUs = 0L
            overflowDroppedCount = 0

            snapCount = count
            if (snapCount == 0) {
                return overflow
            }

            snapSensors = IntArray(snapCount)
            snapTimes = LongArray(snapCount)
            snapX = FloatArray(snapCount)
            snapY = FloatArray(snapCount)
            snapZ = FloatArray(snapCount)

            val firstChunk = minOf(snapCount, capacity - head)
            System.arraycopy(sensorNumbers, head, snapSensors, 0, firstChunk)
            System.arraycopy(timestampsUs, head, snapTimes, 0, firstChunk)
            System.arraycopy(xs, head, snapX, 0, firstChunk)
            System.arraycopy(ys, head, snapY, 0, firstChunk)
            System.arraycopy(zs, head, snapZ, 0, firstChunk)

            val secondChunk = snapCount - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(sensorNumbers, 0, snapSensors, firstChunk, secondChunk)
                System.arraycopy(timestampsUs, 0, snapTimes, firstChunk, secondChunk)
                System.arraycopy(xs, 0, snapX, firstChunk, secondChunk)
                System.arraycopy(ys, 0, snapY, firstChunk, secondChunk)
                System.arraycopy(zs, 0, snapZ, firstChunk, secondChunk)
            }

            head = 0
            tail = 0
            count = 0
        }

        for (i in 0 until snapCount) {
            consumer(snapSensors[i], snapTimes[i], snapX[i], snapY[i], snapZ[i])
        }
        return overflow
    }

    fun clear() = synchronized(lock) {
        head = 0
        tail = 0
        count = 0
        hasOverflow = false
        overflowStartUs = 0L
        overflowEndUs = 0L
        overflowDroppedCount = 0
    }

    companion object {
        const val MIN_CAPACITY: Int = 1_024
        const val MAX_CAPACITY: Int = 200_000
        const val MIN_BUFFER_SECONDS: Int = 30

        fun computeCapacity(
            accelerometerHz: Int,
            gyroscopeHz: Int,
            gravityHz: Int,
            nativeBufferS: Int,
            magnetometerHz: Int = 0,
        ): Int {
            val totalHz = maxOf(
                1,
                accelerometerHz.coerceAtLeast(0) +
                    gyroscopeHz.coerceAtLeast(0) +
                    gravityHz.coerceAtLeast(0) +
                    magnetometerHz.coerceAtLeast(0),
            )
            val bufferSeconds = maxOf(nativeBufferS.coerceAtLeast(0), MIN_BUFFER_SECONDS)
            return (totalHz.toLong() * bufferSeconds.toLong())
                .coerceIn(MIN_CAPACITY.toLong(), MAX_CAPACITY.toLong())
                .toInt()
        }
    }
}
