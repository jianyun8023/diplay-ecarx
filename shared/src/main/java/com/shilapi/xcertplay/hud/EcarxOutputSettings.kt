package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * The ECARX instrument-cluster output: one switch plus the two OEM-side toggles it needs.
 *
 * Kept in its own preferences file so the ECARX switches never alias another head unit's switches
 * by accident, and so a build that ships the same code on a different head unit has an inert,
 * empty store.
 */
object EcarxOutputSettings {
    private const val PREFS = "diplay_ecarx_outputs"
    private const val KEY_ENABLED = "dim_navigation_enabled"
    private const val KEY_PRESENTATION = "dim_presentation_switch"
    private const val KEY_NAVI_MODE = "dim_navi_mode"
    private const val KEY_ARROW_EXPERIMENTAL = "dim_turn_arrow_experimental"
    private const val KEY_MEDIA = "dim_media_enabled"
    private const val KEY_PROJECTION_NAVIGATION = "dim_projection_navigation"
    private const val KEY_PROJECTION_MEDIA = "dim_projection_media"
    private const val KEY_PRESENTATION_PACKAGE = "dim_presentation_package"
    private const val KEY_NAVI_CARD = "dim_navi_card_event"

    /**
     * The package whose window the cluster should mirror, taken from the stock navigation app's own
     * call (`ecarx/naviservice/a/g.smali` passes its `a` field twice).
     */
    const val STOCK_NAVIGATION_PACKAGE = "com.autonavi.amapauto"

    /**
     * NAVI_MODE_OFF leaves the cluster's own navigation source alone. Every other value asks the
     * platform to prefer the external source; the stock ECARX apps only ever use "off"/"on", and
     * the exact meaning of the remaining ids is unverified, so only 0 and 2 are offered in the UI.
     */
    const val NAVI_MODE_OFF = 0
    const val NAVI_MODE_ON = 2

    /**
     * "The user has never asked for anything." Distinct from [NAVI_MODE_OFF] on purpose:
     * `setNaviMode` comes from `ecarx.dimprotocol.DIMProtocolManager`, which is referenced only by
     * the reference app that is *not* installed on the target head unit — weaker evidence than the DIM
     * path itself. So an untouched install never calls it at all, and no value we cannot explain
     * is ever written to the platform.
     */
    const val NAVI_MODE_UNSET = -1

    /** Whether this head unit exposes the ECARX DIM interface (and therefore the settings block). */
    fun available(context: Context): Boolean = EcarxCapabilities.available(context)

    /**
     * Whether the head unit ships `ecarx.dimprotocol.DIMProtocolManager` at all.
     *
     * The target unit does not: probe v2 found 23 of 26 ECARX classes but this one (and its
     * `IDIMProtocolManager`) is absent from the system image, so [requestNaviMode] can only ever
     * fail there. The settings screen hides the switch instead of offering one that cannot work.
     */
    fun naviModeSupported(): Boolean =
        EcarxCapabilities.classPresent(EcarxCapabilities.DIM_PROTOCOL_MANAGER)

