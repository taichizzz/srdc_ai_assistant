package com.foxconn.seeandsay.speech

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies CloudTtsClient WAV parsing, playback delegation, malformed input, and cancellation.
 *
 * Tests inject pure synthesis/player fakes and representative in-memory RIFF bytes. They perform no
 * Android, AudioTrack, network, credential, filesystem, or fixed-delay work. Client cleanup is
 * deterministic and every suspended fake is completed or cancelled by the owning test.
 */
class CloudTtsClientTest {

    /**
     * Verifies RIFF metadata is parsed and only the `data` payload reaches PCM playback.
     *
     * @return This test has no return value.
     *
     * A representative WAV includes an odd-sized padded metadata chunk, proving the parser does not
     * assume a fixed 44-byte header. The fake completes immediately and records headerless PCM.
     */
    @Test
    fun successfulSpeakStripsWavChunksBeforePlayback() =
        runBlocking {
            val expectedPcm = byteArrayOf(1, 2, 3, 4, 5, 6)
            val audio = synthesizedAudio(createLinear16Wav(expectedPcm, includeJunkChunk = true))
            val player = RecordingPcmPlayer()
            val client =
                CloudTtsClient(
                    synthesizeAction = { audio },
                    pcmPlayer = player,
                )

            client.speak("你好")

            val played = requireNotNull(player.playedAudio)
            assertArrayEquals(expectedPcm, played.pcmBytes)
            assertEquals(24_000, played.sampleRateHz)
            assertEquals(1, played.channelCount)
            assertEquals(16, played.bitsPerSample)
            client.close()
        }

    /**
     * Verifies a short/non-WAV response becomes a typed recoverable malformed-audio failure.
     *
     * @return This test has no return value.
     *
     * The pure fake returns four invalid bytes. The test fails if the player is called, parsing
     * crashes with an indexing exception, or the failure category changes.
     */
    @Test
    fun malformedWavFailsRecoverablyWithoutPlayback() =
        runBlocking {
            val player = RecordingPcmPlayer()
            val client =
                CloudTtsClient(
                    synthesizeAction = { synthesizedAudio(byteArrayOf(1, 2, 3, 4)) },
                    pcmPlayer = player,
                )

            val failure = runCatching { client.speak("損壞") }.exceptionOrNull()

            assertTrue(failure is CloudTtsException)
            assertEquals(
                CloudTtsFailureReason.MalformedAudio,
                (failure as CloudTtsException).reason,
            )
            assertEquals(null, player.playedAudio)
            client.close()
        }

    /**
     * Verifies caller cancellation reaches active playback and never becomes a user error.
     *
     * @return This test has no return value.
     *
     * Event-driven Deferred signals replace real audio and time. The fake player records cleanup in
     * its cancellation finally block; any non-CancellationException is treated as a user failure.
     */
    @Test
    fun cancellationStopsPlaybackWithoutUserError() =
        runBlocking {
            val player = CancellablePcmPlayer()
            val userFailures = mutableListOf<Throwable>()
            val audio = synthesizedAudio(createLinear16Wav(byteArrayOf(1, 2, 3, 4)))
            val client =
                CloudTtsClient(
                    synthesizeAction = { audio },
                    pcmPlayer = player,
                )
            val job =
                launch {
                    try {
                        client.speak("停止")
                    } catch (_: CancellationException) {
                        // Expected structured cancellation is not a user-visible failure.
                    } catch (error: Throwable) {
                        userFailures += error
                    }
                }

            withTimeout(1_000) { player.started.await() }
            job.cancelAndJoin()
            withTimeout(1_000) { player.stopped.await() }

            assertTrue(userFailures.isEmpty())
            client.close()
        }

    /**
     * Creates provider-neutral metadata around one fake Google WAV response.
     *
     * @param bytes complete fake WAV container.
     * @return SynthesizedAudio matching Phase 3's 24 kHz mono PCM16 contract.
     *
     * This pure helper performs no I/O/suspension and owns no cancellation resource.
     */
    private fun synthesizedAudio(bytes: ByteArray): SynthesizedAudio =
        SynthesizedAudio(
            bytes = bytes,
            sampleRateHz = 24_000,
            channelCount = 1,
            bitsPerSample = 16,
            format = SynthesizedAudioFormat.Linear16Wav,
        )

