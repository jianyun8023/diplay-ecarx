package com.shihab.diplay

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.airplay.AirPlaySafeArea
import com.shihab.diplay.host.R
import com.shihab.diplay.settings.SettingsTheme
import com.shihab.diplay.settings.SettingsWidgets
import kotlin.math.roundToInt

/** One text line of the in-session settings overlay. */
internal fun overlayMenuText(
    context: Context,
    text: String,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
): TextView = TextView(context).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    includeFontPadding = false
}

/**
 * The safe-area block of the in-session settings menu and the full-screen editor it opens.
 *
 * Owns the two safe-area switches, the summary line, the editor overlay and the edit in progress.
 * The host supplies the activity size the mapping is keyed by, the overlay palette, the settings
 * baseline for cancel, and what to refresh when the mapping changes. Persistence stays in
 * [AirPlayPersistence]; the host loads and saves [drawOutside] and [mainDisplayDisableSafeArea]
 * with its other settings.
 */
internal class CarPlaySafeAreaEditor(
    private val context: Context,
    private val overlayPalette: () -> DiPlayPalette,
    private val currentActivitySize: () -> CarPlayHostActivity.DisplaySize?,
    private val rememberBeforeEdit: (CarPlayHostActivity.DisplaySize) -> Unit,
    private val setSettingsMenuVisible: (Boolean) -> Unit,
    private val onSafeAreaChanged: () -> Unit,
    private val appendLog: (String) -> Unit,
) {
    /** CarPlay may draw outside the safe area. */
    var drawOutside = true
    /** The main display fills its rectangle and ignores the safe area. */
    var mainDisplayDisableSafeArea = false
    /** The full-screen editor is on top of the settings menu. */
    var editorActive = false
        private set

    private var summaryView: TextView? = null
    private var editor: View? = null
    private var editorTitle: TextView? = null
    private var editorView: SafeAreaEditorView? = null
    private var editSize: CarPlayHostActivity.DisplaySize? = null

    private val palette: DiPlayPalette get() = overlayPalette()

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        overlayMenuText(context, value, sizeSp, color, bold)

    private fun switchRow(label: String, checked: Boolean, description: String, onChanged: (Boolean) -> Unit): View =
        SettingsWidgets.createSwitchRow(
            context = context,
            label = label,
            description = description,
            checked = checked,
            theme = SettingsTheme.overlay(palette),
            contentDescription = description,
            onChanged = onChanged,
        ).rowView

    /**
     * The settings-menu block: title, summary, Set and Reset, the two switches, then the black
     * corner controls the host builds (their radius belongs to the host's corner overlay).
     */
    fun buildSection(blackCornerControls: () -> View): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            text(context.getString(R.string.safe_area), 20f, palette.overlaySecondaryText),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = text("", 15f, palette.overlayAccent)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            DiPlayButton(context).apply {
                text = context.getString(R.string.set)
                style(DiPlayButtonKind.PRIMARY, palette, overlay = true)
                setOnClickListener { open() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            DiPlayButton(context).apply {
                text = context.getString(R.string.reset)
                style(DiPlayButtonKind.SECONDARY, palette, overlay = true)
                setOnClickListener { reset() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            switchRow(
                label = context.getString(R.string.draw_outside_safe_area),
                checked = drawOutside,
                description = context.getString(R.string.allow_carplay_ui_outside_the_safe_area),
            ) { checked ->
                drawOutside = checked
                onSafeAreaChanged()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        section.addView(
            switchRow(
                label = context.getString(R.string.rect_fill_disable_safe_area),
                checked = mainDisplayDisableSafeArea,
                description = context.getString(R.string.rect_fill_disable_safe_area_description),
            ) { checked ->
                mainDisplayDisableSafeArea = checked
                updateSummary()
                onSafeAreaChanged()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        section.addView(
            text(context.getString(R.string.black_corner_overlay_description), 15f, palette.overlaySecondaryText),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) },
        )
        section.addView(
            blackCornerControls(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) },
        )
        summaryView = summary
        updateSummary()
        return section
    }

    /** The full-screen editor overlay; the host adds it to its root, hidden. */
    fun buildEditor(): View {
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(palette.overlayBackground)
            isClickable = true
        }
        val editor = SafeAreaEditorView(context, palette)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val title = text(context.getString(R.string.safe_area), 24f, palette.overlayPrimaryText, bold = true).apply {
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        overlay.addView(
            title,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            DiPlayButton(context).apply {
                text = context.getString(R.string.cancel)
                style(DiPlayButtonKind.SECONDARY, palette, overlay = true)
                setOnClickListener { close() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            DiPlayButton(context).apply {
                text = context.getString(R.string.save)
                style(DiPlayButtonKind.PRIMARY, palette, overlay = true)
                setOnClickListener { save() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        editorView = editor
        editorTitle = title
        this.editor = overlay
        return overlay
    }

    /** Recolors the editor after a day/night change. */
    fun applyPalette() {
        val overlay = editor ?: return
        overlay.setBackgroundColor(palette.overlayBackground)
        editorView?.applyPalette(palette)
        editorTitle?.setTextColor(palette.overlayPrimaryText)
    }

    /** Takes the editor off screen without ending an edit; used when the host tears its views down. */
    fun hide() {
        editor?.visibility = View.GONE
    }

    fun summary(): String {
        if (mainDisplayDisableSafeArea) {
            return context.getString(R.string.safe_area_disabled_rect_fill_summary)
        }
        val size = currentActivitySize() ?: return context.getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(context, size.width, size.height)
        return if (mapping == null) {
            "${context.getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            context.getString(
                R.string.safe_area_mapping_summary,
                mapping.width, mapping.height, mapping.left, mapping.top, size.width, size.height,
            )
        }
    }

    fun updateSummary() {
        summaryView?.text = summary()
    }

    private fun open() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = editorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(context, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        editSize = size
        editorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        setSettingsMenuVisible(false)
        editor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    /** Closes the editor without saving; a no-op when it is not open. */
    fun close() {
        if (!editorActive) return
        editorActive = false
        editSize = null
        editor?.visibility = View.GONE
        setSettingsMenuVisible(true)
        updateSummary()
        onSafeAreaChanged()
        appendLog("Safe area editor closed")
    }

    private fun save() {
        val size = editSize ?: currentActivitySize() ?: return
        val rect = editorView?.currentRectForSource() ?: return
        rememberBeforeEdit(size)
        AirPlayPersistence.saveSafeAreaRect(context, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        close()
    }

    private fun reset() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        rememberBeforeEdit(size)
        AirPlayPersistence.clearSafeAreaRect(context, size.width, size.height)
        updateSummary()
        onSafeAreaChanged()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }
}