    /**
     * Whether the head unit ships the DIM media interface — present on the target unit, and hidden
     * from the settings screen where it is not, for the same reason as [naviModeSupported].
     */
    fun mediaSupported(): Boolean = EcarxCapabilities.classPresent(EcarxCapabilities.MEDIA_INTERACTION)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist the ECARX navigation output setting"
        }
    }

    /** The 仪表投屏 switch: mirrors the centre screen's presentation onto the cluster. */
    fun presentationSwitch(context: Context): Boolean = prefs(context).getBoolean(KEY_PRESENTATION, false)

    fun setPresentationSwitch(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PRESENTATION, enabled).commit()) {
            "Could not persist the ECARX instrument-cluster projection setting"
        }
    }

    /** `DIMProtocolManager.setNaviMode`. Applies at once and survives a reconnect. */
    fun naviMode(context: Context): Int = prefs(context).getInt(KEY_NAVI_MODE, NAVI_MODE_UNSET)

    fun setNaviMode(context: Context, mode: Int) {
        check(prefs(context).edit().putInt(KEY_NAVI_MODE, mode).commit()) {
            "Could not persist the ECARX cluster navigation mode"
        }
    }

    /**
     * Whether the CarPlay maneuver is also pushed as a cluster turn arrow.
     *
     * On by default. The ids it sends are no longer a best guess: the manoeuvre-to-id mapping was
     * recovered from Apple's own type table plus an on-car sweep of ids 101–143 on this head unit,
     * and it agrees with the head unit's own navigation app wherever the two vocabularies overlap
     * (see [EcarxArrowCode]). Turning it off leaves the cluster's arrow alone so it keeps showing
     * whatever its own source decided.
     */
    fun turnArrowExperimental(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARROW_EXPERIMENTAL, true)

    fun setTurnArrowExperimental(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ARROW_EXPERIMENTAL, enabled).commit()) {
            "Could not persist the ECARX turn-arrow setting"
        }
    }

    /**
     * Whether CarPlay's now-playing state is also published to the cluster's media card.
     *
     * Off by default until the car has confirmed the cluster takes it, and independent of [enabled]:
     * this is the DIM *media* channel, not the navigation one. Either is worth having without the
     * other — the cluster's media card used to be fed by the head unit's own Bluetooth source
     * reading AVRCP metadata off the iPhone, which is exactly what stops happening when the head
     * unit's Bluetooth adapter is switched off.
     */
    fun mediaEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MEDIA, false)

    fun setMediaEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_MEDIA, enabled).commit()) {
            "Could not persist the ECARX media output setting"
        }
    }

    /**
     * Whether guidance claims the phone as the cluster's **navigation source**
     * (`ihu/info/phoneprojection` ⇒ `carPlayNavigation`).
     *
     * **Off by default, which reverses what this switch shipped as.** It was turned on because it
     * was the only configuration in which an arrow had ever reached the cluster — but the arrow it
     * produced was not the DIM arrow: the cluster was mirroring CarPlay's own navigation view. That
     * mirror takes a **rectangular** region of the cluster and does not follow its shape (the
     * driver's report: "a square picture that does not fit"), whereas the head unit's own renderer —
     * driven by the 仪表投屏 switch alone — fills the cluster's navigation area exactly.
     *
     * The dependency only existed while the numeric turn-id channel was dead. With ids going out
     * (see [EcarxArrowCode]) the arrow no longer needs the mirror, so the mirror is no longer taken
     * on the driver's behalf. Turn it back on to see the CarPlay screen on the cluster as one
     * rectangle; leave it off to let the car draw its own navigation page.
     */
    fun navigationProjection(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROJECTION_NAVIGATION, false)

    fun setNavigationProjection(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PROJECTION_NAVIGATION, enabled).commit()) {
            "Could not persist the ECARX navigation-projection setting"
        }
    }

    /**
     * Whether the media card claims the phone as its source (`ihu/info/phoneprojection` ⇒
     * `carPlayMedia`).
     *
     * On by default, and **independent of [navigationProjection]**: without the claim the card reads
     * `无媒体` no matter what is sent on the DIM media channel, because it is drawn from the head
     * unit's own sources. The two axes share one topic but not one value, so keeping the media claim
     * costs the navigation area nothing — which is the whole reason the single switch this used to
     * be got split: turning the mirror off for the driver also took the media card with it.
     */
    fun mediaProjection(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROJECTION_MEDIA, true)

    fun setMediaProjection(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PROJECTION_MEDIA, enabled).commit()) {
            "Could not persist the ECARX media-projection setting"
        }
    }

    /**
     * The package whose window the cluster mirrors, i.e. the two string arguments of
     * `setPresentationToDimSwitch`.
     *
     * `""` asks the platform to mirror the centre screen, which is what this project has always
     * sent: the driver reports the resulting card as a square picture that does not fit the
     * cluster's shape. The stock navigation app names its own package instead, and that is the only
     * argument of that call never yet varied, so it is exposed rather than guessed at — the car has
     * to say whether naming a package changes *what* is mirrored or only *whether* it is.
     *
     * Read at every switch write rather than cached, so a change reaches the cluster on the next
     * watch attempt without restarting anything.
     */
    fun presentationPackage(context: Context): String =
        prefs(context).getString(KEY_PRESENTATION_PACKAGE, "") ?: ""

    fun setPresentationPackage(context: Context, value: String) {
        check(prefs(context).edit().putString(KEY_PRESENTATION_PACKAGE, value).commit()) {
            "Could not persist the ECARX presentation package"
        }
    }

    /**
     * The packages offered in the settings screen: the centre screen, this app's own window (the
     * stock app names itself, so this is the closest analogue), and the stock navigation app.
     *
     * No free-text entry on purpose — every value here is one the head unit is known to name itself,
     * and a typo would be indistinguishable from "the platform ignored the call".
     */
    fun presentationPackages(context: Context): List<String> =
        listOf("", context.packageName, STOCK_NAVIGATION_PACKAGE)

    /**
     * Whether guidance also publishes the head unit's "a navigation is running" event
     * (`ihu/events/navigation/ongoing`).
     *
     * Off by default. It was worth trying while the cluster's navigation page never appeared, but
     * the page is drawn by the head unit's own renderer as soon as the 仪表投屏 switch is on, and
     * this event additionally raises a **floating distance/turn card** inside that page — which is
     * the card that made the cluster look like it was being taken over rather than filled. Turn it
     * on to have DiPlay also tell the cluster that a navigation exists.
     */
    fun naviCardEvent(context: Context): Boolean = prefs(context).getBoolean(KEY_NAVI_CARD, false)

    fun setNaviCardEvent(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_NAVI_CARD, enabled).commit()) {
            "Could not persist the ECARX navigation-card event setting"
        }
    }

    // ---------------------------------------------------------------- live apply

    /**
     * The settings screen lives in the `common` module, so it cannot reach the `internal`
     * [EcarxHudBridge] directly. These delegates wire a switch straight to the head unit and
     * return whether the platform accepted the call.
     */
    fun requestPresentation(context: Context, show: Boolean, origin: String = ""): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyPresentation(show, origin).ok
    }

    /** Applies the navigation-axis claim at once, withdrawing any claim already published. */
    fun requestNavigationProjection(context: Context, enabled: Boolean): Boolean {
        EcarxHudBridge.initialize(context)
        EcarxHudBridge.applyNavigationProjection(enabled)
        return true
    }

    /** Applies the media-axis claim at once, withdrawing it when turned off. */
    fun requestMediaProjection(context: Context, enabled: Boolean): Boolean {
        EcarxHudBridge.initialize(context)
        EcarxHudBridge.applyMediaProjection(enabled)
        return true
    }

    /**
     * Stores the presentation package and re-issues the switch if the mirror is currently wanted.
     *
     * Re-issued rather than waited for: the watch's next attempt is up to [EcarxHudBridge]'s slow
     * phase away, and a test that has to guess when it took effect is a worse test.
     */
    fun requestPresentationPackage(context: Context, value: String): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyPresentationPackage(value)
    }

    fun requestNaviMode(context: Context, mode: Int): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyNaviMode(mode).ok
    }

    fun diagnosticReport(context: Context): String = EcarxHudBridge.diagnostics(context)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
