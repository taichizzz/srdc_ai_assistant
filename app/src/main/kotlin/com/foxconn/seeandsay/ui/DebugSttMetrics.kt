package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.speech.SttResult

/**
 * Supplies monotonic nanoseconds for DEBUG STT latency measurement.
 *
 * Implementations must never use wall-clock time because clock corrections would corrupt latency.
 * Calls are synchronous, safe on the caller's dispatcher, perform no I/O, and have no coroutine or
 * cancellation behavior. A platform clock failure propagates to the debug harness as an unexpected
 * run failure without affecting production STT.
 */
fun interface MonotonicClock {

    /**
     * Returns an arbitrary-origin monotonic timestamp.
     *
     * @return elapsed nanoseconds suitable only for differences with another value from this clock.
     *
     * The function is synchronous and non-blocking. Implementations own no coroutine resource and
     * must remain monotonic across the lifetime of one metrics tracker.
     */
    fun nowNanos(): Long

    /** Holds the production monotonic clock without introducing an Android dependency into tests. */
    companion object {
        /** JVM/Android monotonic clock backed by [System.nanoTime]. */
        val SYSTEM: MonotonicClock = MonotonicClock(System::nanoTime)
    }
}

/**
 * Classifies one completed DEBUG comparison run for display and CSV export.
 *
 * @property csvValue stable lowercase value intended for spreadsheets/log parsing.
 *
 * Values contain no provider detail or credential. They perform no work, fail in no way, are safe
 * across coroutine contexts, and have no cancellation behavior.
 */
enum class DebugSttOutcome(val csvValue: String) {
    /** A run is currently collecting audio/results and has not emitted its CSV record. */
    Running("running"),

    /** At least one final transcript arrived and the stream completed normally. */
    Success("success"),

    /** Neither approved credential mode or required V2 project/location was configured. */
    NotConfigured("not_configured"),

    /** Google rejected or could not obtain the selected credential. */
    Unauthenticated("auth"),

    /** Google denied API, project, recognizer, model, or regional access. */
    PermissionDenied("permission"),

    /** Google rejected the request because quota/rate limits were exhausted. */
    QuotaExceeded("quota"),

    /** Network or cloud service was unavailable. */
    Unavailable("network"),

    /** Connection, RPC deadline, or final-response wait exceeded its bound. */
    Timeout("timeout"),

    /** A stream completed normally without a final transcript. */
    NoFinalResult("no_final"),

    /** The tester/lifecycle replaced an active run; cancellation is not a user error. */
    Cancelled("cancelled"),

    /** An unexpected provider/audio/client failure occurred without secret detail. */
    Unknown("unknown"),
}

/**
 * Holds the auto-measurable output from one DEBUG engine run.
 *
 * @property engine selected API/model engine.
 * @property firstTokenLatencyMs first audio collection to first non-blank interim, or `null`.
 * @property finalSentenceLatencyMs Stop/end-of-audio to latest final, or `null`.
 * @property totalLatencyMs stream start to latest final, or `null`.
 * @property outcome current/completed run outcome.
 * @property transcript accumulated final text, falling back to latest interim when no final exists.
 *
 * The immutable value contains no credential, raw audio, or provider SDK type. It performs no I/O,
 * is safe in StateFlow across dispatchers, and owns no coroutine/cancellation resource.
 */
data class DebugSttMetrics(
    val engine: DebugSttEngine,
    val firstTokenLatencyMs: Long? = null,
    val finalSentenceLatencyMs: Long? = null,
    val totalLatencyMs: Long? = null,
    val outcome: DebugSttOutcome = DebugSttOutcome.Running,
    val transcript: String = "",
) {

    /**
     * Formats this run as one CSV-friendly logcat payload in the required column order.
     *
     * @return `engine,model,firstTokenMs,finalMs,totalMs,outcome,transcript` with RFC-style escaping.
     *
     * The pure function performs no logging or I/O and is safe on any dispatcher. Missing latency is
     * an empty cell; transcript quotes/newlines are escaped so exactly one log line is produced.
     */
    fun toCsvLine(): String =
        listOf(
            engine.apiVersion,
            engine.model,
            firstTokenLatencyMs?.toString().orEmpty(),
            finalSentenceLatencyMs?.toString().orEmpty(),
            totalLatencyMs?.toString().orEmpty(),
            outcome.csvValue,
            transcript,
        ).joinToString(",", transform = ::escapeCsvField)

    /**
     * Escapes a single field when commas, quotes, carriage returns, or newlines are present.
     *
     * @param value raw non-secret metrics field.
     * @return unchanged safe field or a quoted field with doubled quotes and flattened line breaks.
     *
     * This pure helper performs no I/O, is safe on any dispatcher, and has no cancellation behavior.
     * Normal allocation failure is its only expected local failure.
     */
    private fun escapeCsvField(value: String): String {
        val flattened = value.replace('\r', ' ').replace('\n', ' ')
        return if (flattened.any { it == ',' || it == '"' }) {
            "\"${flattened.replace("\"", "\"\"")}\""
        } else {
            flattened
        }
    }
}

/**
 * Computes one DEBUG STT run's latency metrics without changing [SttResult].
 *
 * @param engine selected comparison engine/model.
 * @param clock monotonic source, injectable with coroutine virtual time in tests.
 *
 * The tracker is confined to one ViewModel session coroutine/main-thread event stream. All methods
 * are synchronous, perform no I/O or logging, and own no coroutine resource. Calling events before
 * [onStreamStarted] leaves unavailable latency fields `null`; clock regressions clamp duration to
 * zero rather than producing invalid negative evaluation data.
 */
