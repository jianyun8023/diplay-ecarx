package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * The ECARX instrument-cluster output: three switches, each one a picture the driver can see.
 *
 * Kept in its own preferences file so the ECARX switches never alias another head unit's switches
 * by accident, and so a build that ships the same code on a different head unit has an inert,
 * empty store.
 *
 * **Six switches were removed from this store** (fix33). Every one of them was a question the car
 * had already answered, and leaving them on the settings page meant the driver had to get six
 * answers right before the cluster showed anything — six chances to get it wrong where one would do:
 *
 * | removed | why it is not a question any more |
 * |---|---|
 * | 投屏包名 (`setPresentationToDimSwitch` 的两个字符串) | the platform discards the first three arguments; `""` is what the stock apps send, and the only value measured working on this head unit |
 * | 箭头实验开关 | the ids are settled (`EcarxArrowCode` + the on-car 101–143 sweep), it is not an experiment |
 * | 导航投影轴 (`carPlayNavigation`) | the projection MQTT claim is what turns the cluster's navigation page into a rectangle of mirrored CarPlay — never wanted |
 * | 媒体投影轴 (`carPlayMedia`) | without it the media card reads `无媒体` whatever is pushed; there is no picture in which it should be off |
 * | 导航进行中事件 (`ongoingNavi`) | it raises the floating distance/turn card inside the projection and DiPlay cannot close it again (its `idle` is ignored by this firmware) |
 * | 集群导航模式 (`setNaviMode`) | `ecarx.dimprotocol.DIMProtocolManager` is absent from this system image, so the switch could only ever fail |
 */
object EcarxOutputSettings {
    /**
     * A **new** store in fix33, on purpose.
     *
     * The six removed switches left values behind in the old file, and every earlier build shipped
     * [enabled] and [mediaEnabled] as `false` — so an existing install would keep reading a `false`
     * the driver chose when those two did nothing useful, and none of the new defaults below would
     * ever take effect. A fresh store is the only way a default change reaches the car; the three
     * kept switches then hold the values this build says are right.
     */
    private const val PREFS = "diplay_ecarx_outputs_v2"
    private const val KEY_ENABLED = "dim_navigation_enabled"
    private const val KEY_PRESENTATION = "dim_presentation_switch"
    private const val KEY_MEDIA = "dim_media_enabled"
    private const val KEY_LANE_GUIDANCE = "dim_lane_guidance_enabled"

    /**
     * The two string arguments of `DimInteraction.setPresentationToDimSwitch(int,String,String,boolean)`.
     *
     * Always empty. The stock ECARX apps send `""`, and `""` is the only form measured working on
     * this head unit;
     * this project spent five builds offering `""` / `com.shihab.diplay` /
     * `com.autonavi.amapauto` as a setting, and the on-car report (10-24 13:43) shows the two named
     * values producing the same picture: a mirror of the whole centre screen. The named variants are
     * gone so the driver can never land on one again.
     */
    const val PRESENTATION_PACKAGE = ""

    /**
     * Whether CarPlay guidance reaches the cluster at all. **On by default**: this build only ships
     * on a head unit whose DIM interface was probed, so an untouched install is expected to work
     * rather than to sit silent until a switch is found.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist the ECARX navigation output setting"
        }
    }

    fun laneGuidanceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LANE_GUIDANCE, true)

    fun setLaneGuidanceEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_LANE_GUIDANCE, enabled).commit()) {
            "Could not persist the ECARX lane guidance setting"
        }
    }

    /**
     * Last requested projection switch, shown by the settings row, not proof of a visible panel.
     * The bridge writes both manual and route-driven requests and clears it at process startup.
     * Keep this existing persistence behavior; it is not a separate user preference to restore.
     */
    fun presentationSwitch(context: Context): Boolean = prefs(context).getBoolean(KEY_PRESENTATION, false)

    internal fun setPresentationSwitch(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PRESENTATION, enabled).commit()) {
            "Could not persist the ECARX instrument-cluster projection setting"
        }
    }

    /**
     * Whether the CarPlay maneuver is also pushed as a cluster turn arrow. **Always true.**
     *
     * Kept as a function rather than a `const` because six call sites pass it a `Context`; the
     * switch it used to read is gone, and the ids it sends are settled: the manoeuvre-to-id mapping
     * was recovered from Apple's own type table plus an on-car sweep of ids 101–143 on this head
     * unit (see [EcarxArrowCode]), and the 10-24 report shows `arrow=id:102` reaching the platform.
     */
    fun turnArrowExperimental(context: Context): Boolean = true

    /**
     * Whether CarPlay's now-playing state is also published to the cluster's media card.
     *
     * **On by default.** The cluster's media card used to be fed by the head unit's own Bluetooth
     * source reading AVRCP metadata off the iPhone, which is exactly what stops happening when the
     * head unit's Bluetooth adapter is switched off — so without this channel the card goes blank
     * the moment CarPlay starts working.
     */
    fun mediaEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MEDIA, true)

    fun setMediaEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_MEDIA, enabled).commit()) {
            "Could not persist the ECARX media output setting"
        }
    }

    /**
     * Whether guidance claims the phone as the cluster's **navigation source**
     * (`ihu/info/phoneprojection` ⇒ `carPlayNavigation`). **Always false.**
     *
     * It was on while the numeric turn-id channel was dead, because it was the only configuration
     * in which an arrow reached the cluster — but that arrow was not the DIM arrow, it was the
     * cluster mirroring CarPlay's own navigation view as a **rectangle that does not follow the
     * cluster's shape**. With ids going out (fix32, confirmed on car) the claim buys nothing and
     * costs the shape, so it is no longer offered.
     */
    fun navigationProjection(context: Context): Boolean = false

    /**
     * Whether the media card claims the phone as its source (`ihu/info/phoneprojection` ⇒
     * `carPlayMedia`). **Always true.**
     *
     * Without the claim the card reads `无媒体` no matter what is pushed on the DIM media channel,
     * because it is drawn from the head unit's own sources. The two axes share one topic but not one
     * value, so this claim costs the navigation area nothing.
     */
    fun mediaProjection(context: Context): Boolean = true

    /**
     * Whether guidance also publishes the head unit's "a navigation is running" event
     * (`ihu/events/navigation/ongoing`). **Always false.**
     *
     * That event raises a floating distance/turn card inside the cluster's projection, and this
     * firmware ignores `idle`, so DiPlay cannot take it back down. The cluster's own navigation page
     * appears without it.
     */
    fun naviCardEvent(context: Context): Boolean = false

    /** See [PRESENTATION_PACKAGE]. */
    fun presentationPackage(context: Context): String = PRESENTATION_PACKAGE

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
