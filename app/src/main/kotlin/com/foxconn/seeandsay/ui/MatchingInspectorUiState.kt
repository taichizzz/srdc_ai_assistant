package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.decision.TextMatchResult
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.VerificationResult

/**
 * Explicit action choice offered by the DEBUG verification inspector.
 *
 * The choice selects the first compatible element from the captured before-snapshot for Click or
 * SetText, or creates Back directly. Values are UI-only immutable data and perform no action, I/O,
 * threading, timer, coroutine, or failure-prone work.
 */
enum class VerificationDecisionKind {
    /** Preview verification for the first clickable before-snapshot element. */
    Click,

    /** Preview verification for the first editable before-snapshot element. */
    SetText,

    /** Preview verification for backward navigation. */
    Back,
}

/**
 * Immutable state rendered by the DEBUG matching inspector.
 *
 * @property snapshot latest provider-neutral screen read from the injected bridge, when available.
 * @property result latest tiered decision produced for a typed command, when submitted.
 * @property verificationBefore tester-selected pre-action snapshot.
 * @property verificationAfter tester-selected post-action snapshot.
 * @property verificationDecision synthetic inspection-only decision selected by the tester.
 * @property verificationResult pure outcome from comparing selected snapshots and expectation.
 * @property isLoading whether a screen read is currently in progress.
 * @property errorMessage fixed recoverable message for a failed screen read or missing snapshot.
 *
 * This pure data value is safe across threads and performs no Android work, I/O, suspension,
 * cancellation, or failure-prone behavior.
 */
data class MatchingInspectorUiState(
    val snapshot: ScreenSnapshot? = null,
    val result: TextMatchResult? = null,
    val verificationBefore: ScreenSnapshot? = null,
    val verificationAfter: ScreenSnapshot? = null,
    val verificationDecision: Decision? = null,
    val verificationResult: VerificationResult? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)
