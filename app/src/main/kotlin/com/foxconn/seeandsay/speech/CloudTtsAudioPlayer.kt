package com.foxconn.seeandsay.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Defines the speech-internal PCM playback seam used by CloudTtsClient and pure JVM fakes.
 *
 * Implementations suspend through the final played frame, stop promptly on cancellation, and keep
 * Android callbacks/resources inside `speech/`. The seam is internal and does not widen TtsClient.
 */
internal interface TtsPcmPlayer {

    /**
     * Plays one validated headerless PCM buffer to completion.
     *
     * @param audio frame-aligned PCM16 data and output format.
     * @return after the final frame has played.
     * @throws CloudTtsException for output/focus/setup/write/start/timeout failures.
     *
     * Implementations choose an appropriate dispatcher and must never block the caller's thread.
     * Cancellation must stop playback and release per-call resources before completion unwinds.
     */
    suspend fun play(audio: PcmPlaybackAudio)

    /**
     * Best-effort stops an active playback call during lifecycle disposal.
     *
     * @return This function has no return value.
     *
     * The call is synchronous, thread-safe, idempotent, and non-suspending. It stops the active track
     * and wakes its Deferred with a recoverable failure; the owning finally block releases the track
     * and audio focus. Cleanup failures remain contained.
     */
    fun stop()
}

/**
 * Plays cloud-synthesized PCM16 through Android AudioTrack with transient assistant audio focus.
 *
 * @param context application context used only to obtain Android's AudioManager.
 * @param ioDispatcher dispatcher used for AudioTrack setup, blocking static writes, and cleanup.
 *
 * Calls are serialized because one player owns one transient-focus session at a time. [play]
 * suspends on an AudioTrack marker rather than sleeping, maps focus/output failures recoverably,
 * and releases track/focus in `finally`. [stop] may be called from cancellation or lifecycle code.
 */
internal class AudioTrackTtsPlayer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TtsPcmPlayer {

    /** Android audio service used for transient focus ownership. */
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    /** Serializes AudioTrack and audio-focus ownership across concurrent speak calls. */
    private val playbackMutex = Mutex()

    /** Active track exposed only for prompt thread-safe Stop/lifecycle cancellation. */
    private val activeTrack = AtomicReference<AudioTrack?>(null)

    /** Active event-driven completion signal used to wake playback during explicit lifecycle Stop. */
    private val activeCompletion = AtomicReference<CompletableDeferred<Unit>?>(null)

    /**
     * Plays validated mono PCM16 and waits for the final frame marker.
     *
     * @param audio non-empty, frame-aligned headerless PCM returned by the WAV parser.
     * @return after AudioTrack reaches the final frame.
     * @throws CloudTtsException for unsupported format, audio-focus denial/loss, initialization,
     * write/start, or completion timeout.
     *
     * All platform setup/blocking work runs on [ioDispatcher] under [playbackMutex]. The marker/focus
     * callbacks complete a Deferred without blocking. Cancellation enters `finally`, stops/releases
     * the track, abandons focus, and propagates as normal coroutine cancellation.
     */
    override suspend fun play(audio: PcmPlaybackAudio) {
        validateAudio(audio)
        playbackMutex.withLock {
            withContext(ioDispatcher) {
                playOnIoDispatcher(audio)
            }
        }
    }

    /**
     * Stops the current AudioTrack without releasing ownership from another thread.
     *
     * @return This function has no return value.
     *
     * The function is thread-safe, idempotent, non-blocking, and may run from ViewModel disposal.
     * Platform state failures are contained; the owning [play] finally block performs release.
     */
    override fun stop() {
        activeTrack.get()?.let(::stopTrackSafely)
        activeCompletion.get()?.completeExceptionally(playbackFailure(PLAYBACK_STOPPED_MESSAGE))
    }

