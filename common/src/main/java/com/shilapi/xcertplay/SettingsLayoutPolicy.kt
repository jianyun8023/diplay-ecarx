package com.shilapi.xcertplay

/** Chooses the settings information architecture from usable window dimensions, not pixels. */
internal object SettingsLayoutPolicy {
    // 840 keeps 1280-pixel units at 240 dpi (853 dp) on the rail.
    const val EXPANDED_MIN_WIDTH_DP = 840
    // The rail scrolls on its own, so short ultrawide units (1920x720 at 240 dpi, 480 dp) still get it.
    const val EXPANDED_MIN_HEIGHT_DP = 400
    // Two overview columns need about 320 dp each beside the rail.
    const val OVERVIEW_TWO_COLUMN_MIN_WIDTH_DP = 1000
    const val RAIL_WIDTH_DP = 240
    private const val MAX_RAIL_SCALE = 1.5f

    // Larger text needs proportionally more width before the rail and content fit side by side.
    fun isExpanded(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        widthDp >= EXPANDED_MIN_WIDTH_DP * fontScale.coerceAtLeast(1f) &&
            heightDp >= EXPANDED_MIN_HEIGHT_DP

    fun overviewHasTwoColumns(widthDp: Int, fontScale: Float): Boolean =
        widthDp >= OVERVIEW_TWO_COLUMN_MIN_WIDTH_DP * fontScale.coerceAtLeast(1f)

    fun railWidthDp(fontScale: Float): Int = (RAIL_WIDTH_DP * fontScale.coerceIn(1f, MAX_RAIL_SCALE)).toInt()

    const val RAIL_ITEM_DP = 52
    const val RAIL_ITEM_MIN_DP = 40
    const val RAIL_GAP_DP = 8
    const val RAIL_GAP_MIN_DP = 2
    /** The rail card's top and bottom padding plus the scroll's bottom inset. */
    const val RAIL_FRAME_DP = 36

    data class RailSpacing(val itemDp: Int, val gapDp: Int)

    /**
     * Destination height and gap that fit [count] destinations in the [availableDp] the rail gets.
     * That height changes with the status bar, the navigation bar and the head unit's own dock, so
     * it is measured, not derived from the screen. Gaps shrink before destinations; below both
     * minimums the rail keeps them and scrolls.
     */
    fun railSpacing(availableDp: Float, count: Int): RailSpacing {
        val gaps = (count - 1).coerceAtLeast(1)
        if (count * RAIL_ITEM_DP + gaps * RAIL_GAP_DP + RAIL_FRAME_DP <= availableDp) {
            return RailSpacing(RAIL_ITEM_DP, RAIL_GAP_DP)
        }
        val gap = ((availableDp - RAIL_FRAME_DP - count * RAIL_ITEM_DP) / gaps).toInt()
            .coerceIn(RAIL_GAP_MIN_DP, RAIL_GAP_DP)
        val item = ((availableDp - RAIL_FRAME_DP - gaps * gap) / count).toInt()
            .coerceIn(RAIL_ITEM_MIN_DP, RAIL_ITEM_DP)
        return RailSpacing(item, gap)
    }
}
