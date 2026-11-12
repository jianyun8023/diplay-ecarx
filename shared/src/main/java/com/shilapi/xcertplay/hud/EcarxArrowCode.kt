package com.shilapi.xcertplay.hud

/**
 * The cluster's turn-arrow code, recovered from the head unit's own navigation app.
 *
 * Provenance — `ecarx.naviservice` (the shipped `导航` app, 2.0.20133112.4135), not documentation:
 *  * `ecarx/naviservice/map/amap/a.smali` `protected static a(II)I` + its `<clinit>`
 *    `SparseIntArray` hold [amapToEcarx], verbatim.
 *  * `ecarx/naviservice/a/g.smali` (`NaviDashboardManager`) maps a maneuver to an int and calls
 *    `INaviInteraction.updateTurnByTurnArrow(int)`; it skips the call when the id is `<= 0`.
 *  * `ecarx/naviservice/a/o.smali` `getNavigationTurnId()` returns **0** while navigation is idle,
 *    and the same class returns `""` from `getNavigationTurnSVG()` — so the SVG the cluster renders
 *    does not travel through `INavigationInfo`; it is *pushed* through
 *    `updateTurnByTurnArrow(String)` (see [EcarxDimApi.updateTurnByTurnArrow]).
 *
 * The half the bytecode could not give us — which Apple maneuver deserves which id — comes from
 * the car instead. An independent port of this project for the same head unit swept ids 100–130
 * and 131–200 through `updateTurnByTurnArrow(int)` on the vehicle and reported an icon for every
 * id in 101–143; [appleToAmap] encodes that sweep. Where the two vocabularies overlap they agree
 * point for point (102 left, 103 right, 108 U-turn, 109 straight, 111 enter roundabout, 112 exit
 * roundabout, 115 destination, 101 start), so the two recoveries cross-check each other rather
 * than resting on one person's reading.
 */
internal object EcarxArrowCode {
    init { com.shilapi.xcertplay.airplay.NativeMedia.requireAvailable() }

    fun amapToEcarx(iconId: Int, aroundNum: Int = 0): Int = NativeClusterArrow.amapToEcarx(iconId, aroundNum)
    fun ecarxToAmap(arrowId: Int): Int = NativeClusterArrow.ecarxToAmap(arrowId)
    fun appleToAmap(type: Int, drivingSide: Int): Int = NativeClusterArrow.appleToAmap(type, drivingSide)
    fun arrowId(appleType: Int, drivingSide: Int): Int = NativeClusterArrow.arrowId(appleType, drivingSide)

    const val NO_ARROW = 0
    // The stock SVG generator skips this ID; the numeric channel can render it.
    const val NO_ICON = 120
}

internal object NativeClusterArrow {
    @JvmStatic external fun amapToEcarx(iconId: Int, aroundNum: Int): Int
    @JvmStatic external fun ecarxToAmap(arrowId: Int): Int
    @JvmStatic external fun appleToAmap(type: Int, drivingSide: Int): Int
    @JvmStatic external fun arrowId(appleType: Int, drivingSide: Int): Int
}