    /**
     * Owns one complete AudioTrack and transient-focus lifecycle on the I/O dispatcher.
     *
     * @param audio validated headerless PCM16 data.
     * @return after the final playback marker.
     * @throws CloudTtsException for focus or Android output failure.
     *
     * The function runs only on [ioDispatcher]. Deferred callbacks may arrive on Android threads;
     * cleanup always removes callbacks, stops/releases the track, and abandons transient focus.
     */
    private suspend fun playOnIoDispatcher(audio: PcmPlaybackAudio) {
        val completion = CompletableDeferred<Unit>()
        val focusRequest = createAudioFocusRequest(completion)
        val audioTrack = createAudioTrack(audio)
        activeTrack.set(audioTrack)
        activeCompletion.set(completion)
        var ownsAudioFocus = false
        try {
            writeStaticAudio(audioTrack, audio.pcmBytes)
            installCompletionMarker(audioTrack, audio, completion)
            ownsAudioFocus = requestAudioFocus(focusRequest)
            if (!ownsAudioFocus) throw playbackFailure(AUDIO_FOCUS_DENIED_MESSAGE)
            try {
                audioTrack.play()
            } catch (error: IllegalStateException) {
                throw playbackFailure(PLAYBACK_START_MESSAGE, error)
            }
            val timeoutMs = playbackTimeoutMs(audio)
            try {
                withTimeout(timeoutMs) { completion.await() }
            } catch (error: TimeoutCancellationException) {
                throw playbackFailure(PLAYBACK_TIMEOUT_MESSAGE, error)
            }
        } finally {
            audioTrack.setPlaybackPositionUpdateListener(null)
            stopTrackSafely(audioTrack)
            activeTrack.compareAndSet(audioTrack, null)
            activeCompletion.compareAndSet(completion, null)
            audioTrack.release()
            if (ownsAudioFocus) abandonAudioFocus(focusRequest)
        }
    }

    /**
     * Validates PCM representation before allocating Android output resources.
     *
     * @param audio parsed PCM data and format metadata.
     * @return This function has no return value.
     * @throws CloudTtsException for empty, non-mono, non-PCM16, invalid-rate, or unaligned data.
     *
     * This synchronous pure check runs on the caller's context, performs no I/O/suspension, and owns
     * no cancellation resource.
     */
    private fun validateAudio(audio: PcmPlaybackAudio) {
        val bytesPerFrame = audio.channelCount * (audio.bitsPerSample / BITS_PER_BYTE)
        if (
            audio.pcmBytes.isEmpty() ||
                audio.sampleRateHz <= 0 ||
                audio.channelCount != SUPPORTED_CHANNEL_COUNT ||
                audio.bitsPerSample != SUPPORTED_BITS_PER_SAMPLE ||
                bytesPerFrame <= 0 ||
                audio.pcmBytes.size % bytesPerFrame != 0
        ) {
            throw playbackFailure(UNSUPPORTED_PCM_MESSAGE)
        }
    }

