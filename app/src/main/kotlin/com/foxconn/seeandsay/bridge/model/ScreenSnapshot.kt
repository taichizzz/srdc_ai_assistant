package com.foxconn.seeandsay.bridge.model

/**
 * Immutable, platform-neutral description of one captured screen.
 *
 * @property screen stable screen label supplied by the bridge.
 * @property capturedAt capture time as Unix epoch milliseconds.
 * @property elements ordered serializable-friendly values visible in this snapshot.
 *
 * This pure data value performs no I/O, has no threading affinity, and has no failure behavior.
 */
data class ScreenSnapshot(
    val screen: String,
    val capturedAt: Long,
    val elements: List<ScreenElement>,
)

/**
 * Immutable, platform-neutral description of one actionable or text-bearing screen element.
 *
 * @property i identifier used by [com.foxconn.seeandsay.bridge.UiBridge] action methods.
 * @property text visible label or accessibility description used for normalized matching.
 * @property clickable whether the bridge reports that the element can be activated.
 * @property editable whether the bridge reports that the element accepts replacement text.
 * @property bounds four integer coordinates ordered as left, top, right, bottom.
 *
 * This pure data value performs no I/O, is safe to share across threads, and has no expected
 * failure behavior. Producers are responsible for supplying four ordered [bounds] coordinates.
 */
data class ScreenElement(
    val i: Int,
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: List<Int>,
)
