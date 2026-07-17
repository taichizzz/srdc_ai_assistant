package com.foxconn.seeandsay.pipeline

/**
 * Describes the deterministic local meaning extracted from one normalized transcript.
 *
 * Values are pure immutable data with no Android, network, coroutine, or cancellation behavior.
 * They never represent an executed UI action; [TargetRequest] only records what the user asked for.
 */
internal sealed interface LocalReplyIntent {

    /** Friendly greeting such as `你好`, `哈囉`, `嗨`, `hello`, or `hi`. */
    data object Greeting : LocalReplyIntent

    /** Request asking the assistant to identify itself. */
    data object Identity : LocalReplyIntent

    /** Request for examples of currently understood deterministic commands. */
    data object Help : LocalReplyIntent

    /** Expression of thanks that needs only a courteous local response. */
    data object Thanks : LocalReplyIntent

    /** Request to repeat the most recent remembered assistant response. */
    data object Repeat : LocalReplyIntent

    /** Request to clear the currently remembered target without cancelling unrelated coroutines. */
    data object Cancel : LocalReplyIntent

    /** Open/play command that omitted the target needed to understand the request. */
    data object MissingTarget : LocalReplyIntent

    /** Empty/noise-only transcript after normalization. */
    data object Empty : LocalReplyIntent

    /**
     * Locally parsed request for a target that future screen-operation milestones may resolve.
     *
     * @property action requested deterministic action category.
     * @property target non-blank normalized target text such as `設定` or `音樂`.
     */
    data class TargetRequest(
        val action: LocalTargetAction,
        val target: String,
    ) : LocalReplyIntent

    /** Unrecognized non-blank transcript that should receive a helpful fallback. */
    data object Unknown : LocalReplyIntent
}

/**
 * Identifies the small action vocabulary parsed before screen operation exists.
 *
 * Values are provider-neutral constants with no I/O, threading, coroutine, or failure behavior.
 */
internal enum class LocalTargetAction {
    /** User wants to open or enter a named screen/function. */
    Open,

    /** User wants to play named media such as music. */
    Play,
}

/**
 * Classifies normalized transcripts into the bounded local reply-intent vocabulary.
 *
 * The classifier is stateless and thread-safe. It performs synchronous in-memory comparison on
 * any dispatcher, never compares raw STT text, launches no coroutine, and cannot fail for ordinary
 * string input.
 */
internal object LocalReplyIntentClassifier {

    /**
     * Classifies one transcript after the shared normalization boundary.
     *
     * @param normalizedTranscript NFKC/punctuation/whitespace-normalized comparison key.
     * @return one deterministic local intent; unknown and empty inputs remain explicit values.
     *
     * This pure function performs no I/O or suspension and owns no cancellation resources.
     */
    fun classify(normalizedTranscript: String): LocalReplyIntent {
        if (normalizedTranscript.isEmpty()) return LocalReplyIntent.Empty
        return when (normalizedTranscript) {
            in GREETING_KEYS -> LocalReplyIntent.Greeting
            in IDENTITY_KEYS -> LocalReplyIntent.Identity
            in HELP_KEYS -> LocalReplyIntent.Help
            in THANKS_KEYS -> LocalReplyIntent.Thanks
            in REPEAT_KEYS -> LocalReplyIntent.Repeat
            in CANCEL_KEYS -> LocalReplyIntent.Cancel
            else -> classifyTargetRequest(normalizedTranscript)
        }
    }

    /**
     * Extracts an open/play action and target after removing polite request prefixes.
     *
     * @param normalizedTranscript non-blank normalized comparison key.
     * @return target request, missing-target clarification, or [LocalReplyIntent.Unknown].
     *
     * This pure synchronous helper performs bounded string operations only and has no failure or
     * cancellation behavior for ordinary input.
     */
    private fun classifyTargetRequest(normalizedTranscript: String): LocalReplyIntent {
        val command = stripPolitePrefix(normalizedTranscript)
        OPEN_PREFIXES.firstOrNull(command::startsWith)?.let { prefix ->
            return targetIntent(LocalTargetAction.Open, command.removePrefix(prefix))
        }
        PLAY_PREFIXES.firstOrNull(command::startsWith)?.let { prefix ->
            return targetIntent(LocalTargetAction.Play, command.removePrefix(prefix))
        }
        return LocalReplyIntent.Unknown
    }

    /**
     * Removes at most one leading courtesy marker and one helper phrase.
     *
     * @param transcript normalized transcript to simplify before action-prefix matching.
     * @return normalized command with supported polite prefixes removed.
     *
     * This pure helper is safe on any dispatcher and performs no I/O, suspension, or cancellation.
     */
    private fun stripPolitePrefix(transcript: String): String {
        val withoutPlease = transcript.removePrefix("請")
        val helperPrefix = HELPER_PREFIXES.firstOrNull(withoutPlease::startsWith)
        return helperPrefix?.let(withoutPlease::removePrefix) ?: withoutPlease
    }

    /**
     * Creates a target intent while preserving missing-target information for clarification.
     *
     * @param action parsed local action category.
     * @param target normalized text following the action prefix.
     * @return [LocalReplyIntent.MissingTarget] when blank, otherwise a target request.
     *
     * The pure allocation performs no I/O and has no threading or cancellation constraints.
     */
    private fun targetIntent(
        action: LocalTargetAction,
        target: String,
    ): LocalReplyIntent =
        if (target.isBlank()) {
            LocalReplyIntent.MissingTarget
        } else {
            LocalReplyIntent.TargetRequest(action, target)
        }

    /** Normalized greeting synonyms accepted without an LM. */
    private val GREETING_KEYS = setOf("你好", "哈囉", "嗨", "hello", "hi", "hey")

    /** Normalized identity-question synonyms accepted without an LM. */
    private val IDENTITY_KEYS =
        setOf("你是誰", "你叫什麼", "你叫什麼名字", "whoareyou", "whatareyou")

    /** Normalized requests for the deterministic command guide. */
    private val HELP_KEYS = setOf("幫助", "幫忙", "你可以做什麼", "你會什麼", "help")

    /** Normalized gratitude synonyms. */
    private val THANKS_KEYS = setOf("謝謝", "感謝", "多謝", "thankyou", "thanks")

    /** Normalized requests to replay the previous assistant response. */
    private val REPEAT_KEYS = setOf("再說一次", "再講一次", "重複一次", "重複", "repeat")

    /** Normalized conversational cancellation phrases. */
    private val CANCEL_KEYS = setOf("取消", "停止", "算了", "cancel")

    /** Helper phrases accepted between an optional `請` and the requested action. */
    private val HELPER_PREFIXES = listOf("幫我", "幫忙")

    /** Open/enter synonyms ordered from specific to short. */
    private val OPEN_PREFIXES = listOf("打開", "開啟", "進入")

    /** Play synonyms ordered so the short colloquial form cannot preempt `播放`. */
    private val PLAY_PREFIXES = listOf("播放", "放")
}
