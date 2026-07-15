package com.foxconn.seeandsay.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Defines the provider-neutral microphone boundary consumed by the ViewModel and future STT code.
 *
 * Implementations expose cold Flows, perform device I/O away from the main thread, surface capture
 * failures through Flow completion with an exception, and release capture resources when collection
 * is cancelled. No Android callback crosses this boundary.
 */
fun interface AudioCaptureSource {

    /**
     * Creates a cold stream of independent PCM chunks.
     *
     * @return a cold Flow that starts capture on collection and releases it on completion/cancel.
     *
     * Collection may fail for permission, unsupported format, initialization, or device read
     * errors. Implementations choose their I/O dispatcher and must cooperate with collector
     * cancellation without leaking the microphone.
     */
    fun capture(): Flow<ByteArray>
}

/**
 * Reports a recoverable microphone setup or read failure to the UI collection boundary.
 *
 * @param message non-secret user-readable description of the failed audio operation.
 * @param cause optional platform exception that caused the failure.
 *
 * Construction performs no I/O and is safe on any dispatcher. Throwing it cancels the active Flow;
 * [MicRecorder] still releases its AudioRecord in `finally` before the failure reaches callers.
 */
class AudioCaptureException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Captures 16 kHz mono PCM16 microphone audio as a cold, cancellation-safe Flow.
 *
 * @param context application or activity context used only for the runtime permission check.
 * @param ioDispatcher dispatcher used for AudioRecord setup and blocking reads.
 *
 * Each collector owns one AudioRecord. Permission, unsupported-buffer, initialization, start, and
 * read failures terminate that collector with [AudioCaptureException]. Blocking reads run on
 * [ioDispatcher], and cancellation is observed between approximately 100 ms reads; `finally`
 * always attempts to stop an active recorder and always releases it.
 */
