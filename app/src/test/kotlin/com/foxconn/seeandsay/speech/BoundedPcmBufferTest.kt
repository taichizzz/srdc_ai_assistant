package com.foxconn.seeandsay.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies ordered PCM accumulation and hard-cap enforcement without microphone or speaker I/O.
 *
 * Tests execute synchronously on JUnit's thread and own no coroutine or cancellation. Assertion
 * failures identify ordering, truncation, or memory-bound regressions in the debug verification tool.
 */
class BoundedPcmBufferTest {

    /**
     * Verifies multiple chunks concatenate in capture order below the cap.
     *
     * @return This test has no return value.
     *
     * The test performs only in-memory copies and has no cancellation behavior or expected failure.
     */
    @Test
    fun appendsChunksAndProducesConcatenatedPcm() {
        val buffer = BoundedPcmBuffer(capacityBytes = 8)

        assertFalse(buffer.append(byteArrayOf(1, 2, 3)))
        assertFalse(buffer.append(byteArrayOf(4, 5)))

        assertEquals(5, buffer.sizeBytes)
        assertFalse(buffer.isLimitReached)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.toByteArray())
    }

    /**
     * Verifies the final chunk is truncated exactly at the cap and later bytes are discarded.
     *
     * @return This test has no return value.
     *
     * The test performs only bounded memory copies, has no cancellation behavior, and fails only if
     * the accumulator exceeds its limit or changes retained ordering.
     */
    @Test
    fun enforcesCapAndRetainsOnlyAvailablePrefix() {
        val buffer = BoundedPcmBuffer(capacityBytes = 5)

        assertFalse(buffer.append(byteArrayOf(1, 2, 3)))
        assertTrue(buffer.append(byteArrayOf(4, 5, 6, 7)))
        assertTrue(buffer.append(byteArrayOf(8)))

        assertEquals(5, buffer.sizeBytes)
        assertTrue(buffer.isLimitReached)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.toByteArray())
    }
}
