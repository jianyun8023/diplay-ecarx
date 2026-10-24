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

    /** What `getNavigationTurnId()` reports while there is nothing to draw. */
    const val NO_ARROW = 0

    /**
     * The id the stock app's own **SVG generator** declines to draw: `widget/q` skips building a
     * drawing when the mapped id is `0x78` (120), which is where Amap's icon id 20 — "along the
     * road" — lands. It is a property of that drawing branch only: the numeric channel renders 120
     * on this car, so [appleToAmap] still sends it for the "along the road" maneuvers.
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
     * Apple's `RouteGuidanceManeuverType` to the Amap icon id that [amapToEcarx] expects.
     *
     * Deliberately expressed in Amap's vocabulary rather than straight in cluster ids: the
     * recovered `SparseIntArray` above stays the single source of truth for the cluster side, and
     * this leg only has to say *which* maneuver it is. Each right-hand column below is the cluster
     * id the sweep above observed for that maneuver.
     *
     * ```
     * 0, 3                  ->  9  → 109  no turn / straight ahead
     * 1, 20, 47             ->  2  → 102  left, end-of-road left, sharp left
     * 2, 21, 48             ->  3  → 103  right, end-of-road right, sharp right
     * 13, 49, 52            ->  4  → 104  keep left / slight left / highway change left
     * 14, 50, 53            ->  5  → 105  keep right / slight right / highway change right
     * 8, 9                  -> 4|5→ 104|105  ramp; the side follows the traffic side
     * 4, 18, 26             ->  8  → 108  U-turn, U-turn then start, U-turn when allowed
     * 19                    -> 24  → 143  U-turn inside a roundabout
     * 5, 15, 16, 17, 51     -> 20  → 120  along the road / ferry / change highway
     * 6                     -> 11  → 111  enter roundabout
     * 7                     -> 12  → 112  exit roundabout
     * 28..37                -> 28..37 → 121..130  roundabout exit 1..10
     * 38..46                -> 38..46 → 131..139  roundabout exit 11..19
     * 10, 12, 24, 25, 27    -> 15  → 115  destination / arrival / guidance ends
     * 11                    ->  1  → 101  route start
     * 22, 23                -> 4|5 → 104|105  highway exit left / right
     * ```
     *
     * Types with no trustworthy counterpart answer `-1`, and the caller then leaves the cluster
     * with the arrow it already had instead of being handed a guess. 106/107 (behind left/right),
     * 110 (waypoint), 114 (toll gate) and 141/142 (roundabout left/right) are in the id space but
     * have nothing in Apple's table that reliably distinguishes them.
     */
    fun appleToAmap(type: Int, drivingSide: Int): Int = when (type) {
        0, 3 -> 9
        1, 20, 47 -> 2
        2, 21, 48 -> 3
        13, 49, 52 -> 4
        14, 50, 53 -> 5
        8, 9 -> if (drivingSide == 1) 4 else 5
        4, 18, 26 -> 8
        19 -> 24
        5, 15, 16, 17, 51 -> 20
        6 -> 11
        7 -> 12
        in 28..46 -> type + 93
        10, 12, 24, 25, 27 -> 15
        11 -> 1
        22 -> 4
        23 -> 5
        else -> -1
    }

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