class MicRecorder(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioCaptureSource {
    private val applicationContext = context.applicationContext

    /**
     * Creates a cold Flow whose collection owns one initialized AudioRecord.
     *
     * @return independent copied chunks targeted at 100 ms (3,200 bytes) each.
     *
     * No device work occurs until collection. Setup/read failures are emitted as
     * [AudioCaptureException]. Collection and blocking reads are shifted to [ioDispatcher].
     * Cancelling the collector stops emission, then stops and releases AudioRecord in `finally`.
     */
    override fun capture(): Flow<ByteArray> =
        flow {
            ensurePermissionGranted()

            val deviceMinimumBytes =
                AudioRecord.getMinBufferSize(
                    AudioConfig.SAMPLE_RATE_HZ,
                    AudioConfig.CHANNEL_CONFIG,
                    AudioConfig.AUDIO_FORMAT,
                )
            if (deviceMinimumBytes <= 0) {
                throw AudioCaptureException(
                    "This device does not support 16 kHz mono PCM microphone capture " +
                        "(minimum buffer result $deviceMinimumBytes).",
                )
            }
            val recorderBufferBytes = AudioConfig.recorderBufferSizeBytes(deviceMinimumBytes)
            val audioRecord = createAudioRecord(recorderBufferBytes)
            var recordingStarted = false

            try {
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    throw AudioCaptureException("The microphone could not be initialized.")
                }

                try {
                    audioRecord.startRecording()
                } catch (error: IllegalStateException) {
                    throw AudioCaptureException("The microphone could not start recording.", error)
                } catch (error: SecurityException) {
                    throw AudioCaptureException("Microphone permission was rejected by Android.", error)
                }
                recordingStarted = audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                if (!recordingStarted) {
                    throw AudioCaptureException("The microphone did not enter the recording state.")
                }

                val reusableReadBuffer = ByteArray(AudioConfig.CHUNK_SIZE_BYTES)
                var chunkCount = 0L
                while (currentCoroutineContext().isActive) {
                    val bytesRead =
                        audioRecord.read(
                            reusableReadBuffer,
                            0,
                            reusableReadBuffer.size,
                            AudioRecord.READ_BLOCKING,
                        )
                    when {
                        bytesRead > 0 -> {
                            chunkCount += 1
                            Log.d(TAG, "Captured PCM chunk #$chunkCount: $bytesRead bytes")
                            if (bytesRead < reusableReadBuffer.size) {
                                Log.w(
                                    TAG,
                                    "Short PCM read: $bytesRead/${reusableReadBuffer.size} bytes",
                                )
                            }
                            // The next AudioRecord.read mutates its target, so every emitted chunk
                            // must own a copy before a slower downstream collector can suspend us.
                            emit(reusableReadBuffer.copyOf(bytesRead))
                        }

                        bytesRead == AudioRecord.ERROR_DEAD_OBJECT ->
                            throw AudioCaptureException(
                                "The microphone device became unavailable during capture.",
                            )

                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION ->
                            throw AudioCaptureException(
                                "Android rejected a microphone read in the current state.",
                            )

                        bytesRead == AudioRecord.ERROR_BAD_VALUE ->
                            throw AudioCaptureException(
                                "Android rejected the microphone read buffer.",
                            )

                        else ->
                            throw AudioCaptureException(
                                "The microphone returned no audio (read result $bytesRead).",
                            )
                    }
                }
            } finally {
                releaseAudioRecord(audioRecord, recordingStarted)
            }
        }.flowOn(ioDispatcher)

    /**
     * Verifies Android currently grants microphone access before AudioRecord construction.
     *
     * @return This function has no return value.
     * @throws AudioCaptureException when `RECORD_AUDIO` is absent or revoked.
     *
     * The package-manager lookup is synchronous and runs on [ioDispatcher] inside [capture]. It
     * performs no suspension and has no independent cancellation behavior.
     */
    private fun ensurePermissionGranted() {
        if (
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw AudioCaptureException("Microphone permission is not granted.")
        }
    }

    /**
     * Builds the AudioRecord using the shared STT format and device-safe internal buffer.
     *
     * @param recorderBufferBytes validated internal AudioRecord capacity.
     * @return a recorder whose [AudioRecord.state] must still be checked by the caller.
     * @throws AudioCaptureException when Android rejects construction or permission.
     *
     * Construction runs on [ioDispatcher] inside [capture], does not suspend, and owns no separate
     * cancellation point. The lint suppression is scoped here because [ensurePermissionGranted]
     * performs the runtime check immediately before this call.
     */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(recorderBufferBytes: Int): AudioRecord =
        try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(AudioConfig.AUDIO_FORMAT)
                        .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioConfig.CHANNEL_CONFIG)
                        .build(),
                )
                .setBufferSizeInBytes(recorderBufferBytes)
                .build()
        } catch (error: IllegalArgumentException) {
            throw AudioCaptureException("Android rejected the microphone audio format.", error)
        } catch (error: UnsupportedOperationException) {
            throw AudioCaptureException("Microphone capture is unsupported on this device.", error)
        } catch (error: SecurityException) {
            throw AudioCaptureException("Microphone permission was rejected by Android.", error)
        }

    /**
     * Stops an active recorder when possible and unconditionally releases its native resources.
     *
     * @param audioRecord recorder owned by the current Flow collector.
     * @param recordingStarted whether this collector observed a successful recording transition.
     * @return This function has no return value.
     *
     * The function runs in the Flow's [ioDispatcher] `finally` block on normal completion, failure,
     * or cancellation. Stop failures are logged because release must still run and the original
     * capture failure/cancellation must remain authoritative.
     */
    private fun releaseAudioRecord(audioRecord: AudioRecord, recordingStarted: Boolean) {
        if (recordingStarted) {
            try {
                audioRecord.stop()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop failed during cleanup", error)
            }
        }
        audioRecord.release()
        Log.d(TAG, "AudioRecord released")
    }

    private companion object {
        const val TAG = "MicRecorder"
    }
}
