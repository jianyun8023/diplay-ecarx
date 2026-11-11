package com.shilapi.xcertplay.hud

import android.content.Context

/** Independent guidance output, initial map visibility, media/mirror preferences and crash recovery. */
object EcarxOutputSettings {
    private const val PREFS = "diplay_ecarx_outputs_v2"
    private const val KEY_ENABLED = "dim_navigation_enabled"
    private const val KEY_AUTO_SHOW_MAP = "dim_auto_show_map_on_guidance"
    private const val KEY_CRUISE_MAP = "dim_cruise_map"
    private const val KEY_LEGACY_PRESENTATION = "dim_presentation_switch"
    private const val KEY_PRESENTATION_RELEASE_PENDING = "dim_presentation_release_pending"
    private const val KEY_MIRROR_MAIN = "dim_mirror_main_screen"
    private const val KEY_MEDIA = "dim_media_enabled"

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

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist the ECARX navigation output setting"
        }
    }

    fun autoShowMap(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_SHOW_MAP, true)

    fun setAutoShowMap(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_AUTO_SHOW_MAP, enabled).commit()) {
            "Could not persist the ECARX initial map visibility setting"
        }
    }

    /** Whether the cluster map also shows while CarPlay runs without a route. Off by default. */
    fun cruiseMap(context: Context): Boolean = prefs(context).getBoolean(KEY_CRUISE_MAP, false)

    fun setCruiseMap(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_CRUISE_MAP, enabled).commit()) {
            "Could not persist the ECARX cruise map setting"
        }
    }

    /** An old "on" may own a platform window; it no longer controls this process's display intent. */
    internal fun retirePresentationSwitch(context: Context) {
        val saved = prefs(context)
        if (!saved.contains(KEY_LEGACY_PRESENTATION)) return
        val pending = saved.getBoolean(KEY_LEGACY_PRESENTATION, false) || presentationReleasePending(context)
        check(saved.edit().remove(KEY_LEGACY_PRESENTATION)
            .putBoolean(KEY_PRESENTATION_RELEASE_PENDING, pending).commit()) {
            "Could not retire the ECARX instrument-cluster projection setting"
        }
    }

    fun mirrorMainScreen(context: Context): Boolean = prefs(context).getBoolean(KEY_MIRROR_MAIN, false)

    fun setMirrorMainScreen(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_MIRROR_MAIN, enabled).commit()) {
            "Could not persist the ECARX full-screen mirror setting"
        }
    }

    /** Our projection may still need releasing; this is not the driver's current request. */
    internal fun presentationReleasePending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRESENTATION_RELEASE_PENDING, false)

    internal fun setPresentationReleasePending(context: Context, pending: Boolean) {
        if (presentationReleasePending(context) == pending) return
        check(prefs(context).edit().putBoolean(KEY_PRESENTATION_RELEASE_PENDING, pending).commit()) {
            "Could not persist the ECARX projection release state"
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
