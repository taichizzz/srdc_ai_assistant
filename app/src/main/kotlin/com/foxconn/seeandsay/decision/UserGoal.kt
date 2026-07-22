package com.foxconn.seeandsay.decision

/**
 * High-level user goal produced by semantic interpretation without selecting a screen element.
 *
 * Goals never contain element indices, coordinates, provider types, or executable UI functions.
 * Values are immutable pure Kotlin data, safe across threads and dispatchers, and perform no I/O,
 * coroutine, cancellation, network, Android, or failure-prone work.
 */
sealed interface UserGoal {

    /**
     * Optional model-provided semantic description used only for local element grounding.
     *
     * This value is never an index, coordinate, or executable function. Reading it is pure,
     * thread-safe, non-blocking, and has no I/O, coroutine, cancellation, or failure behavior.
     */
    val controlQuery: String?

    /**
     * Requests opening a named target while leaving element selection to a later local planner.
     *
     * @property target non-blank model-provided target text, trimmed but not compared raw.
     * @property controlQuery optional non-blank semantic control description preferred for grounding.
     */
    data class OpenTarget(
        val target: String,
        override val controlQuery: String? = null,
    ) : UserGoal

    /**
     * @property direction requested text-size adjustment direction.
     * @property controlQuery optional semantic label hint such as `文字大小`.
     */
    data class AdjustTextSize(
        val direction: Direction,
        override val controlQuery: String? = null,
    ) : UserGoal

    /**
     * @property direction requested volume adjustment direction.
     * @property controlQuery optional semantic label hint such as `音量調低`.
     */
    data class AdjustVolume(
        val direction: Direction,
        override val controlQuery: String? = null,
    ) : UserGoal

    /**
     * @property direction requested brightness adjustment direction.
     * @property controlQuery optional semantic label hint such as `螢幕亮度`.
     */
    data class AdjustBrightness(
        val direction: Direction,
        override val controlQuery: String? = null,
    ) : UserGoal

    /** Requests local backward navigation without selecting an element index. */
    object GoBack : UserGoal {
        override val controlQuery: String? = null
    }

    /** Requests stopping the later task flow without selecting or executing a UI action here. */
    object Stop : UserGoal {
        override val controlQuery: String? = null
    }

    /** Represents a valid schema response whose semantic intent remains unknown. */
    object Unknown : UserGoal {
        override val controlQuery: String? = null
    }
}

/**
 * Direction of a supported adjustment goal.
 *
 * Values are immutable pure constants with no I/O, threading, coroutine, cancellation, or failure
 * behavior.
 */
enum class Direction {
    /** Requests increasing the selected setting. */
    Increase,

    /** Requests decreasing the selected setting. */
    Decrease,
}
