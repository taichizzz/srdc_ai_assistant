package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.normalization.TextNormalizer

/**
 * Central reviewable synonym table used only by the alias tier of [TextMatcher].
 *
 * The supported groups are 設定 / 設置 / Settings, 音樂 / 播放器 / Music, and
 * 導航 / 地圖 / Navigation. Every table entry passes through the one shared [TextNormalizer]
 * before lookup; no raw string is compared. Adding a synonym therefore requires one data-only
 * change rather than another conditional branch.
 *
 * Initialization and [canonicalForm] are deterministic, synchronous, thread-safe, and safe on any
 * dispatcher. They perform no I/O or coroutine work. Duplicate normalized aliases would fail fast
 * with [IllegalArgumentException] if they were ever assigned to different canonical groups.
 */
object TextAliases {

    /** Human-reviewable source groups; the first value is each group's canonical spelling. */
    private val aliasGroups =
        listOf(
            listOf("設定", "設置", "Settings"),
            listOf("音樂", "播放器", "Music"),
            listOf("導航", "地圖", "Navigation"),
        )

    /** Normalized alias-to-canonical lookup derived once from [aliasGroups]. */
    private val canonicalByAlias: Map<String, String> =
        buildMap {
            aliasGroups.forEach { group ->
                val canonical = TextNormalizer.normalize(group.first())
                group.forEach { alias ->
                    val normalizedAlias = TextNormalizer.normalize(alias)
                    val previous = put(normalizedAlias, canonical)
                    require(previous == null || previous == canonical) {
                        "Normalized alias belongs to more than one canonical group"
                    }
                }
            }
        }

    /**
     * Finds the canonical alias group for a previously normalized comparison key.
     *
     * @param normalizedText non-raw key produced by [TextNormalizer.normalize].
     * @return normalized canonical group key, or `null` when the table has no entry.
     *
     * This pure lookup performs no normalization, I/O, suspension, or dispatcher switch and has no
     * expected failure after successful object initialization.
     */
    fun canonicalForm(normalizedText: String): String? = canonicalByAlias[normalizedText]
}
