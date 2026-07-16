package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.speech.SttResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies DEBUG evaluation latency math and CSV output using coroutine virtual time.
 *
 * Tests inject a clock derived from the test scheduler and perform no real delay, Android, audio,
 * network, logging, or credential work. The tracker owns no coroutine resource, so test completion
 * requires no cleanup beyond the runTest scope.
 */
class DebugSttMetricsTest {

    /**
     * Verifies first-token, Stop-to-final, total latency, transcript, outcome, and CSV ordering.
     *
     * @return This test has no return value.
     *
     * [runTest] advances only virtual time: stream starts at 0 ms, first audio at 25 ms, interim at
     * 145 ms, Stop at 225 ms, and final at 525 ms. It fails if the three distinct boundaries are
     * conflated or CSV escaping/order changes; no real waiting or external I/O occurs.
     */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun computesRequiredLatenciesAndCsvWithVirtualTime() =
        runTest {
            val clock = MonotonicClock { testScheduler.currentTime * NANOS_PER_MILLISECOND }
            val tracker = DebugSttMetricsTracker(DebugSttEngine.V2Chirp3, clock)

            tracker.onStreamStarted()
            delay(25)
            tracker.onAudioChunkSent()
            delay(120)
            tracker.onResult(SttResult("你", isFinal = false))
            delay(80)
            tracker.onStopRequested()
            delay(300)
            tracker.onResult(SttResult("你好, \"IVI\"", isFinal = true, confidence = 0.9f))

            val metrics = tracker.snapshot(DebugSttOutcome.Success)
            assertEquals(120L, metrics.firstTokenLatencyMs)
            assertEquals(300L, metrics.finalSentenceLatencyMs)
            assertEquals(525L, metrics.totalLatencyMs)
            assertEquals(DebugSttOutcome.Success, metrics.outcome)
            assertEquals("你好, \"IVI\"", metrics.transcript)
            assertEquals(
                "v2,chirp_3,120,300,525,success,\"你好, \"\"IVI\"\"\"",
                metrics.toCsvLine(),
            )
            assertTrue(tracker.hasFinalResult())
        }

    private companion object {
        /** Nanoseconds per virtual scheduler millisecond. */
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