    /**
     * Builds a representative little-endian PCM WAV container for parser tests.
     *
     * @param pcm frame-aligned PCM16 data chunk.
     * @param includeJunkChunk whether to insert an odd-sized padded metadata chunk before `data`.
     * @return complete RIFF/WAVE bytes with valid `fmt ` and `data` chunks.
     *
     * The helper writes only to memory, performs no suspension, and owns no cancellation resource.
     * Invalid PCM alignment fails synchronously through require.
     */
    private fun createLinear16Wav(
        pcm: ByteArray,
        includeJunkChunk: Boolean = false,
    ): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0)
        val body = ByteArrayOutputStream()
        body.writeAscii("WAVE")
        body.writeAscii("fmt ")
        body.writeLittleEndianInt(16)
        body.writeLittleEndianShort(1)
        body.writeLittleEndianShort(1)
        body.writeLittleEndianInt(24_000)
        body.writeLittleEndianInt(48_000)
        body.writeLittleEndianShort(2)
        body.writeLittleEndianShort(16)
        if (includeJunkChunk) {
            body.writeAscii("JUNK")
            body.writeLittleEndianInt(3)
            body.write(byteArrayOf(9, 8, 7))
            body.write(0)
        }
        body.writeAscii("data")
        body.writeLittleEndianInt(pcm.size)
        body.write(pcm)

        val result = ByteArrayOutputStream()
        result.writeAscii("RIFF")
        result.writeLittleEndianInt(body.size())
        result.write(body.toByteArray())
        return result.toByteArray()
    }

    /**
     * Writes a four-character ASCII RIFF identifier.
     *
     * @receiver in-memory WAV output.
     * @param value exactly four ASCII characters.
     * @return This function has no return value.
     *
     * This pure test helper performs bounded memory writes, no external I/O/suspension, and fails
     * synchronously when the identifier length is invalid.
     */
    private fun ByteArrayOutputStream.writeAscii(value: String) {
        require(value.length == 4)
        write(value.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Writes an unsigned-compatible 16-bit little-endian test value.
     *
     * @receiver in-memory WAV output.
     * @param value integer whose low 16 bits are written.
     * @return This function has no return value.
     *
     * This pure helper performs two bounded memory writes and has no suspension/cancellation.
     */
    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    /**
     * Writes a 32-bit little-endian test value.
     *
     * @receiver in-memory WAV output.
     * @param value integer written least-significant byte first.
     * @return This function has no return value.
     *
     * This pure helper performs four bounded memory writes and has no suspension/cancellation.
     */
    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    /**
     * Records the latest PCM playback request and completes immediately.
     *
     * The fake is confined to one test coroutine, performs no platform/I/O work, and has no
     * independent failure or cancellation behavior.
     */
    private class RecordingPcmPlayer : TtsPcmPlayer {
        /** Latest provider-neutral PCM request, or null before playback. */
        var playedAudio: PcmPlaybackAudio? = null
            private set

        /**
         * Records PCM and returns as successful completed playback.
         *
         * @param audio parsed PCM request.
         * @return immediately after recording.
         *
         * This suspend fake performs no I/O, blocking, or cancellation work.
         */
        override suspend fun play(audio: PcmPlaybackAudio) {
            playedAudio = audio
        }

        /**
         * Accepts lifecycle Stop without additional work.
         *
         * @return This function has no return value.
         *
         * This fake has no resource, I/O, failure, suspension, or cancellation behavior.
         */
        override fun stop() = Unit
    }

    /**
     * Suspends playback until cancellation and records cleanup.
     *
     * Deferred signals are event-driven and confined to the coroutine test. The fake performs no
     * Android/audio/network work; [stop] is idempotent.
     */
    private class CancellablePcmPlayer : TtsPcmPlayer {
        /** Signals that cloud PCM reached the player. */
        val started = CompletableDeferred<Unit>()

        /** Signals that cancellation or explicit Stop ended fake playback. */
        val stopped = CompletableDeferred<Unit>()

        /**
         * Signals start, suspends forever, and records cancellation cleanup.
         *
         * @param audio parsed PCM request, deliberately not retained.
         * @return only if externally cancelled, so normal return is not expected.
         *
         * The fake runs on the caller's coroutine and performs no external I/O. Cancellation
         * propagates after `finally` completes the stopped signal.
         */
        override suspend fun play(audio: PcmPlaybackAudio) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stop()
            }
        }

        /**
         * Completes the idempotent stopped signal.
         *
         * @return This function has no return value.
         *
         * It is thread-safe/non-blocking and performs no I/O or suspension.
         */
        override fun stop() {
            stopped.complete(Unit)
        }
    }
}
