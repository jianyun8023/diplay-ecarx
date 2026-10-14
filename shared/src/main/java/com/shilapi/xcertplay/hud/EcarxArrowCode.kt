package com.shilapi.xcertplay.hud

/**
 * The cluster's turn-arrow code, recovered from the head unit's own navigation app.
 *
 * Provenance — `ecarx.naviservice` (the shipped `导航` app, 2.0.20133112.4135), not documentation:
 *  * `ecarx/naviservice/map/amap/a.smali` `protected static a(II)I` + its `<clinit>`
 *    `SparseIntArray` hold the whole table below, verbatim.
 *  * `ecarx/naviservice/a/g.smali` (`NaviDashboardManager`) maps a maneuver to an int and calls
 *    `INaviInteraction.updateTurnByTurnArrow(int)`; it skips the call when the id is `<= 0`.
 *  * `ecarx/naviservice/a/o.smali` `getNavigationTurnId()` returns **0** while navigation is idle,
 *    and the same class returns `""` from `getNavigationTurnSVG()` — so the SVG the cluster renders
 *    does not travel through `INavigationInfo`; it is *pushed* through
 *    `updateTurnByTurnArrow(String)` (see [EcarxDimApi.updateTurnByTurnArrow]).
 *
 * A machine-readable copy lives in `适配文件/ecarx-arrowId-码表.tsv`.
 */
internal object EcarxArrowCode {

    /** What `getNavigationTurnId()` reports while there is nothing to draw. */
    const val NO_ARROW = 0

    /**
     * The one arrow id the stock app refuses to draw for: `widget/q` skips generating the arrow
     * drawing when the mapped id is `0x78` (120), which is where Amap's icon id 20 lands.
     */
    const val NO_ICON = 120

    /**
     * Amap's `GuideInfoProtocolData.icon` / `ManeuverIconModel.iconId` to the cluster's arrow id.
     *
     * The roundabout prefix is part of the stock algorithm, not an embellishment: for a roundabout
     * exit number in `1..10` the app rewrites the icon id into a per-exit code first, then looks
     * that up. Both rewrites are reproduced here; everything else is the table.
     *
     * Unknown ids answer [NO_ARROW], which is what the original `SparseIntArray` does for a missing
     * key (its default is 0) — not an invented fallback.
     */
    fun amapToEcarx(iconId: Int, aroundNum: Int = 0): Int {
        val id = when {
            aroundNum in 1..10 && (iconId == 11 || iconId == 12 || iconId == 22) -> aroundNum + 0x78
            aroundNum in 1..10 && (iconId == 17 || iconId == 18 || iconId == 25) -> aroundNum + 0x82
            else -> iconId
        }
        return when (id) {
            in 1..20 -> id + 100
            21 -> 141
            22 -> 142
            23 -> 112
            24 -> 143
            25 -> 144
            26 -> 145
            27 -> 118
            28 -> 146
            in 121..140 -> id
            else -> NO_ARROW
        }
    }

    /**
     * Reverse lookup, for reading the code back out of a log line.
     *
     * The table is **not injective** — Amap 12 and 23 both land on 112, 18 and 27 both on 118 — so
     * the lowest matching Amap id is returned and `-1` means "not a value this table produces".
     */
    fun ecarxToAmap(arrowId: Int): Int {
        for (id in 1..28) {
            if (amapToEcarx(id) == arrowId) return id
        }
        for (id in 121..140) {
            if (amapToEcarx(id) == arrowId) return id
        }
        return -1
    }

    /**
     * Apple's `RouteGuidanceManeuverType` to Amap's icon id.
     *
     * **Deliberately unimplemented: this leg is not recoverable from the artifacts we hold.**
     * The head unit gave us the Amap-to-ECARX half (above) but no Amap icon-id semantics — the icon
     * ids are Amap's own vocabulary, and the stock app never needs to state what they mean because
     * it receives them already decoded. Filling this in from guesswork would put fabricated data on
     * a safety-adjacent display, so it returns `-1` and the callers fall back to the SVG drawing,
     * which carries the shape and needs no table at all.
     *
     * To fill it in: recover Amap's own icon-id to drawable mapping (the `AmapAuto` APK ships the
     * icons; the order is the semantics) and write the 20-odd entries here.
     */
    fun appleToAmap(type: Int, drivingSide: Int): Int = -1

    /**
     * The value to report through `getNavigationTurnId()` / `updateTurnByTurnArrow(int)`.
     *
     * Returns [NO_ARROW] whenever the Apple side cannot be named in Amap's vocabulary. That is a
     * deliberate downgrade from the counter this used to be: the field is the cluster's arrow code,
     * so a ramp of 1, 2, 3 … asks it to draw unrelated icons, while 0 is the exact value the stock
     * app reports when there is nothing to show.
     */
    fun arrowId(appleType: Int, drivingSide: Int): Int {
        val amap = appleToAmap(appleType, drivingSide)
        return if (amap > 0) amapToEcarx(amap) else NO_ARROW
    }
}
