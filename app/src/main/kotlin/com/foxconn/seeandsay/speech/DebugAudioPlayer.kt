package com.foxconn.seeandsay.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Defines the cancellation-safe raw-PCM playback boundary used only by the debug loopback tool.
 *
 * Implementations must suspend until playback completes, keep platform callbacks inside `speech/`,
 * perform blocking work away from the main thread, and release playback resources on cancellation.
 */
fun interface PcmAudioPlayer {

    /**
     * Plays one complete PCM16 mono buffer at the shared 16 kHz format.
     *
     * @param pcm immutable, frame-aligned raw PCM data to play.
     * @return when playback has reached the final frame.
     *
     * Implementations may fail for empty/invalid audio, unsupported output, initialization, write,
     * or playback timeout. Cancellation must stop playback and release native resources before the
     * function returns control to its caller.
     */
    suspend fun play(pcm: ByteArray)
}

/**
 * Reports a recoverable raw-PCM debug playback failure to the ViewModel.
 *
 * @param message non-secret user-readable description of the playback failure.
 * @param cause optional platform exception that caused the failure.
 *
 * Construction performs no I/O and is safe on any dispatcher. Throwing it cancels the current
 * playback call; [DebugAudioPlayer] releases AudioTrack from its `finally` block.
 */
class DebugAudioPlaybackException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Plays captured verification audio through AudioTrack without using Android's TTS engine.
 *
 * @param ioDispatcher dispatcher used for AudioTrack creation and blocking PCM writes.
 *
 * [play] switches to [ioDispatcher], wraps AudioTrack's marker callback inside this package, and
 * suspends until the last PCM frame is played. Setup, output, write, and timeout failures surface as
 * [DebugAudioPlaybackException]. Cancellation stops and releases the track in `finally`.
 */
class DebugAudioPlayer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PcmAudioPlayer {

    /**
     * Plays one complete 16 kHz mono PCM16 buffer and waits for its final frame marker.
     *
     * @param pcm immutable, non-empty, frame-aligned raw PCM bytes.
     * @return after Android reports the final playback marker.
     * @throws DebugAudioPlaybackException for invalid PCM, unsupported output, initialization,
     * write, start, or completion-timeout failures.
     *
     * All blocking work runs on [ioDispatcher]. The marker wait is cancellable and event-driven;
     * cancellation stops the track promptly, while `finally` always removes the internal callback
     * and releases AudioTrack.
     */
    override suspend fun play(pcm: ByteArray) {
        require(pcm.isNotEmpty()) { "Debug PCM playback requires non-empty audio." }
        val frameCount =
            try {
                AudioConfig.frameCountForBytes(pcm.size)
            } catch (error: IllegalArgumentException) {
                throw DebugAudioPlaybackException("Captured PCM is not frame-aligned.", error)
            }

        withContext(ioDispatcher) {
            val audioTrack = createAudioTrack(pcm.size)
            try {
                writeStaticAudio(audioTrack, pcm)
                awaitPlaybackCompletion(audioTrack, frameCount, pcm.size)
            } finally {
                audioTrack.setPlaybackPositionUpdateListener(null)
                stopAudioTrack(audioTrack)
                audioTrack.release()
                Log.d(TAG, "Debug AudioTrack released")
            }
        }
    }

