package com.foxconn.seeandsay.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Classifies recoverable failures from Android's on-device text-to-speech boundary.
 *
 * Values contain no Android callback or engine object, perform no I/O, and have no coroutine or
 * cancellation behavior. They let speech-layer tests distinguish setup from playback failures
 * without leaking platform callbacks into UI state.
 */
enum class DeviceTtsFailureReason {
    /** The Android TTS engine failed to initialize. */
    InitializationFailed,

    /** Android reports that Taiwan-Mandarin voice data must be installed. */
    LanguageDataMissing,

    /** The selected Android TTS engine does not support Taiwan Mandarin. */
    LanguageNotSupported,

    /** Initialization did not report success or failure before its bounded timeout. */
    InitializationTimedOut,

    /** The caller supplied blank text. */
    EmptyText,

    /** Android rejected the speak request or reported an utterance error. */
    PlaybackFailed,

    /** The client was disposed before the request began or completed. */
    ClientClosed,
}

/**
 * Represents a fixed, non-secret, recoverable failure from [DeviceTtsClient].
 *
 * @property reason stable failure category suitable for deterministic tests and future UI mapping.
 * @param message fixed user-safe description that contains no engine/provider internals.
 * @param cause optional local exception retained for diagnostics but never logged by this client.
 *
 * Construction performs no I/O or coroutine work. The exception propagates through [TtsClient]
 * and is converted by the ViewModel into recoverable Error state.
 */
