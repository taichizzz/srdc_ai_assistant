package com.foxconn.seeandsay.bridge

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import kotlinx.coroutines.delay

/**
 * Signals that the Bridge cannot serve a request because the accessibility service is not connected
 * or the active window content is unavailable.
 *
 * @param message non-secret, user-readable description of why the Bridge is unavailable.
 * @param cause optional underlying platform failure.
 *
 * Construction performs no I/O and is safe on any dispatcher. The frozen [UiBridge] contract lets
 * implementation failures propagate to the caller; this is this implementation's failure type.
 */
class BridgeUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The real [UiBridge], backed by the connected [SeeAndSayService].
 *
 * [readScreen] resolves the active window root (retrying a transient null), converts the tree with
 * [SnapshotBuilder], and remembers the live node list so [click] / [setText] can address the same
 * elements by index. Actions operate on that remembered list: a stale, recycled, or out-of-range
 * node yields `false` (the contract's "rejected" signal) rather than an exception. All
 * `AccessibilityNodeInfo` handling stays inside this package.
 *
 * @param serviceProvider supplies the connected service; overridable for testing. Defaults to the
 * process-wide [SeeAndSayService.instance].
 */
class AccessibilityBridge(
    private val serviceProvider: () -> SeeAndSayService? = { SeeAndSayService.instance },
) : UiBridge {

    /** Live nodes aligned with the most recent snapshot's element indices; empty until first read. */
    @Volatile
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    /**
     * Reads the current foreground screen into a [ScreenSnapshot].
     *
     * @return the snapshot for the active window.
     * @throws BridgeUnavailableException when the service is disabled or the root is unavailable
     * after [ROOT_RETRIES] attempts.
     *
     * Suspends only while briefly retrying a transient null root; the tree walk itself is synchronous.
     */
    override suspend fun readScreen(): ScreenSnapshot {
        val service = serviceProvider()
            ?: throw BridgeUnavailableException(
                "無障礙服務尚未啟用，請先到「設定 → 無障礙」開啟本服務。",
            )

        val root = resolveRoot(service)
        val screen = root.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: "unknown"
        val built = SnapshotBuilder.build(root, screen, System.currentTimeMillis())
        lastNodes = built.nodes
        return built.snapshot
    }

    /**
     * Clicks the element at [elementIndex] from the most recent [readScreen] result (`ui_click`).
     *
     * @param elementIndex the `i` of a snapshot element.
     * @return `true` when the platform accepted the click action on a live node; `false` when the
     * index is unknown, no snapshot was taken yet, or the node is stale/rejected the action.
     *
     * The caller is expected to re-read and verify the screen afterwards (M2.3); a click without a
     * verified change is a failed step (docs/ARCHITECTURE §6).
     */
    override suspend fun click(elementIndex: Int): Boolean {
        val node = lastNodes.getOrNull(elementIndex) ?: return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
    }

    /**
     * Replaces the text of the editable element at [elementIndex] (`ui_set_text`).
     *
     * @param elementIndex the `i` of a snapshot element; must refer to an editable node.
     * @param text complete replacement text for the field.
     * @return `true` when the platform accepted the set-text action; `false` when the index is
     * unknown, the node is not editable, or the node is stale/rejected the action.
     */
    override suspend fun setText(elementIndex: Int, text: String): Boolean {
        val node = lastNodes.getOrNull(elementIndex) ?: return false
        if (!node.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }
            .getOrDefault(false)
    }

    /**
     * Performs one global back navigation (`ui_back`).
     *
     * @return `true` when the connected service dispatched the global action; `false` when the
     * service is not connected or the platform rejected it.
     */
    override suspend fun back(): Boolean {
        val service = serviceProvider() ?: return false
        return runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            .getOrDefault(false)
    }

    /**
     * Returns the active window root, retrying briefly because `getRootInActiveWindow()` may return
     * null while a screen is still settling (docs/ARCHITECTURE §8).
     *
     * @param service the connected accessibility service.
     * @return a non-null active window root.
     * @throws BridgeUnavailableException when no root is available after [ROOT_RETRIES] attempts.
     */
    private suspend fun resolveRoot(service: SeeAndSayService): AccessibilityNodeInfo {
        repeat(ROOT_RETRIES) { attempt ->
            service.rootInActiveWindow?.let { return it }
            if (attempt < ROOT_RETRIES - 1) delay(ROOT_RETRY_DELAY_MS)
        }
        throw BridgeUnavailableException(
            "無法讀取當前畫面（getRootInActiveWindow 回傳 null）。請確認前景有 App 並重試。",
        )
    }

    private companion object {
        const val ROOT_RETRIES = 3
        const val ROOT_RETRY_DELAY_MS = 150L
    }
}