    /**
     * Creates a static AudioTrack large enough for the full bounded debug recording.
     *
     * @param pcmSizeBytes complete PCM byte count to load.
     * @return an AudioTrack whose state has been validated as usable for a static write.
     * @throws DebugAudioPlaybackException when the format is unsupported or construction fails.
     *
     * This synchronous platform setup runs on [ioDispatcher], does not suspend, and has no
     * independent cancellation point. Its caller owns and releases the returned track.
     */
    private fun createAudioTrack(pcmSizeBytes: Int): AudioTrack {
        val deviceMinimumBytes =
            AudioTrack.getMinBufferSize(
                AudioConfig.SAMPLE_RATE_HZ,
                AudioConfig.OUTPUT_CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
            )
        if (deviceMinimumBytes <= 0) {
            throw DebugAudioPlaybackException(
                "This device does not support 16 kHz mono PCM playback " +
                    "(minimum buffer result $deviceMinimumBytes).",
            )
        }

        val audioTrack =
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioConfig.AUDIO_FORMAT)
                            .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
                            .setChannelMask(AudioConfig.OUTPUT_CHANNEL_CONFIG)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(deviceMinimumBytes, pcmSizeBytes))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (error: IllegalArgumentException) {
                throw DebugAudioPlaybackException("Android rejected the debug audio format.", error)
            } catch (error: UnsupportedOperationException) {
                throw DebugAudioPlaybackException("Audio playback is unsupported on this device.", error)
            }

        if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED) {
            audioTrack.release()
            throw DebugAudioPlaybackException("The debug audio output could not be initialized.")
        }
        return audioTrack
    }

    /**
     * Loads the complete PCM buffer into a static AudioTrack before playback begins.
     *
     * @param audioTrack initialized static track owned by [play].
     * @param pcm complete immutable PCM buffer.
     * @return This function has no return value.
     * @throws DebugAudioPlaybackException when Android rejects or only partially writes the buffer.
     *
     * The blocking write runs on [ioDispatcher]. It cannot be cooperatively cancelled while inside
     * the platform call, but the bounded ten-second buffer limits that interval and the enclosing
     * coroutine observes cancellation immediately afterward.
     */
    private fun writeStaticAudio(audioTrack: AudioTrack, pcm: ByteArray) {
        val bytesWritten =
            audioTrack.write(
                pcm,
                0,
                pcm.size,
                AudioTrack.WRITE_BLOCKING,
            )
        if (bytesWritten != pcm.size) {
            throw DebugAudioPlaybackException(
                "Android wrote $bytesWritten of ${pcm.size} debug PCM bytes.",
            )
        }
    }

    /**
     * Suspends until AudioTrack reports that playback reached the final PCM frame.
     *
     * @param audioTrack loaded static track owned by [play].
     * @param frameCount final notification marker position.
     * @param pcmSizeBytes PCM size used to derive a bounded completion timeout.
     * @return after the final marker callback.
     * @throws DebugAudioPlaybackException when playback cannot start or the marker times out.
     *
     * The wait is cancellable and callback-driven. The callback stays inside `speech/`; cancellation
     * stops AudioTrack, and the outer [play] `finally` removes the listener and releases it.
     */
    private suspend fun awaitPlaybackCompletion(
        audioTrack: AudioTrack,
        frameCount: Int,
        pcmSizeBytes: Int,
    ) {
        val timeoutMs = AudioConfig.playbackDurationMs(pcmSizeBytes) + PLAYBACK_TIMEOUT_MARGIN_MS
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val listener =
                        object : AudioTrack.OnPlaybackPositionUpdateListener {
                            /**
                             * Completes the suspended playback call at the configured final frame.
                             *
                             * @param track AudioTrack that reached its notification marker.
                             * @return This callback has no return value.
                             *
                             * Android invokes this on the main looper selected below. It performs
                             * no blocking work; a cancelled continuation ignores a late callback.
                             */
                            override fun onMarkerReached(track: AudioTrack) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            /**
                             * Ignores periodic updates because this player registers only a marker.
                             *
                             * @param track AudioTrack associated with the unused callback.
                             * @return This callback has no return value.
                             *
                             * Android may invoke it on the main looper, but no period is configured,
                             * so it performs no work and has no failure/cancellation behavior.
                             */
                            override fun onPeriodicNotification(track: AudioTrack) = Unit
                        }

                    audioTrack.setPlaybackPositionUpdateListener(
                        listener,
                        Handler(Looper.getMainLooper()),
                    )
                    val markerResult = audioTrack.setNotificationMarkerPosition(frameCount)
                    if (markerResult != AudioTrack.SUCCESS) {
                        continuation.resumeWith(
                            Result.failure(
                                DebugAudioPlaybackException(
                                    "Android rejected the debug playback completion marker.",
                                ),
                            ),
                        )
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { stopAudioTrack(audioTrack) }
                    try {
                        audioTrack.play()
                    } catch (error: IllegalStateException) {
                        continuation.resumeWith(
                            Result.failure(
                                DebugAudioPlaybackException(
                                    "The debug audio output could not start.",
                                    error,
                                ),
                            ),
                        )
                    }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw DebugAudioPlaybackException("Debug audio playback timed out.", error)
        }
    }

    /**
     * Best-effort stops a playing or paused track during completion, failure, or cancellation.
     *
     * @param audioTrack track owned by the current [play] call.
     * @return This function has no return value.
     *
     * The function may run on [ioDispatcher] or from a cancellation callback. It does not suspend;
     * stop failures are logged so resource release in the caller remains guaranteed.
     */
    private fun stopAudioTrack(audioTrack: AudioTrack) {
        if (
            audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING ||
                audioTrack.playState == AudioTrack.PLAYSTATE_PAUSED
        ) {
            try {
                audioTrack.stop()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "AudioTrack.stop failed during cleanup", error)
            }
        }
    }

    private companion object {
        const val TAG = "DebugAudioPlayer"
        const val PLAYBACK_TIMEOUT_MARGIN_MS = 2_000L
    }
}
