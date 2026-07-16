package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.MainDispatcherRule
import com.foxconn.seeandsay.speech.CloudTtsException
import com.foxconn.seeandsay.speech.CloudTtsFailureReason
import com.foxconn.seeandsay.speech.FakeTtsClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies standalone Phase 1 TTS state transitions against provider-neutral fake clients.
 *
 * Tests replace Dispatchers.Main with a controlled scheduler and perform no Android, audio,
 * credential, or network work. Every suspended fake is explicitly completed or cancelled, so test
 * completion proves lifecycle work does not leak.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsViewModelTest {

    /** Controlled Dispatchers.Main replacement required by ViewModel.viewModelScope. */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Verifies Speak exposes Speaking while suspended and Completed after client completion.
     *
     * @return This test has no return value.
     *
     * Virtual scheduling starts a fake request and releases it through a Deferred without real
     * delay. It fails if text normalization, request delegation, or status ordering regresses.
     */
    @Test
    fun speakTransitionsFromSpeakingToCompleted() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val client =
                FakeTtsClient {
                    started.complete(Unit)
                    finish.await()
                }
            val viewModel = TtsViewModel(client)

            viewModel.onSpeakRequested(" 你好 ")

            assertEquals(TtsStatus.Speaking, viewModel.uiState.value.status)
            runCurrent()
            assertTrue(started.isCompleted)
            assertEquals(listOf("你好"), client.requests)
            finish.complete(Unit)
            advanceUntilIdle()
            assertEquals(TtsStatus.Completed, viewModel.uiState.value.status)
            assertEquals("你好", viewModel.uiState.value.currentText)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Verifies Stop cancels the active fake request and restores a usable Idle state.
     *
     * @return This test has no return value.
     *
     * Virtual scheduling observes cancellation through a Deferred with no real delay. It fails if
     * cancellation is shown as Error, current text is lost, or the fake coroutine remains active.
     */
    @Test
    fun stopCancelsSpeakingAndReturnsToIdle() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val client =
                FakeTtsClient {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val viewModel = TtsViewModel(client)
            viewModel.onSpeakRequested("停止測試")
            runCurrent()
            assertTrue(started.isCompleted)

            viewModel.onStopRequested()
            runCurrent()

            assertTrue(cancelled.isCompleted)
            assertEquals(TtsStatus.Idle, viewModel.uiState.value.status)
            assertEquals("停止測試", viewModel.uiState.value.currentText)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Verifies a new Speak request cancels and joins the prior utterance before replacement starts.
     *
     * @return This test has no return value.
     *
     * Virtual-time Deferred signals prove the first fake is cancelled before the second request is
     * delegated. The test fails if stale completion overwrites the replacement state, cancellation
     * becomes Error, either request is duplicated, or lifecycle work leaks.
     */
    @Test
    fun newSpeakCancelsAndReplacesPriorUtterance() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val secondFinish = CompletableDeferred<Unit>()
            val client =
                FakeTtsClient { text ->
                    if (text == "第一句") {
                        firstStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            firstCancelled.complete(Unit)
                        }
                    } else {
                        secondStarted.complete(Unit)
                        secondFinish.await()
                    }
                }
            val viewModel = TtsViewModel(client)
            viewModel.onSpeakRequested("第一句")
            runCurrent()
            assertTrue(firstStarted.isCompleted)

            viewModel.onSpeakRequested("第二句")
            assertEquals(TtsStatus.Speaking, viewModel.uiState.value.status)
            assertEquals("第二句", viewModel.uiState.value.currentText)
            runCurrent()

            assertTrue(firstCancelled.isCompleted)
            assertTrue(secondStarted.isCompleted)
            assertEquals(listOf("第一句", "第二句"), client.requests)
            assertEquals(TtsStatus.Speaking, viewModel.uiState.value.status)
            secondFinish.complete(Unit)
            runCurrent()
            assertEquals(TtsStatus.Completed, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Verifies a typed cloud failure becomes recoverable Error and a later Speak can succeed.
     *
     * @return This test has no return value.
     *
     * The first fake call throws CloudTtsException inside virtual-time work and the second completes.
     * No provider detail reaches state; failure occurs only if typed cloud recovery or fixed-message
     * handling regresses.
     */
    @Test
    fun clientFailureIsRecoverableByNextSpeak() =
        runTest {
            var requestCount = 0
            val client =
                FakeTtsClient {
                    requestCount += 1
                    if (requestCount == 1) {
                        throw CloudTtsException(
                            CloudTtsFailureReason.Unavailable,
                            "provider detail must remain hidden",
                        )
                    }
                }
            val viewModel = TtsViewModel(client)

            viewModel.onSpeakRequested("第一次")
            advanceUntilIdle()
            assertEquals(TtsStatus.Error, viewModel.uiState.value.status)
            assertEquals(
                "Text-to-speech failed. Please try again.",
                viewModel.uiState.value.errorMessage,
            )

            viewModel.onSpeakRequested("第二次")
            advanceUntilIdle()
            assertEquals(TtsStatus.Completed, viewModel.uiState.value.status)
            assertEquals("第二次", viewModel.uiState.value.currentText)
            assertNull(viewModel.uiState.value.errorMessage)
        }
}
