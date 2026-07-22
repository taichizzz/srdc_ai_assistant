package com.foxconn.seeandsay.bridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * The result of walking one live accessibility tree: the immutable [snapshot] handed to callers, and
 * the parallel [nodes] list that keeps `elements[i]` aligned with the live node index `i` acts on.
 *
 * @property snapshot pure, safe-to-keep description of the screen.
 * @property nodes live `AccessibilityNodeInfo` per element, valid only until the next snapshot; the
 * click target M2.2 will operate on. Never expose these outside the `bridge` package.
 */
class BuiltSnapshot(
    val snapshot: ScreenSnapshot,
    val nodes: List<AccessibilityNodeInfo>,
)

/**
 * Converts a live `AccessibilityNodeInfo` tree into a compact [ScreenSnapshot] (docs/ARCHITECTURE §5.1).
 *
 * Selection rules, in order:
 * - include only nodes that are **visible** AND (**clickable** OR **editable** OR text-bearing);
 * - `text` = node text → content description → a labelled child's text, trimmed;
 * - a text-bearing but non-clickable node borrows the nearest clickable ancestor as its click target,
 *   so the emitted node is the one `click()` should act on;
 * - de-duplicate by (text, click-target bounds) and cap the list at [MAX_ELEMENTS] so the snapshot
 *   stays small enough to later embed in an LM prompt.
 *
 * This is a stateless transform; all `AccessibilityNodeInfo` access stays within the `bridge` package.
 */
object SnapshotBuilder {

    /** Upper bound on emitted elements so a busy screen still yields a prompt-sized snapshot. */
    const val MAX_ELEMENTS = 40

    /**
     * Builds a [BuiltSnapshot] from the active window root.
     *
     * @param root the tree returned by `getRootInActiveWindow()`, or `null` when unavailable.
     * @param screen coarse screen identifier (currently the foreground package name).
     * @param capturedAt epoch-millis timestamp recorded on the returned snapshot.
     * @return the immutable snapshot plus its aligned live-node list; empty when [root] is `null`.
     *
     * Pure CPU work over the supplied tree; performs no I/O and does not suspend.
     */
    fun build(root: AccessibilityNodeInfo?, screen: String, capturedAt: Long): BuiltSnapshot {
        if (root == null) {
            return BuiltSnapshot(ScreenSnapshot(screen, capturedAt, emptyList()), emptyList())
        }

        val elements = ArrayList<ScreenElement>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        val bounds = Rect()

        traverse(root) { node ->
            if (elements.size >= MAX_ELEMENTS) return@traverse false
            if (!node.isVisibleToUser) return@traverse true

            val clickableSelf = node.isClickable
            val editable = node.isEditable
            val text = extractText(node)
            if (text.isEmpty() && !clickableSelf && !editable) return@traverse true

            val clickTarget = when {
                clickableSelf || editable -> node
                else -> nearestClickableAncestor(node)
            }
            // A blank-text node that is only reachable via an ancestor adds no value to the list.
            if (text.isEmpty() && clickTarget == null) return@traverse true

            val boundsHolder = clickTarget ?: node
            boundsHolder.getBoundsInScreen(bounds)
            val key = "$text@${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            if (!seen.add(key)) return@traverse true

            elements.add(
                ScreenElement(
                    i = elements.size,
                    text = text,
                    clickable = clickTarget != null,
                    editable = editable,
                    bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                ),
            )
            nodes.add(clickTarget ?: node)
            true
        }

        return BuiltSnapshot(ScreenSnapshot(screen, capturedAt, elements), nodes)
    }

    /**
     * Depth-first walk of the tree, invoking [onNode] for each node.
     *
     * @param root subtree root to visit.
     * @param onNode visitor returning `true` to keep traversing and `false` to stop early (used to
     * halt once [MAX_ELEMENTS] is reached).
     */
    private fun traverse(root: AccessibilityNodeInfo, onNode: (AccessibilityNodeInfo) -> Boolean) {
        if (!onNode(root)) return
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            traverse(child, onNode)
        }
    }

    /**
     * Resolves the display text of [node]: its own text, else content description, else the first
     * non-blank text found among its direct children (a common label-inside-container pattern).
     *
     * @param node node to describe.
     * @return trimmed display text, or an empty string when none is available.
     */
    private fun extractText(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    /**
     * Climbs parent links from [node] to the nearest clickable ancestor.
     *
     * @param node starting node (already known to be non-clickable itself).
     * @return the closest clickable ancestor, or `null` when none exists up to the root.
     */
    private fun nearestClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }
}
