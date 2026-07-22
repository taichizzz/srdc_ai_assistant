package com.foxconn.seeandsay.bridge

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of waiting for accessibility-driven screen activity to become quiet.
 *
 * Values are immutable, contain no Android object, and are safe across threads. A timeout is an
 * observation rather than an exception: callers must still take a fresh snapshot and verify it.
 */
sealed interface ScreenSettleResult {

    /** At least one relevant event arrived and no newer event followed during the quiet window. */
    data object ChangeObserved : ScreenSettleResult

    /** No debounced change was observed before the caller's bounded timeout elapsed. */
    data object TimedOut : ScreenSettleResult
}

/**
 * Provider-neutral coroutine seam for waiting until accessibility screen-change events settle.
 *
 * Implementations must be event-driven, bounded, and cancellation-safe. They perform no action and
 * capture no snapshot; pipeline code owns action ordering and read-back verification.
 */
interface ScreenSettler {

    /**
     * Waits for at least one screen event followed by a quiet debounce period.
     *
     * @param timeoutMillis positive upper bound for the complete wait, including debounce.
     * @return [ScreenSettleResult.ChangeObserved] after a settled event burst, otherwise
     * [ScreenSettleResult.TimedOut].
     *
     * Cancellation propagates immediately and releases the flow collector. Ordinary timeout is a
     * value, not a thrown failure. Implementations must not replace event observation with a sleep.
     */
    suspend fun awaitScreenSettled(timeoutMillis: Long): ScreenSettleResult
}

/**
 * Event-driven [ScreenSettler] backed by a hot stream of relevant accessibility event signals.
 *
 * @param events hot event stream; each value represents window content/state activity.
 * @param quietWindowMillis non-negative duration with no newer event required before returning.
 *
 * The implementation uses Flow debounce rather than a blind delay: every new event restarts the
 * quiet window. It performs no Android access or I/O. Concurrent callers each collect the hot
 * stream independently. Cancellation and timeout remove their collectors automatically.
 *
 * @throws IllegalArgumentException when [quietWindowMillis] is negative.
 */
class EventDrivenScreenSettler(
    private val events: Flow<Unit>,
    private val quietWindowMillis: Long = DEFAULT_QUIET_WINDOW_MILLIS,
) : ScreenSettler {

    init {
        require(quietWindowMillis >= 0L) { "quietWindowMillis must not be negative" }
    }

    /**
     * Collects one debounced event within [timeoutMillis].
     *
     * @param timeoutMillis positive complete wait bound; non-positive values time out immediately.
     * @return settled-change or timeout observation.
     *
     * This suspending function is safe on any dispatcher. It launches no unstructured coroutine,
     * propagates cancellation, and releases its collector on cancellation or timeout.
     */
    @OptIn(FlowPreview::class)
    override suspend fun awaitScreenSettled(timeoutMillis: Long): ScreenSettleResult {
        if (timeoutMillis <= 0L) return ScreenSettleResult.TimedOut
        return withTimeoutOrNull(timeoutMillis) {
            events.debounce(quietWindowMillis).first()
            ScreenSettleResult.ChangeObserved
        } ?: ScreenSettleResult.TimedOut
    }

    companion object {
        /** Debounce chosen to absorb the short burst commonly emitted by one accessibility action. */
        const val DEFAULT_QUIET_WINDOW_MILLIS = 300L
    }
}
