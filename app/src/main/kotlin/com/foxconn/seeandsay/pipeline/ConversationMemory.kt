package com.foxconn.seeandsay.pipeline

/**
 * Captures the small, process-local context retained between deterministic reply turns.
 *
 * @property lastTranscript most recent user transcript, or `null` before the first turn.
 * @property lastReply most recent assistant reply, used by the repeat intent.
 * @property lastRequestedTarget most recent locally parsed target that has not been cancelled.
 * @property lastSuccessfulAction most recent action description recorded only after verification.
 *
 * Instances are immutable values and safe to read on any dispatcher. They perform no I/O, launch
 * no coroutine, own no cancellation resource, and contain no persisted or cloud-sourced data.
 */
data class ConversationSnapshot(
    val lastTranscript: String? = null,
    val lastReply: String? = null,
    val lastRequestedTarget: String? = null,
    val lastSuccessfulAction: String? = null,
)

/**
 * Retains bounded local conversation context for repeat, cancel, and future verified actions.
 *
 * Text is kept only in memory for this engine/process lifetime and is truncated to a fixed bound;
 * it is never persisted, logged, or sent to a service. All operations synchronize internally, so
 * callers may safely use the instance from any dispatcher. Operations are synchronous, perform no
 * I/O, create no coroutine, and have no cancellation behavior.
 */
class ConversationMemory {

    /** Monitor protecting the immutable snapshot replacement used by every memory operation. */
    private val lock = Any()

    /** Latest immutable context; access is always protected by [lock]. */
    private var currentSnapshot = ConversationSnapshot()

    /**
     * Returns a stable copy of the currently remembered context.
     *
     * @return immutable process-local context at the instant the lock is acquired.
     *
     * This synchronous method is safe on any dispatcher, performs no I/O, cannot fail for normal
     * state, and owns no coroutine or cancellation resource.
     */
    fun snapshot(): ConversationSnapshot = synchronized(lock) { currentSnapshot.copy() }

    /**
     * Records the completed user/assistant exchange used by later repeat requests.
     *
     * @param transcript user transcript; blank content is represented as `null` in memory.
     * @param reply non-blank assistant reply produced for the transcript.
     * @return this function has no return value.
     * @throws IllegalArgumentException when [reply] is blank.
     *
     * The synchronous update is lock-protected and safe on any dispatcher. It performs no I/O,
     * launches no coroutine, and cannot be cancelled midway through the atomic replacement.
     */
    internal fun rememberExchange(
        transcript: String,
        reply: String,
    ) {
        require(reply.isNotBlank()) { "A remembered assistant reply must not be blank." }
        synchronized(lock) {
            currentSnapshot =
                currentSnapshot.copy(
                    lastTranscript = transcript.toRememberedTextOrNull(),
                    lastReply = reply.toRememberedTextOrNull(),
                )
        }
    }

    /**
     * Records the target parsed from a local open/play request without claiming it was executed.
     *
     * @param target non-blank normalized target such as `設定` or `音樂`.
     * @return this function has no return value.
     * @throws IllegalArgumentException when [target] is blank.
     *
     * The synchronous update is lock-protected, performs no I/O, creates no coroutine, and has no
     * cancellation behavior. A verified action must be recorded separately after M2.3 read-back.
     */
    internal fun rememberRequestedTarget(target: String) {
        require(target.isNotBlank()) { "A remembered target must not be blank." }
        synchronized(lock) {
            currentSnapshot =
                currentSnapshot.copy(lastRequestedTarget = target.toRememberedTextOrNull())
        }
    }

    /**
     * Clears only the pending target remembered for conversational cancellation.
     *
     * @return whether a target existed before it was cleared.
     *
     * The synchronous update is atomic and safe on any dispatcher. It performs no UI action or
     * coroutine cancellation—the v1 cancel intent clears conversation context only—and cannot fail.
     */
    internal fun clearRequestedTarget(): Boolean =
        synchronized(lock) {
            val hadRequestedTarget = currentSnapshot.lastRequestedTarget != null
            currentSnapshot = currentSnapshot.copy(lastRequestedTarget = null)
            hadRequestedTarget
        }

    /**
     * Records a successfully completed action for future contextual replies.
     *
     * @param actionDescription non-blank human-readable description recorded only after the caller
     * has verified the UI state change.
     * @return this function has no return value.
     * @throws IllegalArgumentException when [actionDescription] is blank.
     *
     * This method performs a synchronous bounded in-memory update on any dispatcher. It does not
     * execute or verify an action itself, performs no I/O, and has no coroutine/cancellation work.
     */
    internal fun recordVerifiedAction(actionDescription: String) {
        require(actionDescription.isNotBlank()) { "A verified action description must not be blank." }
        synchronized(lock) {
            currentSnapshot =
                currentSnapshot.copy(
                    lastSuccessfulAction = actionDescription.toRememberedTextOrNull(),
                )
        }
    }

    /**
     * Converts arbitrary text to the bounded representation retained by this process.
     *
     * @return trimmed text capped at [MAX_REMEMBERED_CHARACTERS], or `null` when blank.
     *
     * The helper is pure CPU/string work, safe on any dispatcher, and has no failure or
     * cancellation behavior for ordinary strings.
     */
    private fun String.toRememberedTextOrNull(): String? =
        trim().takeIf(String::isNotEmpty)?.take(MAX_REMEMBERED_CHARACTERS)

    private companion object {
        /** Bound preventing a long transcript/reply from becoming unbounded retained app state. */
        const val MAX_REMEMBERED_CHARACTERS: Int = 500
    }
}