class DeviceTtsException(
    val reason: DeviceTtsFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Result of initializing an internal on-device engine with Taiwan-Mandarin configuration.
 *
 * Values are pure, perform no I/O, and own no callback/coroutine resource. The Android adapter
 * converts platform status integers into this speech-internal vocabulary.
 */
internal enum class DeviceTtsInitializationResult {
    /** Engine initialized and accepted Taiwan Mandarin. */
    Ready,

    /** Engine construction or asynchronous initialization failed. */
    EngineFailed,

    /** Taiwan-Mandarin voice data is missing from the device. */
    LanguageDataMissing,

    /** Taiwan Mandarin is unsupported by the installed engine. */
    LanguageNotSupported,
}

/**
 * Receives speech-internal utterance lifecycle events from an on-device engine adapter.
 *
 * Implementations must be thread-safe because Android may invoke progress callbacks on binder or
 * engine threads. Callbacks perform no suspension and must return promptly; they never leave the
 * `speech/` package.
 */
internal interface DeviceTtsEngineListener {

    /**
     * Reports that Android began an accepted utterance.
     *
     * @param utteranceId identifier supplied by DeviceTtsClient.
     * @return This callback has no return value.
     *
     * It may run on an Android engine thread, must not block, and has no cancellation ownership.
     */
    fun onStart(utteranceId: String)

    /**
     * Reports successful completion of an utterance's audible playback.
     *
     * @param utteranceId identifier supplied by DeviceTtsClient.
     * @return This callback has no return value.
     *
     * It may run on an Android engine thread, must not block, and completes the matching suspended
     * caller only when it still owns that identifier.
     */
    fun onDone(utteranceId: String)

    /**
     * Reports an engine or playback error for an accepted utterance.
     *
     * @param utteranceId identifier supplied by DeviceTtsClient.
     * @return This callback has no return value.
     *
     * It may run on an Android engine thread, must not block, and resumes the matching caller with
     * a recoverable failure unless cancellation already removed it.
     */
    fun onError(utteranceId: String)
}

/**
 * Pure test seam around Android's callback-oriented TextToSpeech engine.
 *
 * The interface remains internal to `speech/`; no callback crosses into UI or TtsClient. Production
 * uses AndroidDeviceTtsEngine while JVM tests inject a deterministic fake. Implementations must make
 * stop and shutdown idempotent and safe when initialization or playback is incomplete.
 */
internal interface DeviceTtsEngine {

    /**
     * Starts asynchronous engine initialization and Taiwan-Mandarin configuration.
     *
     * @param listener utterance callbacks retained only inside the speech package.
     * @param onResult callback invoked exactly once with a provider-neutral setup result.
     * @return This function has no return value.
     *
     * Production invocation occurs on Android's main thread during ViewModel composition. The
     * callback may arrive later on an engine thread. Initialization failure must be reported rather
     * than thrown asynchronously; this method owns no coroutine cancellation.
     */
    fun initialize(
        listener: DeviceTtsEngineListener,
        onResult: (DeviceTtsInitializationResult) -> Unit,
    )

    /**
     * Requests one flush-and-speak utterance from an initialized engine.
     *
     * @param text non-blank Taiwan-Mandarin text.
     * @param utteranceId unique request identifier.
     * @return `true` when Android accepted the request and will later issue a callback.
     *
     * The function is synchronous and non-suspending. Engine rejection returns false; unexpected
     * local platform exceptions may propagate to DeviceTtsClient for fixed failure mapping.
     */
    fun speak(
        text: String,
        utteranceId: String,
    ): Boolean

    /**
     * Stops current engine playback and discards queued speech.
     *
     * @return This function has no return value.
     *
     * It is idempotent, non-suspending, and may be called by a coroutine cancellation handler.
     * Platform failures must be contained by the implementation so cancellation cannot crash.
     */
    fun stop()

    /**
     * Permanently releases the underlying engine and callbacks.
     *
     * @return This function has no return value.
     *
     * It is idempotent and non-suspending. After shutdown, speak requests must be rejected; platform
     * cleanup failures must be contained so ViewModel disposal cannot crash.
     */
    fun shutdown()
}

/**
 * Speaks text with Android's installed on-device TextToSpeech engine in Taiwan Mandarin.
 *
 * @param engine speech-internal engine boundary; production constructs the Android adapter while
 * tests inject a pure fake.
 *
 * Construction starts asynchronous engine initialization but never speaks. [speak] serializes
 * requests, awaits successful zh-TW configuration with a timeout, and suspends through the matching
 * onDone callback. Cancellation removes the continuation and calls engine stop promptly. [close]
 * cancels callers and releases TextToSpeech. No Android callback escapes `speech/`.
 */
class DeviceTtsClient internal constructor(
    private val engine: DeviceTtsEngine,
) : TtsClient,
    AutoCloseable {

    /**
     * Creates the production adapter and owns one Android TextToSpeech engine.
     *
     * @param context Android context; its application context is retained to avoid Activity leaks.
     *
     * Construction must occur on Android's main thread and starts asynchronous initialization but
     * does not speak or block. Platform construction/setup failures are delivered later through
     * [speak] as recoverable [DeviceTtsException] values; [close] cancels and releases the engine.
     */
    constructor(context: Context) : this(AndroidDeviceTtsEngine(context.applicationContext))

    /** Deferred initialization outcome shared by all serialized speak calls. */
    private val initialization = CompletableDeferred<Unit>()

    /** Serializes engine ownership because Android QUEUE_FLUSH supports one active utterance here. */
    private val speakMutex = Mutex()

    /** Active utterance continuations keyed for thread-safe Android callback matching. */
    private val activeUtterances =
        ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    /** Monotonic in-process identifier source that avoids transcript text in callback identifiers. */
    private val utteranceCounter = AtomicLong(0L)

    /** Thread-safe disposal state checked before initialization and playback work. */
    private val isClosed = AtomicBoolean(false)

    /** Thread-safe listener that converts Android-engine callbacks into suspended-call completion. */
    private val engineListener =
        object : DeviceTtsEngineListener {
            /**
             * Accepts the start signal without completing the suspend-until-done contract.
             *
             * @param utteranceId active request identifier.
             * @return This callback has no return value.
             *
             * The callback may run on any engine thread, performs no blocking work, and deliberately
             * leaves cancellation/completion ownership with onDone/onError.
             */
            override fun onStart(utteranceId: String) = Unit

            /**
             * Completes the matching caller after Android reports playback finished.
             *
             * @param utteranceId active request identifier.
             * @return This callback has no return value.
             *
             * The callback is thread-safe/non-blocking. Missing identifiers indicate cancellation
             * or a stale callback and are ignored without failure.
             */
            override fun onDone(utteranceId: String) {
                val continuation = activeUtterances.remove(utteranceId) ?: return
                continuation.resume(Unit)
            }

            /**
             * Fails the matching caller with a fixed recoverable playback error.
             *
             * @param utteranceId active request identifier.
             * @return This callback has no return value.
             *
             * The callback is thread-safe/non-blocking. Cancellation removes the identifier first,
             * so a later Android error cannot become a user-visible cancellation failure.
             */
            override fun onError(utteranceId: String) {
                val continuation = activeUtterances.remove(utteranceId) ?: return
                continuation.resumeWithException(playbackFailure())
            }
        }

    init {
        try {
            engine.initialize(engineListener, ::completeInitialization)
        } catch (error: RuntimeException) {
            initialization.completeExceptionally(initializationFailure(error))
        }
    }

    /**
     * Speaks one non-blank text value and suspends until Android reports playback completion.
     *
     * @param text plain text to synthesize with the configured Taiwan-Mandarin engine.
     * @return only after the matching onDone callback confirms audible playback completed.
     * @throws DeviceTtsException for empty text, initialization/language failure or timeout, engine
     * rejection, playback error, or use after disposal.
     *
     * The suspend function may be called from any coroutine context and never blocks a thread.
     * Calls are serialized. Cancellation while waiting for the engine or playback propagates as
     * normal coroutine cancellation; active playback is stopped and stale callbacks are ignored.
     */
    override suspend fun speak(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            throw DeviceTtsException(DeviceTtsFailureReason.EmptyText, EMPTY_TEXT_MESSAGE)
        }

        speakMutex.withLock {
            ensureOpen()
            awaitInitialization()
            ensureOpen()
            suspendUntilPlaybackCompletes(normalizedText)
        }
    }

    /**
     * Stops active playback, cancels pending callers, and releases the Android TTS engine.
     *
     * @return This function has no return value.
     *
     * The call is thread-safe, idempotent, and non-suspending. Active continuations receive normal
     * cancellation rather than DeviceTtsException. Engine stop/shutdown contain platform failures;
     * subsequent speak calls fail recoverably as ClientClosed.
     */
    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        if (!initialization.isCompleted) {
            initialization.completeExceptionally(closedFailure())
        }
        activeUtterances.values.forEach { continuation -> continuation.cancel() }
        activeUtterances.clear()
        engine.stop()
        engine.shutdown()
    }

    /**
     * Converts the asynchronous engine setup result into the shared initialization Deferred.
     *
     * @param result ready, engine failure, missing data, or unsupported locale.
     * @return This callback has no return value.
     *
     * Android may invoke it from an engine thread. CompletableDeferred completion is thread-safe and
     * non-blocking; duplicate/stale completion is ignored. It owns no coroutine cancellation.
     */
    private fun completeInitialization(result: DeviceTtsInitializationResult) {
        when (result) {
            DeviceTtsInitializationResult.Ready -> initialization.complete(Unit)
            DeviceTtsInitializationResult.EngineFailed ->
                initialization.completeExceptionally(initializationFailure())
            DeviceTtsInitializationResult.LanguageDataMissing ->
                initialization.completeExceptionally(languageDataMissingFailure())
            DeviceTtsInitializationResult.LanguageNotSupported ->
                initialization.completeExceptionally(languageNotSupportedFailure())
        }
    }

    /**
     * Awaits engine readiness with a finite no-hang bound.
     *
     * @return after Android successfully initializes and accepts Taiwan Mandarin.
     * @throws DeviceTtsException when setup fails or exceeds the timeout.
     *
     * The function suspends without blocking on the caller's context. Caller cancellation propagates
     * normally; only this function's own timeout is converted into a recoverable TTS failure.
     */
    private suspend fun awaitInitialization() {
        try {
            withTimeout(INITIALIZATION_TIMEOUT_MS) { initialization.await() }
        } catch (_: TimeoutCancellationException) {
            throw DeviceTtsException(
                DeviceTtsFailureReason.InitializationTimedOut,
                INITIALIZATION_TIMEOUT_MESSAGE,
            )
        }
    }

    /**
     * Bridges one accepted engine utterance to cancellable coroutine completion.
     *
     * @param text normalized non-blank text.
     * @return after the matching listener onDone callback.
     * @throws DeviceTtsException when the engine rejects the request or reports playback failure.
     *
     * The function does not block. Cancellation atomically removes the continuation and calls stop;
     * request/callback races must win map ownership before resuming, so completion happens once.
     */
    private suspend fun suspendUntilPlaybackCompletes(text: String) {
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "device-tts-${utteranceCounter.incrementAndGet()}"
            activeUtterances[utteranceId] = continuation
            continuation.invokeOnCancellation {
                if (activeUtterances.remove(utteranceId, continuation)) engine.stop()
            }

            val accepted =
                try {
                    engine.speak(text, utteranceId)
                } catch (error: RuntimeException) {
                    failAcceptedContinuation(utteranceId, continuation, playbackFailure(error))
                    return@suspendCancellableCoroutine
                }
            if (!accepted) {
                failAcceptedContinuation(utteranceId, continuation, playbackFailure())
            }
        }
    }

    /**
     * Removes and fails a continuation only when it still owns the utterance identifier.
     *
     * @param utteranceId request identifier.
     * @param continuation suspended caller registered for that identifier.
     * @param failure fixed recoverable playback failure.
     * @return This function has no return value.
     *
     * It is thread-safe/non-blocking and may race cancellation or engine callbacks. A lost ownership
     * race is ignored because another path already completed or cancelled the caller.
     */
    private fun failAcceptedContinuation(
        utteranceId: String,
        continuation: CancellableContinuation<Unit>,
        failure: DeviceTtsException,
    ) {
        if (!activeUtterances.remove(utteranceId, continuation)) return
        continuation.resumeWithException(failure)
    }

    /**
     * Rejects work after disposal.
     *
     * @return This function has no return value.
     * @throws DeviceTtsException when [close] has run.
     *
     * The check is synchronous, thread-safe, and performs no I/O or coroutine work.
     */
    private fun ensureOpen() {
        if (isClosed.get()) throw closedFailure()
    }

    private companion object {
        /** Maximum wait for Android's asynchronous TextToSpeech initialization callback. */
        const val INITIALIZATION_TIMEOUT_MS = 10_000L

        /** Fixed recoverable message for blank direct-client input. */
        const val EMPTY_TEXT_MESSAGE = "Text-to-speech text must not be empty."

        /** Fixed recoverable message for engine initialization failure. */
        const val INITIALIZATION_FAILURE_MESSAGE =
            "On-device text-to-speech could not initialize. Please retry."

        /** Fixed recoverable message for missing Taiwan-Mandarin engine data. */
        const val LANGUAGE_DATA_MISSING_MESSAGE =
            "Taiwan-Mandarin speech data is missing on this device."

        /** Fixed recoverable message for an engine without Taiwan-Mandarin support. */
        const val LANGUAGE_NOT_SUPPORTED_MESSAGE =
            "Taiwan Mandarin is not supported by the installed speech engine."

        /** Fixed recoverable message when Android never finishes initialization. */
        const val INITIALIZATION_TIMEOUT_MESSAGE =
            "On-device text-to-speech initialization timed out. Please retry."

        /** Fixed recoverable message for request rejection or utterance error. */
        const val PLAYBACK_FAILURE_MESSAGE =
            "On-device text-to-speech playback failed. Please retry."

        /** Fixed recoverable message for calls after lifecycle disposal. */
        const val CLIENT_CLOSED_MESSAGE = "On-device text-to-speech is no longer available."

        /**
         * Creates a fixed initialization failure without leaking Android engine detail.
         *
         * @param cause optional local platform exception retained only as the throwable cause.
         * @return recoverable initialization exception with a fixed non-secret message.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot itself be cancelled.
         */
        fun initializationFailure(cause: Throwable? = null): DeviceTtsException =
            DeviceTtsException(
                DeviceTtsFailureReason.InitializationFailed,
                INITIALIZATION_FAILURE_MESSAGE,
                cause,
            )

        /**
         * Creates a fixed missing-Taiwan-Mandarin-data failure.
         *
         * @return recoverable missing-data exception with a fixed non-secret message.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot itself be cancelled.
         */
        fun languageDataMissingFailure(): DeviceTtsException =
            DeviceTtsException(
                DeviceTtsFailureReason.LanguageDataMissing,
                LANGUAGE_DATA_MISSING_MESSAGE,
            )

        /**
         * Creates a fixed unsupported-Taiwan-Mandarin failure.
         *
         * @return recoverable unsupported-language exception with a fixed non-secret message.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot itself be cancelled.
         */
        fun languageNotSupportedFailure(): DeviceTtsException =
            DeviceTtsException(
                DeviceTtsFailureReason.LanguageNotSupported,
                LANGUAGE_NOT_SUPPORTED_MESSAGE,
            )

        /**
         * Creates a fixed playback failure without leaking Android engine detail.
         *
         * @param cause optional local platform exception retained only as the throwable cause.
         * @return recoverable playback exception with a fixed non-secret message.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot itself be cancelled.
         */
        fun playbackFailure(cause: Throwable? = null): DeviceTtsException =
            DeviceTtsException(
                DeviceTtsFailureReason.PlaybackFailed,
                PLAYBACK_FAILURE_MESSAGE,
                cause,
            )

        /**
         * Creates a fixed use-after-disposal failure.
         *
         * @return recoverable client-closed exception with a fixed non-secret message.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot itself be cancelled.
         */
        fun closedFailure(): DeviceTtsException =
            DeviceTtsException(DeviceTtsFailureReason.ClientClosed, CLIENT_CLOSED_MESSAGE)
    }
}