internal class DebugSttMetricsTracker(
    private val engine: DebugSttEngine,
    private val clock: MonotonicClock,
) {
    private var streamStartedNanos: Long? = null
    private var firstAudioNanos: Long? = null
    private var firstInterimNanos: Long? = null
    private var stopNanos: Long? = null
    private var latestFinalNanos: Long? = null
    // A final emitted before Stop is valid for total latency, but cannot define Stop-to-final.
    private var finalAfterStopNanos: Long? = null
    private var latestInterim: String = ""
    private var finalTranscript: String = ""

    /**
     * Records the start boundary before the selected SttClient Flow is collected.
     *
     * @return This function has no return value.
     *
     * The synchronous call records only the first invocation, performs no I/O, and has no
     * cancellation behavior. Clock failure propagates to the DEBUG run's existing failure path.
     */
    fun onStreamStarted() {
        if (streamStartedNanos == null) streamStartedNanos = clock.nowNanos()
    }

    /**
     * Records when the selected client first pulls a non-empty audio chunk from the harness Flow.
     *
     * @return This function has no return value.
     *
     * The first call wins so buffering/repeated chunks cannot move the latency origin. It is
     * synchronous, non-blocking, and owns no cancellation resource.
     */
    fun onAudioChunkSent() {
        if (firstAudioNanos == null) firstAudioNanos = clock.nowNanos()
    }

    /**
     * Records the tester's Stop/end-of-audio boundary for final-sentence latency.
     *
     * @return This function has no return value.
     *
     * The first call wins. The synchronous method performs no I/O or coroutine work; if a final
     * already arrived, snapshot latency clamps to zero because text was available before Stop.
     */
    fun onStopRequested() {
        if (stopNanos == null) stopNanos = clock.nowNanos()
    }

    /**
     * Records one non-blank provider-neutral recognition result and its timing boundary.
     *
     * @param result interim or final value from the selected SttClient.
     * @return This function has no return value.
     *
     * The synchronous method ignores blank text. The first non-final value defines first-token
     * latency; finals append once in event order, clear interim, and update final/total timing. It
     * performs no I/O and has no independent cancellation behavior.
     */
    fun onResult(result: SttResult) {
        val text = result.transcript.trim()
        if (text.isEmpty()) return
        val now = clock.nowNanos()
        if (result.isFinal) {
            finalTranscript = appendTranscript(finalTranscript, text)
            latestInterim = ""
            latestFinalNanos = now
            if (stopNanos != null) finalAfterStopNanos = now
        } else {
            latestInterim = text
            if (firstInterimNanos == null) firstInterimNanos = now
        }
    }

    /**
     * Produces the latest immutable panel/CSV snapshot for a requested outcome.
     *
     * @param outcome running, success, cancellation, or recoverable failure classification.
     * @return metrics derived from recorded monotonic boundaries.
     *
     * The pure calculation performs no I/O or suspension and owns no cancellation resource.
     * Missing prerequisite events produce `null` latency instead of fabricated zero values.
     */
    fun snapshot(outcome: DebugSttOutcome = DebugSttOutcome.Running): DebugSttMetrics {
        val firstTokenLatency = durationMillis(firstAudioNanos, firstInterimNanos)
        val finalLatency = durationMillis(stopNanos, finalAfterStopNanos)
        val totalLatency = durationMillis(streamStartedNanos, latestFinalNanos)
        return DebugSttMetrics(
            engine = engine,
            firstTokenLatencyMs = firstTokenLatency,
            finalSentenceLatencyMs = finalLatency,
            totalLatencyMs = totalLatency,
            outcome = outcome,
            transcript = finalTranscript.ifBlank { latestInterim },
        )
    }

    /**
     * Reports whether at least one final result has been observed in this run.
     *
     * @return `true` after a non-blank final transcript was appended.
     *
     * The getter is synchronous, performs no work beyond immutable state inspection, fails in no
     * project-specific way, and has no coroutine/cancellation behavior.
     */
    fun hasFinalResult(): Boolean = finalTranscript.isNotBlank()

    /**
     * Computes a non-negative elapsed millisecond value when both boundaries exist.
     *
     * @param start earlier monotonic nanoseconds, or `null` when the event did not occur.
     * @param end later monotonic nanoseconds, or `null` when the event did not occur.
     * @return truncated non-negative milliseconds, or `null` without both boundaries.
     *
     * This pure helper performs no I/O/suspension and has no cancellation behavior. Regressing fake
     * clocks clamp to zero to keep evaluation output valid.
     */
    private fun durationMillis(start: Long?, end: Long?): Long? {
        if (start == null || end == null) return null
        return (end - start).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
    }

    /**
     * Appends one committed result using the same readable newline boundary as the smoke panel.
     *
     * @param existing prior committed text.
     * @param segment new non-blank final segment.
     * @return [segment] alone or both values separated by one newline.
     *
     * This pure helper performs no I/O, failure-prone provider work, or cancellation behavior.
     */
    private fun appendTranscript(existing: String, segment: String): String =
        if (existing.isBlank()) segment else "$existing\n$segment"

    private companion object {
        /** Nanoseconds in one millisecond, used for monotonic duration conversion. */
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