    /**
     * Creates a static AudioTrack matching the parsed cloud PCM format.
     *
     * @param audio validated mono PCM16 data and sample rate.
     * @return initialized static AudioTrack sized for the complete utterance.
     * @throws CloudTtsException when Android rejects or cannot initialize the output.
     *
     * This synchronous platform setup runs on [ioDispatcher], performs no playback, and owns no
     * coroutine cancellation point. The caller releases every returned track in `finally`.
     */
    private fun createAudioTrack(audio: PcmPlaybackAudio): AudioTrack {
        val outputChannelMask = AudioFormat.CHANNEL_OUT_MONO
        val minimumBytes =
            AudioTrack.getMinBufferSize(
                audio.sampleRateHz,
                outputChannelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minimumBytes <= 0) throw playbackFailure(UNSUPPORTED_OUTPUT_MESSAGE)
        val track =
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(audio.sampleRateHz)
                            .setChannelMask(outputChannelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minimumBytes, audio.pcmBytes.size))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (error: IllegalArgumentException) {
                throw playbackFailure(UNSUPPORTED_OUTPUT_MESSAGE, error)
            } catch (error: UnsupportedOperationException) {
                throw playbackFailure(UNSUPPORTED_OUTPUT_MESSAGE, error)
            }
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            track.release()
            throw playbackFailure(OUTPUT_INITIALIZATION_MESSAGE)
        }
        return track
    }

    /**
     * Loads the entire headerless PCM payload into static AudioTrack storage.
     *
     * @param audioTrack initialized static track.
     * @param pcmBytes complete headerless PCM data.
     * @return This function has no return value.
     * @throws CloudTtsException when Android rejects or partially writes the payload.
     *
     * The bounded blocking write runs on [ioDispatcher]. Coroutine cancellation is observed directly
     * afterward; Phase 3's 5,000-byte text bound limits response and static-buffer size.
     */
    private fun writeStaticAudio(
        audioTrack: AudioTrack,
        pcmBytes: ByteArray,
    ) {
        val written =
            audioTrack.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
        if (written != pcmBytes.size) throw playbackFailure(PLAYBACK_WRITE_MESSAGE)
    }

    /**
     * Installs an event-driven final-frame marker on the Android main looper.
     *
     * @param audioTrack initialized and loaded track.
     * @param audio parsed PCM data used to calculate the final frame.
     * @param completion thread-safe signal completed by marker or focus loss.
     * @return This function has no return value.
     * @throws CloudTtsException when Android rejects the final marker position.
     *
     * Listener setup runs on [ioDispatcher]; callbacks run on the main looper, perform no blocking
     * work, and remain inside `speech/`. Cancellation is owned by the caller's Deferred wait.
     */
    private fun installCompletionMarker(
        audioTrack: AudioTrack,
        audio: PcmPlaybackAudio,
        completion: CompletableDeferred<Unit>,
    ) {
        val bytesPerFrame = audio.channelCount * (audio.bitsPerSample / BITS_PER_BYTE)
        val frameCount = audio.pcmBytes.size / bytesPerFrame
        val listener =
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                /**
                 * Completes playback when Android reaches the configured final frame.
                 *
                 * @param track AudioTrack reporting the marker.
                 * @return This callback has no return value.
                 *
                 * Android invokes this on the main looper. It performs no I/O/suspension; duplicate
                 * completion after focus loss or cancellation is harmless.
                 */
                override fun onMarkerReached(track: AudioTrack) {
                    completion.complete(Unit)
                }

                /**
                 * Ignores periodic notifications because this player registers only one marker.
                 *
                 * @param track AudioTrack associated with the unused callback.
                 * @return This callback has no return value.
                 *
                 * Android may invoke it on the main looper, but it performs no work and owns no
                 * failure or cancellation behavior.
                 */
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }
        audioTrack.setPlaybackPositionUpdateListener(listener, Handler(Looper.getMainLooper()))
        if (audioTrack.setNotificationMarkerPosition(frameCount) != AudioTrack.SUCCESS) {
            throw playbackFailure(PLAYBACK_MARKER_MESSAGE)
        }
    }

    /**
     * Builds a transient assistant-focus request whose loss terminates playback recoverably.
     *
     * @param completion active playback completion signal.
     * @return reusable-for-this-call AudioFocusRequest with speech/assistant attributes.
     *
     * Construction is synchronous on [ioDispatcher]. Android may invoke the listener on its audio
     * callback thread; completing Deferred is thread-safe/non-blocking and wakes cancellable wait.
     */
    private fun createAudioFocusRequest(completion: CompletableDeferred<Unit>): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    completion.completeExceptionally(playbackFailure(AUDIO_FOCUS_LOST_MESSAGE))
                }
            }.build()

    /**
     * Requests transient focus immediately before playback begins.
     *
     * @param request per-call transient assistant focus request.
     * @return true only when Android grants focus synchronously.
     *
     * The platform call is synchronous/non-suspending on [ioDispatcher]. Runtime failure maps to
     * false; no audio has begun, and no focus cleanup is required when focus was not granted.
     */
    private fun requestAudioFocus(request: AudioFocusRequest): Boolean =
        try {
            audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: RuntimeException) {
            false
        }

    /**
     * Abandons a previously granted transient focus request after playback termination.
     *
     * @param request focus request owned by the current call.
     * @return This function has no return value.
     *
     * The synchronous platform call runs in `finally` on [ioDispatcher]. Runtime cleanup failures
     * are contained so successful playback, fallback, or cancellation cannot be replaced by them.
     */
    private fun abandonAudioFocus(request: AudioFocusRequest) {
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (_: RuntimeException) {
            // Cleanup remains best-effort after track playback has already ended.
        }
    }

    /**
     * Best-effort stops a playing or paused AudioTrack.
     *
     * @param audioTrack track owned by the current call.
     * @return This function has no return value.
     *
     * This synchronous helper may run on [ioDispatcher] or a lifecycle thread. Android state errors
     * are contained so the owner can always proceed to release and focus cleanup.
     */
    private fun stopTrackSafely(audioTrack: AudioTrack) {
        try {
            if (
                audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING ||
                    audioTrack.playState == AudioTrack.PLAYSTATE_PAUSED
            ) {
                audioTrack.stop()
            }
        } catch (_: IllegalStateException) {
            // Release in the owning finally block remains mandatory even if stop is rejected.
        }
    }

    /**
     * Calculates a bounded marker timeout from PCM duration plus a fixed scheduling margin.
     *
     * @param audio validated PCM data.
     * @return positive timeout in milliseconds.
     *
     * This pure arithmetic helper performs no I/O/suspension and owns no cancellation resource.
     * Long arithmetic prevents overflow for provider-sized payloads.
     */
    private fun playbackTimeoutMs(audio: PcmPlaybackAudio): Long {
        val bytesPerFrame = audio.channelCount * (audio.bitsPerSample / BITS_PER_BYTE)
        val frameCount = audio.pcmBytes.size.toLong() / bytesPerFrame
        val durationMs = (frameCount * MILLIS_PER_SECOND) / audio.sampleRateHz
        return maxOf(MINIMUM_PLAYBACK_TIMEOUT_MS, durationMs + PLAYBACK_TIMEOUT_MARGIN_MS)
    }

    /**
     * Creates Android attributes used consistently by AudioTrack and audio-focus requests.
     *
     * @return assistant/speech audio attributes suitable for spoken feedback.
     *
     * This synchronous builder performs no I/O/suspension and has no cancellation behavior.
     */
    private fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /**
     * Creates a fixed recoverable playback exception containing no audio/provider detail.
     *
     * @param message fixed user-safe playback description.
     * @param cause optional local Android exception retained only as throwable cause.
     * @return typed PlaybackFailed exception used by CloudTtsClient fallback.
     *
     * This synchronous helper performs no I/O/coroutine work and cannot be cancelled.
     */
    private fun playbackFailure(
        message: String,
        cause: Throwable? = null,
    ): CloudTtsException =
        CloudTtsException(CloudTtsFailureReason.PlaybackFailed, message, cause)

    private companion object {
        /** PCM bit-to-byte conversion factor. */
        const val BITS_PER_BYTE = 8

        /** Cloud playback supports the selected Google mono output only. */
        const val SUPPORTED_CHANNEL_COUNT = 1

        /** Cloud playback supports signed PCM16 only. */
        const val SUPPORTED_BITS_PER_SAMPLE = 16

        /** Milliseconds in one second for duration math. */
        const val MILLIS_PER_SECOND = 1_000L

        /** Scheduling/output margin added to calculated audio duration. */
        const val PLAYBACK_TIMEOUT_MARGIN_MS = 3_000L

        /** Lower timeout bound for short command responses. */
        const val MINIMUM_PLAYBACK_TIMEOUT_MS = 5_000L

        /** Fixed message for invalid PCM supplied to the Android player. */
        const val UNSUPPORTED_PCM_MESSAGE = "Cloud speech audio format is unsupported."

        /** Fixed message when Android cannot support the selected output settings. */
        const val UNSUPPORTED_OUTPUT_MESSAGE = "This device cannot play cloud speech audio."

        /** Fixed message when AudioTrack construction returns an unusable object. */
        const val OUTPUT_INITIALIZATION_MESSAGE = "Cloud speech audio output could not initialize."

        /** Fixed message when Android cannot load all static PCM bytes. */
        const val PLAYBACK_WRITE_MESSAGE = "Cloud speech audio could not be loaded for playback."

        /** Fixed message when Android rejects the final-frame marker. */
        const val PLAYBACK_MARKER_MESSAGE = "Cloud speech playback completion could not be tracked."

        /** Fixed message when transient assistant focus is denied. */
        const val AUDIO_FOCUS_DENIED_MESSAGE = "Cloud speech could not obtain audio focus."

        /** Fixed message when another audio owner interrupts active speech. */
        const val AUDIO_FOCUS_LOST_MESSAGE = "Cloud speech lost audio focus during playback."

        /** Fixed message when AudioTrack rejects play(). */
        const val PLAYBACK_START_MESSAGE = "Cloud speech audio playback could not start."

        /** Fixed message when the event-driven final marker never arrives. */
        const val PLAYBACK_TIMEOUT_MESSAGE = "Cloud speech audio playback timed out."

        /** Fixed message used to wake a playback call during explicit lifecycle Stop. */
        const val PLAYBACK_STOPPED_MESSAGE = "Cloud speech audio playback was stopped."
    }
}
