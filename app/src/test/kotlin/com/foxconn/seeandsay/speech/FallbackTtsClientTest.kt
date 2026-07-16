package com.foxconn.seeandsay.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies cloud-first/device-fallback routing with pure provider-neutral fake clients.
 *
 * Tests perform no Android, audio, credential, network, filesystem, or real-delay work. Routing is
 * confined to one test coroutine and cancellation is explicitly joined so no request leaks.
 */
class FallbackTtsClientTest {

    /**
     * Verifies a typed cloud synthesis/playback failure falls back once to device speech.
     *
     * @return This test has no return value.
     *
     * The cloud fake fails synchronously and the device fake records success. The test fails if text
     * changes, device is skipped, or cloud is retried.
     */
    @Test
    fun cloudFailureFallsBackToDevice() =
        runBlocking {
            val cloud =
                FakeTtsClient {
                    throw CloudTtsException(
                        CloudTtsFailureReason.Unavailable,
                        "fixed fake cloud failure",
                    )
                }
            val device = FakeTtsClient()
            val client = FallbackTtsClient(cloud, device) { true }

            client.speak("你好")

            assertEquals(listOf("你好"), cloud.requests)
            assertEquals(listOf("你好"), device.requests)
        }

    /**
     * Verifies CLOUD_TTS_ENABLED=false bypasses all cloud work.
     *
     * @return This test has no return value.
     *
     * The injected false flag is pure and the device fake completes immediately. The test fails if
     * cloud receives text or device is not selected exactly once.
     */
    @Test
    fun disabledCloudUsesDeviceOnly() =
        runBlocking {
            val cloud = FakeTtsClient()
            val device = FakeTtsClient()
            val client = FallbackTtsClient(cloud, device) { false }

            client.speak("離線")

            assertTrue(cloud.requests.isEmpty())
            assertEquals(listOf("離線"), device.requests)
        }

    /**
     * Verifies user cancellation never triggers unintended device fallback speech.
     *
     * @return This test has no return value.
     *
     * The cloud fake suspends until structured cancellation. The job is cancelled/joined without a
     * real delay; device must remain untouched and CancellationException must not become an error.
     */
    @Test
    fun cancellationDoesNotFallbackOrSurfaceUserError() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cloud =
                FakeTtsClient {
                    started.complete(Unit)
                    awaitCancellation()
                }
            val device = FakeTtsClient()
            val client = FallbackTtsClient(cloud, device) { true }
            val userFailures = mutableListOf<Throwable>()
            val job =
                launch {
                    try {
                        client.speak("取消")
                    } catch (_: CancellationException) {
                        // Expected Stop behavior is not a user-visible failure.
                    } catch (error: Throwable) {
                        userFailures += error
                    }
                }

            withTimeout(1_000) { started.await() }
            job.cancelAndJoin()

            assertTrue(device.requests.isEmpty())
            assertTrue(userFailures.isEmpty())
        }
}
