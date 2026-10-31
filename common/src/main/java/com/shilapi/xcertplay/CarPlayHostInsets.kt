package com.shilapi.xcertplay

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/**
 * The ordinary host lays out behind the bars, but never puts its content under a bar the driver keeps.
 * Reserve the requested bars even while another window temporarily hides them. Using visible insets
 * here would turn every foreground transition into a different video fit (and a different touch map).
 * Multi-window and Harman retain their existing window-management paths instead.
 */
internal fun carPlayHostContentInsets(
    insets: WindowInsetsCompat,
    hideTop: Boolean,
    hideBottom: Boolean,
): Insets {
    // An actual keyboard is not a transient system-bar change: keep editors reachable.
    var safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
    if (!hideTop) safe = Insets.max(safe,
        insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()))
    if (!hideBottom) safe = Insets.max(safe,
        insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()))
    return safe
}
