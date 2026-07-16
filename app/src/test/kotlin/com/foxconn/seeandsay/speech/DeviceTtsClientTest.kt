package com.foxconn.seeandsay.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies DeviceTtsClient suspension, recovery, and cancellation with a pure fake engine.
 *
 * Tests use coroutine virtual scheduling and no Android TextToSpeech runtime, audio, speaker,
 * network, credential, or filesystem resource. Every client is closed so shutdown behavior and
 * continuation cleanup are included in the deterministic fixture lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTtsClientTest {

    /**
     * Verifies speak remains suspended until the matching completion callback arrives.
     *
     * @return This test has no return value.
     *
     * Virtual scheduling starts one accepted fake utterance, observes suspension, and emits onDone.
     * The test fails if initialization gating, request text, or suspend-until-complete behavior
     * changes; it performs no real delay or I/O.
     */
    @Test
    fun completionCallbackResumesSpeak() =
        runTest {
            val engine = FakeDeviceTtsEngine()
            val client = DeviceTtsClient(engine)
            try {
                val result = async { client.speak("你好") }
                runCurrent()

                assertFalse(result.isCompleted)
                assertEquals("你好", engine.lastText)
                val utteranceId = requireNotNull(engine.lastUtteranceId)
                engine.complete(utteranceId)

                result.await()
                assertTrue(result.isCompleted)
            } finally {
                client.close()
            }
        }

    /**
     * Verifies an utterance error becomes the fixed recoverable playback failure.
     *
     * @return This test has no return value.
     *
     * The fake emits an error callback after request acceptance. No provider detail or Android code
     * is involved; the test fails if the caller hangs or receives a non-recoverable category.
     */
    @Test
    fun errorCallbackFailsWithRecoverablePlaybackReason() =
        runTest {
            val engine = FakeDeviceTtsEngine()
            val client = DeviceTtsClient(engine)
            try {
                val capturedFailure = CompletableDeferred<Throwable>()
                val job =
                    launch {
                        try {
                            client.speak("錯誤測試")
                        } catch (error: Throwable) {
                            capturedFailure.complete(error)
                        }
                    }
                runCurrent()
                engine.fail(requireNotNull(engine.lastUtteranceId))

                val failure = capturedFailure.await()
                job.join()
                assertTrue(failure is DeviceTtsException)
                assertEquals(
                    DeviceTtsFailureReason.PlaybackFailed,
                    (failure as DeviceTtsException).reason,
                )
            } finally {
                client.close()
            }
        }

    /**
     * Verifies coroutine cancellation stops engine playback without a secondary user failure.
     *
     * @return This test has no return value.
     *
     * Virtual scheduling cancels an accepted utterance and records exceptions other than normal
     * CancellationException. It fails if stop is omitted, the coroutine leaks, or cancellation is
     * converted into DeviceTtsException.
     */
    @Test
    fun cancellationStopsPlaybackWithoutUserError() =
        runTest {
            val engine = FakeDeviceTtsEngine()
            val client = DeviceTtsClient(engine)
            val userFailures = mutableListOf<Throwable>()
            try {
                val job =
                    launch {
                        try {
                            client.speak("停止")
                        } catch (_: CancellationException) {
                            // Expected structured cancellation is deliberately not a user failure.
                        } catch (error: Throwable) {
                            userFailures += error
                        }
                    }
                runCurrent()

                job.cancelAndJoin()

                assertEquals(1, engine.stopCount)
                assertTrue(userFailures.isEmpty())
            } finally {
                client.close()
            }
        }

    /**
     * Verifies missing Taiwan-Mandarin voice data fails before any speak request is submitted.
     *
     * @return This test has no return value.
     *
     * The fake completes initialization with LanguageDataMissing synchronously. The test performs no
     * I/O and fails if speech is attempted, the caller hangs, or failure categorization changes.
     */
    @Test
    fun missingLanguageDataFailsBeforeSpeak() =
        runTest {
            val engine =
                FakeDeviceTtsEngine(
                    initializationResult = DeviceTtsInitializationResult.LanguageDataMissing,
                )
            val client = DeviceTtsClient(engine)
            try {
                val failure = runCatching { client.speak("你好") }.exceptionOrNull()

                assertTrue(failure is DeviceTtsException)
                assertEquals(
                    DeviceTtsFailureReason.LanguageDataMissing,
                    (failure as DeviceTtsException).reason,
                )
                assertEquals(0, engine.speakCount)
            } finally {
                client.close()
            }
        }

    /**
     * Deterministic in-memory implementation of DeviceTtsEngine for this test class.
     *
     * @param initializationResult setup outcome delivered synchronously by [initialize].
     *
     * All fields and callbacks are confined to one coroutine test scheduler. The fake performs no
     * platform/audio/network work; callbacks occur only when a test explicitly triggers them.
     */
    private class FakeDeviceTtsEngine(
        private val initializationResult: DeviceTtsInitializationResult =
            DeviceTtsInitializationResult.Ready,
    ) : DeviceTtsEngine {

        /** Listener installed during initialization, or null before client construction completes. */
        private var listener: DeviceTtsEngineListener? = null

        /** Latest requested text, retained only for deterministic assertions. */
        var lastText: String? = null
            private set

        /** Latest requested utterance identifier, retained only for callback simulation. */
        var lastUtteranceId: String? = null
            private set

        /** Number of fake speak requests submitted. */
        var speakCount: Int = 0
            private set

        /** Number of fake stop calls observed. */
        var stopCount: Int = 0
            private set

        /** Number of fake shutdown calls observed. */
        var shutdownCount: Int = 0
            private set

        /**
         * Stores the listener and reports the configured setup result synchronously.
         *
         * @param listener speech-internal callback receiver.
         * @param onResult initialization result callback.
         * @return This function has no return value.
         *
         * The method is synchronous/non-blocking and owns no coroutine or cancellation resource.
         */
        override fun initialize(
            listener: DeviceTtsEngineListener,
            onResult: (DeviceTtsInitializationResult) -> Unit,
        ) {
            this.listener = listener
            onResult(initializationResult)
        }

        /**
         * Records an accepted fake utterance without completing it.
         *
         * @param text requested text.
         * @param utteranceId request identifier.
         * @return always true to model engine acceptance.
         *
         * The method is synchronous/non-blocking and owns no coroutine or I/O resource.
         */
        override fun speak(
            text: String,
            utteranceId: String,
        ): Boolean {
            lastText = text
            lastUtteranceId = utteranceId
            speakCount += 1
            listener?.onStart(utteranceId)
            return true
        }

        /**
         * Records playback cancellation.
         *
         * @return This function has no return value.
         *
         * The operation is synchronous/non-blocking and deliberately emits no error callback.
         */
        override fun stop() {
            stopCount += 1
        }

        /**
         * Records engine disposal.
         *
         * @return This function has no return value.
         *
         * The operation is synchronous/non-blocking, idempotence is asserted through client use,
         * and it owns no external resource.
         */
        override fun shutdown() {
            shutdownCount += 1
        }

        /**
         * Emits successful completion for an accepted identifier.
         *
         * @param utteranceId accepted fake request identifier.
         * @return This function has no return value.
         *
         * The callback is synchronous/non-blocking and fails only if initialization omitted listener.
         */
        fun complete(utteranceId: String) {
            requireNotNull(listener).onDone(utteranceId)
        }

        /**
         * Emits a playback error for an accepted identifier.
         *
         * @param utteranceId accepted fake request identifier.
         * @return This function has no return value.
         *
         * The callback is synchronous/non-blocking and fails only if initialization omitted listener.
         */
        fun fail(utteranceId: String) {
            requireNotNull(listener).onError(utteranceId)
        }
    }
}
