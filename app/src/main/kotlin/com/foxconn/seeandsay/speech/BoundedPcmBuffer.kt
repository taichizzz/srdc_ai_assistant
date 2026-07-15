package com.foxconn.seeandsay.speech

import java.io.ByteArrayOutputStream

/**
 * Accumulates PCM chunks up to a fixed byte cap for debug record-then-playback verification.
 *
 * @param capacityBytes maximum number of bytes retained; must be positive.
 * @throws IllegalArgumentException when [capacityBytes] is not positive.
 *
 * Instances are intentionally confined to one capture coroutine and are not thread-safe. Methods
 * perform in-memory work without suspension, I/O, or cancellation behavior. Allocation failure may
 * surface as the JVM's normal memory error, while excess input is truncated rather than thrown.
 */
class BoundedPcmBuffer(
    val capacityBytes: Int,
) {
    private val output = ByteArrayOutputStream(capacityBytes)

    /** Number of PCM bytes currently retained. */
    val sizeBytes: Int
        get() = output.size()

    /** Whether at least one append filled or attempted to exceed the configured cap. */
    var isLimitReached: Boolean = false
        private set

    init {
        require(capacityBytes > 0) { "PCM buffer capacity must be positive." }
    }

    /**
     * Appends as much of one immutable capture chunk as the remaining capacity permits.
     *
     * @param chunk PCM bytes emitted by the shared microphone Flow; an empty array is accepted.
     * @return `true` when this append reaches or encounters the cap, otherwise `false`.
     *
     * This method must be called from the buffer's owning capture coroutine. It does not suspend
     * and cannot be cancelled mid-copy. It throws no project-specific failure; data beyond the cap
     * is deliberately discarded to keep debug memory use bounded.
     */
    fun append(chunk: ByteArray): Boolean {
        val remainingBytes = capacityBytes - sizeBytes
        if (remainingBytes <= 0) {
            isLimitReached = true
            return true
        }

        val acceptedBytes = minOf(remainingBytes, chunk.size)
        if (acceptedBytes > 0) {
            output.write(chunk, 0, acceptedBytes)
        }
        if (acceptedBytes < chunk.size || sizeBytes == capacityBytes) {
            isLimitReached = true
        }
        return isLimitReached
    }

    /**
     * Produces an independent concatenated PCM snapshot in original append order.
     *
     * @return a new byte array containing all retained PCM data.
     *
     * This in-memory copy must run on the owning coroutine, does not suspend, and has no
     * cancellation behavior. Allocation failure may surface as the JVM's normal memory error.
     */
    fun toByteArray(): ByteArray = output.toByteArray()
}
