package com.foxconn.seeandsay.bridge

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * Frozen provider-neutral contract for reading and operating a UI surface.
 *
 * Implementations define their own threading and failure handling. Callers may invoke these
 * suspending operations from any dispatcher and must treat `false` as a rejected or failed action;
 * implementation exceptions and cancellation propagate to the caller. This declaration performs
 * no Android work and owns no resources.
 */
interface UiBridge {

    /**
     * Reads the current immutable screen representation.
     *
     * @return the latest available [ScreenSnapshot].
     *
     * This declaration performs no I/O itself. Implementations may suspend and may propagate
     * cancellation or implementation-specific failures to the caller.
     */
    suspend fun readScreen(): ScreenSnapshot

    /**
     * Requests activation of one element from the current snapshot.
     *
     * @param elementIndex snapshot element identifier to activate.
     * @return `true` when the implementation accepted and performed the action, otherwise `false`.
     *
     * This declaration has no threading affinity. Implementations may suspend and may propagate
     * cancellation or implementation-specific failures.
     */
    suspend fun click(elementIndex: Int): Boolean

    /**
     * Requests replacement of an editable element's text.
     *
     * @param elementIndex snapshot element identifier to edit.
     * @param text complete text requested for the element.
     * @return `true` when the implementation accepted and performed the action, otherwise `false`.
     *
     * This declaration has no threading affinity. Implementations may suspend and may propagate
     * cancellation or implementation-specific failures.
     */
    suspend fun setText(elementIndex: Int, text: String): Boolean

    /**
     * Requests one backward navigation action.
     *
     * @return `true` when the implementation accepted and performed the action, otherwise `false`.
     *
     * This declaration has no threading affinity. Implementations may suspend and may propagate
     * cancellation or implementation-specific failures.
     */
    suspend fun back(): Boolean
}