/**
 * Adapts Android TextToSpeech callbacks to the speech-internal [DeviceTtsEngine] boundary.
 *
 * @param context application context used to create one engine without leaking an Activity.
 *
 * Construction performs no work until [initialize]. Initialization and normal app calls originate
 * on the main thread; Android may deliver progress callbacks on engine threads. Platform exceptions
 * during stop/shutdown are contained, and no callback or Android engine object escapes `speech/`.
 */
private class AndroidDeviceTtsEngine(
    private val context: Context,
) : DeviceTtsEngine {

    /** Android audio service used to obtain exclusive transient focus before audible speech. */
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Speech-oriented attributes shared by the TTS engine and its transient focus request. */
    private val speechAudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /**
     * Transient focus request ensuring assistant speech does not overlap another app audio owner.
     *
     * Android dispatches focus changes on its selected callback thread. Permanent or transient
     * loss stops the active utterance through [handleAudioFocusLoss]; ducking is left to Android.
     */
    private val audioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAudioAttributes)
            .setOnAudioFocusChangeListener(::handleAudioFocusChange)
            .build()

    /** Lock coordinating the rare case where onInit races constructor assignment. */
    private val initializationLock = Any()

    /** Underlying Android engine, assigned once and cleared permanently by shutdown. */
    @Volatile private var textToSpeech: TextToSpeech? = null

    /** Initialization status delivered before engine assignment, if Android calls synchronously. */
    private var earlyInitializationStatus: Int? = null

    /** Ensures setup result is delivered exactly once across constructor/callback races. */
    private val initializationCompleted = AtomicBoolean(false)

    /** Prevents duplicate initialization and work after shutdown. */
    private val isShutdown = AtomicBoolean(false)

    /** Currently focused utterance identifier used to resolve callback/focus-loss races once. */
    private val focusedUtteranceId = AtomicReference<String?>(null)

    /** Progress receiver retained only so focus loss can terminate the matching suspended call. */
    @Volatile private var engineListener: DeviceTtsEngineListener? = null

    /**
     * Creates and configures Android TextToSpeech for Locale.TAIWAN.
     *
     * @param listener speech-internal progress receiver.
     * @param onResult exactly-once initialization result callback.
     * @return This function has no return value.
     *
     * Called on Android's main thread and returns before asynchronous initialization finishes.
     * Construction failure reports EngineFailed synchronously. Callback races are lock-protected;
     * this adapter owns no coroutine and cannot itself be cancelled.
     */
    override fun initialize(
        listener: DeviceTtsEngineListener,
        onResult: (DeviceTtsInitializationResult) -> Unit,
    ) {
        if (isShutdown.get() || textToSpeech != null) {
            onResult(DeviceTtsInitializationResult.EngineFailed)
            return
        }

        try {
            val createdEngine =
                TextToSpeech(context) { status ->
                    val assignedEngine =
                        synchronized(initializationLock) {
                            textToSpeech ?: run {
                                earlyInitializationStatus = status
                                null
                            }
                        }
                    if (assignedEngine != null) {
                        configureInitializedEngine(assignedEngine, status, listener, onResult)
                    }
                }
            val earlyStatus =
                synchronized(initializationLock) {
                    textToSpeech = createdEngine
                    earlyInitializationStatus.also { earlyInitializationStatus = null }
                }
            if (earlyStatus != null) {
                configureInitializedEngine(createdEngine, earlyStatus, listener, onResult)
            }
        } catch (_: RuntimeException) {
            deliverInitializationResult(DeviceTtsInitializationResult.EngineFailed, onResult)
        }
    }

    /**
     * Sends one flush-and-speak request to the initialized Android engine.
     *
     * @param text non-blank Taiwan-Mandarin text.
     * @param utteranceId opaque callback identifier.
     * @return true only when Android returns TextToSpeech.SUCCESS.
     *
     * Called by DeviceTtsClient's serialized coroutine. It is synchronous/non-blocking; missing or
     * shut-down engines return false, while unexpected Android runtime failures propagate for fixed
     * mapping by the client.
     */
    override fun speak(
        text: String,
        utteranceId: String,
    ): Boolean {
        val engine = textToSpeech ?: return false
        if (audioManager.requestAudioFocus(audioFocusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        focusedUtteranceId.set(utteranceId)
        return try {
            val accepted =
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ==
                    TextToSpeech.SUCCESS
            if (!accepted) releaseAudioFocus(utteranceId)
            accepted
        } catch (error: RuntimeException) {
            releaseAudioFocus(utteranceId)
            throw error
        }
    }

    /**
     * Stops Android playback without allowing cleanup failure to escape cancellation.
     *
     * @return This function has no return value.
     *
     * The call is idempotent/non-suspending and may run from a coroutine cancellation handler.
     * Android runtime failures are contained because Stop must remain normal cancellation.
     */
    override fun stop() {
        val focusedId = focusedUtteranceId.getAndSet(null)
        try {
            textToSpeech?.stop()
        } catch (_: RuntimeException) {
            // Cancellation cleanup must not become a second user-visible failure.
        } finally {
            if (focusedId != null) abandonAudioFocus()
        }
    }

    /**
     * Stops and permanently releases the Android engine.
     *
     * @return This function has no return value.
     *
     * The call is idempotent and non-suspending. It clears the reference before shutdown so no new
     * request can race resource disposal; Android cleanup failures remain contained.
     */
    override fun shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return
        focusedUtteranceId.set(null)
        engineListener = null
        val engine = synchronized(initializationLock) { textToSpeech.also { textToSpeech = null } }
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (_: RuntimeException) {
            // Lifecycle disposal must not crash the Activity/ViewModel.
        } finally {
            abandonAudioFocus()
        }
    }

    /**
     * Installs the progress bridge and selects Taiwan Mandarin after Android initialization.
     *
     * @param engine successfully constructed Android engine.
     * @param status Android onInit status.
     * @param listener speech-internal progress receiver.
     * @param onResult exactly-once setup result callback.
     * @return This function has no return value.
     *
     * Android may invoke this on its initialization callback thread. It performs only synchronous
     * engine configuration. Runtime failure maps to EngineFailed; it owns no coroutine/cancellation.
     */
    private fun configureInitializedEngine(
        engine: TextToSpeech,
        status: Int,
        listener: DeviceTtsEngineListener,
        onResult: (DeviceTtsInitializationResult) -> Unit,
    ) {
        if (status != TextToSpeech.SUCCESS || isShutdown.get()) {
            deliverInitializationResult(DeviceTtsInitializationResult.EngineFailed, onResult)
            return
        }

        try {
            engineListener = listener
            engine.setAudioAttributes(speechAudioAttributes)
            engine.setOnUtteranceProgressListener(createProgressListener(listener))
            val languageResult = engine.setLanguage(Locale.TAIWAN)
            val result =
                when (languageResult) {
                    TextToSpeech.LANG_MISSING_DATA ->
                        DeviceTtsInitializationResult.LanguageDataMissing
                    TextToSpeech.LANG_NOT_SUPPORTED ->
                        DeviceTtsInitializationResult.LanguageNotSupported
                    else -> DeviceTtsInitializationResult.Ready
                }
            deliverInitializationResult(result, onResult)
        } catch (_: RuntimeException) {
            deliverInitializationResult(DeviceTtsInitializationResult.EngineFailed, onResult)
        }
    }

    /**
     * Creates the Android callback bridge retained entirely by this engine adapter.
     *
     * @param listener speech-internal receiver owned by DeviceTtsClient.
     * @return UtteranceProgressListener forwarding non-null identifiers only.
     *
     * Listener construction is synchronous and performs no I/O. Android callbacks may occur on
     * engine threads, perform no blocking work, and never escape the speech module.
     */
    private fun createProgressListener(
        listener: DeviceTtsEngineListener,
    ): UtteranceProgressListener =
        object : UtteranceProgressListener() {
            /**
             * Forwards Android's start signal when it contains an utterance identifier.
             *
             * @param utteranceId opaque identifier supplied with the speak request, or null.
             * @return This callback has no return value.
             *
             * Android invokes this on an engine thread. It is non-blocking, owns no coroutine,
             * and intentionally does not complete or cancel the suspended speak call.
             */
            override fun onStart(utteranceId: String?) {
                utteranceId?.let(listener::onStart)
            }

            /**
             * Forwards Android's playback-complete signal when an identifier is present.
             *
             * @param utteranceId opaque identifier supplied with the speak request, or null.
             * @return This callback has no return value.
             *
             * Android invokes this on an engine thread. It is non-blocking and lets the client
             * resume only the matching, still-active coroutine continuation.
             */
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    releaseAudioFocus(id)
                    listener.onDone(id)
                }
            }

            /**
             * Forwards Android's legacy playback-error signal when an identifier is present.
             *
             * @param utteranceId opaque identifier supplied with the speak request, or null.
             * @return This callback has no return value.
             *
             * Android invokes this on an engine thread. It is non-blocking and maps through the
             * speech-internal listener; cancelled/stale identifiers are harmlessly ignored.
             */
            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { id ->
                    releaseAudioFocus(id)
                    listener.onError(id)
                }
            }

            /**
             * Forwards a non-null modern Android error callback while discarding provider code.
             *
             * @param utteranceId opaque request identifier.
             * @param errorCode Android-specific detail deliberately kept out of TtsClient/UI.
             * @return This callback has no return value.
             *
             * Android invokes it on an engine thread. It is non-blocking and has no cancellation
             * ownership; the matching continuation receives one fixed recoverable failure.
             */
            override fun onError(
                utteranceId: String?,
                errorCode: Int,
            ) {
                utteranceId?.let { id ->
                    releaseAudioFocus(id)
                    listener.onError(id)
                }
            }
        }

    /**
     * Responds to Android audio-focus changes for the active assistant utterance.
     *
     * @param focusChange Android focus gain/loss constant.
     * @return This callback has no return value.
     *
     * Android may invoke this on its focus callback thread. Permanent and transient loss stop the
     * engine and fail the matching suspended request recoverably; ducking/gain require no action.
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        if (
            focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            handleAudioFocusLoss()
        }
    }

    /**
     * Stops and fails the one utterance that owned audio focus when focus is lost.
     *
     * @return This function has no return value.
     *
     * The callback is non-suspending and race-safe. It clears ownership before stopping because
     * Android may synchronously report an utterance error; only the client continuation map decides
     * which signal completes the caller, preventing duplicate user-visible failures.
     */
    private fun handleAudioFocusLoss() {
        val utteranceId = focusedUtteranceId.getAndSet(null) ?: return
        try {
            textToSpeech?.stop()
        } catch (_: RuntimeException) {
            // Focus loss must still terminate the suspended caller even if platform stop fails.
        } finally {
            abandonAudioFocus()
            engineListener?.onError(utteranceId)
        }
    }

    /**
     * Releases transient focus only when the supplied utterance still owns it.
     *
     * @param utteranceId opaque identifier from an Android completion/error callback.
     * @return This function has no return value.
     *
     * It is thread-safe and non-suspending. Stale callbacks lose the compare-and-set and cannot
     * abandon focus held by a newer request; Android cleanup failure is deliberately contained.
     */
    private fun releaseAudioFocus(utteranceId: String) {
        if (focusedUtteranceId.compareAndSet(utteranceId, null)) abandonAudioFocus()
    }

    /**
     * Best-effort release of the reusable Android audio-focus request.
     *
     * @return This function has no return value.
     *
     * The synchronous platform call may run on a callback or cancellation thread. Runtime failures
     * are contained so completion, cancellation, and lifecycle disposal cannot crash or hang.
     */
    private fun abandonAudioFocus() {
        try {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (_: RuntimeException) {
            // Focus cleanup is best-effort after speech has already ended or been cancelled.
        }
    }

    /**
     * Delivers setup result at most once across initialization races.
     *
     * @param result normalized initialization outcome.
     * @param onResult client callback.
     * @return This function has no return value.
     *
     * The helper is thread-safe/non-blocking, performs no I/O, and owns no coroutine cancellation.
     */
    private fun deliverInitializationResult(
        result: DeviceTtsInitializationResult,
        onResult: (DeviceTtsInitializationResult) -> Unit,
    ) {
        if (initializationCompleted.compareAndSet(false, true)) onResult(result)
    }
}
