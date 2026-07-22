package com.foxconn.seeandsay.bridge

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * DEBUG/JVM [UiBridge] fake that returns scripted screens and records action ordering.
 *
 * @param snapshots non-empty sequence returned by successive [readScreen] calls; after the final
 * snapshot, subsequent reads keep returning that final value.
 * @param actionResult result returned by click, set-text, and back calls.
 *
 * Calls mutate only in-memory test state and perform no Android, network, or filesystem I/O. This
 * fake is intended for single-coroutine tests and is not thread-safe. An empty script fails fast
 * with [IllegalArgumentException]; its suspending methods do not delay or intercept cancellation.
 */
class FakeUiBridge(
    snapshots: List<ScreenSnapshot> = verificationDemoScreens(),
    private val actionResult: Boolean = true,
) : UiBridge {

    /** Immutable defensive copy of the scripted snapshots. */
    private val scriptedSnapshots = snapshots.toList()

    /** Zero-based position of the next scripted snapshot. */
    private var nextSnapshotIndex = 0

    /** Ordered mutable call log retained privately to prevent untracked external mutation. */
    private val recordedCalls = mutableListOf<Call>()

    init {
        require(scriptedSnapshots.isNotEmpty()) { "FakeUiBridge requires at least one snapshot" }
    }

    /**
     * Returns a defensive view of all calls recorded so far.
     *
     * @return immutable ordered call values.
     *
     * This synchronous in-memory accessor performs no I/O. It is not safe to race with calls from
     * other threads and has no expected failure.
     */
    fun calls(): List<Call> = recordedCalls.toList()

    /**
     * Records a read and returns the next screen, retaining the final screen after exhaustion.
     *
     * @return the next scripted immutable [ScreenSnapshot].
     *
     * This method completes immediately without I/O or dispatcher switching. It is not thread-safe
     * and has no expected failure after constructor validation.
     */
    override suspend fun readScreen(): ScreenSnapshot {
        recordedCalls += Call.ReadScreen
        val snapshot = scriptedSnapshots[nextSnapshotIndex]
        if (nextSnapshotIndex < scriptedSnapshots.lastIndex) nextSnapshotIndex += 1
        return snapshot
    }

    /**
     * Records a click request and returns the configured result.
     *
     * @param elementIndex snapshot identifier supplied by the caller.
     * @return configured [actionResult].
     *
     * This method completes immediately without I/O or dispatcher switching and is not thread-safe.
     */
    override suspend fun click(elementIndex: Int): Boolean {
        recordedCalls += Call.Click(elementIndex)
        return actionResult
    }

    /**
     * Records a set-text request and returns the configured result.
     *
     * @param elementIndex snapshot identifier supplied by the caller.
     * @param text exact requested replacement text.
     * @return configured [actionResult].
     *
     * This method completes immediately without I/O or dispatcher switching and is not thread-safe.
     */
    override suspend fun setText(elementIndex: Int, text: String): Boolean {
        recordedCalls += Call.SetText(elementIndex, text)
        return actionResult
    }

    /**
     * Records backward navigation and returns the configured result.
     *
     * @return configured [actionResult].
     *
     * This method completes immediately without I/O or dispatcher switching and is not thread-safe.
     */
    override suspend fun back(): Boolean {
        recordedCalls += Call.Back
        return actionResult
    }

    /**
     * Immutable record of one fake bridge invocation.
     *
     * Values are pure test data with no I/O, threading, suspension, or failure behavior.
     */
    sealed class Call {

        /** Records a [readScreen] invocation. */
        object ReadScreen : Call()

        /** @property index element identifier supplied to [click]. */
        data class Click(val index: Int) : Call()

        /**
         * @property index element identifier supplied to [setText].
         * @property text exact replacement text supplied to [setText].
         */
        data class SetText(val index: Int, val text: String) : Call()

        /** Records a [back] invocation. */
        object Back : Call()
    }

    /** Pure test fixtures shared by fake instances and decision tests. */
    companion object {

        /**
         * Builds the default DEBUG verification sequence: home, settings before edit, settings
         * after edit, then home again.
         *
         * @return immutable scripted snapshots demonstrating Click, SetText, and Back verification.
         *
         * This pure factory performs no I/O, accessibility work, waiting, timer, or suspension and
         * has no expected failure.
         */
        fun verificationDemoScreens(): List<ScreenSnapshot> =
            listOf(
                realisticHomeScreen(),
                realisticSettingsScreen(),
                realisticSettingsScreen(enteredText = "Roxanne"),
                realisticHomeScreen(),
            )

        /**
         * Builds a realistic launcher snapshot containing 設定, 音樂, and 導航 actions.
         *
         * @return immutable scripted home screen with stable element identifiers.
         *
         * This pure factory performs no I/O, is safe on any dispatcher, and has no expected failure.
         */
        fun realisticHomeScreen(): ScreenSnapshot =
            ScreenSnapshot(
                screen = "home",
                capturedAt = 1_789_000_000_000,
                elements =
                    listOf(
                        ScreenElement(0, "設定", clickable = true, editable = false, listOf(0, 120, 240, 200)),
                        ScreenElement(1, "音樂", clickable = true, editable = false, listOf(0, 220, 240, 300)),
                        ScreenElement(2, "導航", clickable = true, editable = false, listOf(0, 320, 240, 400)),
                    ),
            )

        /**
         * Builds a settings snapshot with one editable profile-name field.
         *
         * @param enteredText current visible field value; blank represents the pre-edit state.
         * @return immutable scripted settings screen with stable element identifiers.
         *
         * This pure factory performs no I/O, accessibility work, waiting, timer, or suspension and
         * has no expected failure for ordinary text.
         */
        fun realisticSettingsScreen(enteredText: String = ""): ScreenSnapshot =
            ScreenSnapshot(
                screen = "settings",
                capturedAt = 1_789_000_000_100,
                elements =
                    listOf(
                        ScreenElement(0, "設定", clickable = false, editable = false, emptyList()),
                        ScreenElement(1, "顯示", clickable = true, editable = false, emptyList()),
                        ScreenElement(2, "音效", clickable = true, editable = false, emptyList()),
                        ScreenElement(3, enteredText, clickable = false, editable = true, emptyList()),
                    ),
            )
    }
}
