package com.foxconn.seeandsay.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies provider-neutral TTS routing without Android audio, credentials, or network access.
 *
 * Tests execute immediate fake clients on the calling coroutine and leave no suspended or external
 * resources. Failures identify model-selection routing regressions independently of Compose.
 */
class SwitchableTtsClientTest {

    /**
     * Verifies the baseline handles the first call and selection routes only later calls.
     *
     * @return This test has no return value.
     *
     * Both fakes complete immediately on the test thread. The test fails if selection duplicates an
     * utterance, mutates the earlier call, or delegates to an unselected client.
     */
    @Test
    fun selectionRoutesSubsequentUtterances() =
        runBlocking {
            val baseline = FakeTtsClient()
            val gemini = FakeTtsClient()
            val client =
                SwitchableTtsClient(
                    initialClient = baseline,
                    clients = listOf(baseline, gemini),
                )

            client.speak("基準")
            client.select(gemini)
            client.speak("Gemini")

            assertEquals(listOf("基準"), baseline.requests)
            assertEquals(listOf("Gemini"), gemini.requests)
            client.close()
        }
}
