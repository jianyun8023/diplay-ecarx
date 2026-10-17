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
    private const val KEY_PROJECTION = "dim_projection_channel"
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
     * Whether the CarPlay maneuver is also pushed as a raw turn arrow. Off by default: the ECARX
     * turn-id table could not be recovered from either shipped app, so the mapping is a best
     * effort and must be calibrated on the car before it is trusted.
     */
    fun turnArrowExperimental(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARROW_EXPERIMENTAL, false)

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
     * Whether guidance also claims the phone as the cluster's navigation source
     * (`ihu/info/phoneprojection` ⇒ `carPlayNavigation`).
     *
     * On by default, because that is the only configuration in which the arrow has ever reached the
     * cluster. It is also, on this head unit, the same value that makes the cluster **display**
     * CarPlay's navigation view — the driver sees a mirror while this page's 仪表投屏 switch still
     * reads off. `NavigationHandler.updateNavigationPhoneProjection` accepts exactly three values
     * (`androidAutoNavigation` / `carPlayNavigation` / `noProjection`), so there is no "data only"
     * state to fall back on and the question "does the arrow survive without it" can only be
     * answered on the car. Turning this off is that experiment: the structured DIM frames keep
     * going out, only the projection claim stops.
     */
    fun projectionChannel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROJECTION, true)

    fun setProjectionChannel(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PROJECTION, enabled).commit()) {
            "Could not persist the ECARX projection-channel setting"
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
     * On by default, because it is the last candidate for the one thing still missing: the cluster's
     * card does not appear unless the 仪表投屏 switch is on, yet our arrow, distance and road name are
     * *proven* to be on the broker already (the 12:06 report shows our SVG echoed back on
     * `ihu/info/navigation/turn-by-turn/icon`). Something else therefore decides whether the cluster
     * shows its navigation page, and this event is the only navigation-shaped signal this project
     * never sent itself. Turn it off to restore the exact behaviour of fix25.
     */
    fun naviCardEvent(context: Context): Boolean = prefs(context).getBoolean(KEY_NAVI_CARD, true)

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
    fun requestPresentation(context: Context, show: Boolean): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyPresentation(show).ok
    }

    /** Applies the projection-channel switch at once, withdrawing any claim already published. */
    fun requestProjectionChannel(context: Context, enabled: Boolean): Boolean {
        EcarxHudBridge.initialize(context)
        EcarxHudBridge.applyProjectionChannel(enabled)
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
