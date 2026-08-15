package pl.dakil.notes.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType
import pl.dakil.notes.model.PressureCurve
import pl.dakil.notes.model.ViewMode

/** Where the toolbar sits, so left-handed users and tablet users can put it somewhere sensible. */
enum class ToolbarPosition { BOTTOM, LEFT, RIGHT, TOP }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Everything the user can configure. Defaults are what a casual note-taker should never touch. */
data class AppSettings(
    val input: InputConfig = InputConfig(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM,
    val defaultPageSize: PageSize = PageSize.A4,
    val defaultBackground: PageBackground = PageBackground.DEFAULT,
    /** Show the page grid/rule pattern behind text in Document mode too. */
    val patternInDocumentMode: Boolean = false,
    val libraryRoot: String? = null,
    /** Which view new notes open in. */
    val defaultView: ViewMode = ViewMode.PAGED,
    /** Most-recently-used custom colours, newest first. Shared by every picker in the app. */
    val recentColors: List<Int> = emptyList(),
)

/**
 * Persists [AppSettings].
 *
 * `SharedPreferences` rather than DataStore: DataStore pulls in protobuf-javalite for a few dozen
 * scalar values, which is real weight against the size target and buys nothing here. The `Flow`
 * ergonomics people actually want from DataStore come from [callbackFlow] over the change listener,
 * which is a dozen lines.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Emits the current settings immediately, then again on every change. */
    val settings: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { read() }

    fun read(): AppSettings = AppSettings(
        input = InputConfig(
            fingerDrawingEnabled = prefs.getBoolean(KEY_FINGER_DRAWING, false),
            palmRejectionWindowMs = prefs.getLong(KEY_PALM_WINDOW, 120L),
            palmTouchMajorThreshold = prefs.getFloat(KEY_PALM_SIZE, 90f),
            multiTouchNavigates = prefs.getBoolean(KEY_MULTITOUCH_NAV, true),
            smoothingScale = prefs.getFloat(KEY_SMOOTHING_SCALE, 1f),
            pressureCurve = PressureCurve(
                x1 = prefs.getFloat(KEY_CURVE_X1, PressureCurve.LINEAR.x1),
                y1 = prefs.getFloat(KEY_CURVE_Y1, PressureCurve.LINEAR.y1),
                x2 = prefs.getFloat(KEY_CURVE_X2, PressureCurve.LINEAR.x2),
                y2 = prefs.getFloat(KEY_CURVE_Y2, PressureCurve.LINEAR.y2),
            ),
            minPressure = prefs.getFloat(KEY_MIN_PRESSURE, 0f),
        ),
        theme = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM },
        toolbarPosition = ToolbarPosition.entries
            .getOrElse(prefs.getInt(KEY_TOOLBAR_POSITION, 0)) { ToolbarPosition.BOTTOM },
        defaultPageSize = readPageSize(),
        defaultBackground = readBackground(),
        patternInDocumentMode = prefs.getBoolean(KEY_PATTERN_IN_DOC, false),
        libraryRoot = prefs.getString(KEY_LIBRARY_ROOT, null),
        recentColors = readRecentColors(),
        defaultView = ViewMode.fromKey(prefs.getString(KEY_DEFAULT_VIEW, "paged") ?: "paged"),
    )

    // ---- Individual setters. Kept granular so a settings screen can write one field at a time. ---

    fun setFingerDrawing(enabled: Boolean) = prefs.edit().putBoolean(KEY_FINGER_DRAWING, enabled).apply()

    fun setPalmRejectionWindowMs(value: Long) = prefs.edit().putLong(KEY_PALM_WINDOW, value).apply()

    fun setPalmTouchMajorThreshold(value: Float) = prefs.edit().putFloat(KEY_PALM_SIZE, value).apply()

    fun setMultiTouchNavigates(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_MULTITOUCH_NAV, enabled).apply()

    fun setSmoothingScale(value: Float) = prefs.edit().putFloat(KEY_SMOOTHING_SCALE, value).apply()

    fun setMinPressure(value: Float) = prefs.edit().putFloat(KEY_MIN_PRESSURE, value).apply()

    fun setPressureCurve(curve: PressureCurve) = prefs.edit()
        .putFloat(KEY_CURVE_X1, curve.x1)
        .putFloat(KEY_CURVE_Y1, curve.y1)
        .putFloat(KEY_CURVE_X2, curve.x2)
        .putFloat(KEY_CURVE_Y2, curve.y2)
        .apply()

    fun setTheme(mode: ThemeMode) = prefs.edit().putInt(KEY_THEME, mode.ordinal).apply()

    fun setToolbarPosition(position: ToolbarPosition) =
        prefs.edit().putInt(KEY_TOOLBAR_POSITION, position.ordinal).apply()

    fun setPatternInDocumentMode(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_PATTERN_IN_DOC, enabled).apply()

    fun setLibraryRoot(ref: String?) = prefs.edit().putString(KEY_LIBRARY_ROOT, ref).apply()

    /**
     * Records a colour the user chose, newest first, de-duplicated.
     *
     * Kept globally rather than per-tool: someone who mixes a particular green wants it for the pen,
     * the rule lines and the paper without mixing it three times.
     */
    fun addRecentColor(argb: Int) {
        val existing = readRecentColors()
        if (existing.firstOrNull() == argb) return
        val updated = (listOf(argb) + existing.filterNot { it == argb }).take(MAX_RECENT_COLORS)
        prefs.edit().putString(KEY_RECENT_COLORS, updated.joinToString(",")).apply()
    }

    fun setDefaultView(view: ViewMode) = prefs.edit().putString(KEY_DEFAULT_VIEW, view.key).apply()

    fun clearRecentColors() = prefs.edit().remove(KEY_RECENT_COLORS).apply()

    private fun readRecentColors(): List<Int> =
        prefs.getString(KEY_RECENT_COLORS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.take(MAX_RECENT_COLORS)
            ?: emptyList()

    fun setDefaultPageSize(size: PageSize) {
        val editor = prefs.edit()
        when (size) {
            is PageSize.Fixed -> editor.putString(KEY_PAGE_KIND, size.kind.name)
            is PageSize.Custom -> editor
                .putString(KEY_PAGE_KIND, "custom")
                .putFloat(KEY_PAGE_W, size.width)
                .putFloat(KEY_PAGE_H, size.height)
        }
        editor.apply()
    }

    fun setDefaultPattern(pattern: PagePattern) = prefs.edit()
        .putString(KEY_PATTERN_TYPE, pattern.type.key)
        .putFloat(KEY_PATTERN_SPACING, pattern.spacing)
        .putInt(KEY_PATTERN_COLOR, pattern.color)
        .putFloat(KEY_PATTERN_OPACITY, pattern.opacity)
        .putFloat(KEY_PATTERN_MARGIN, pattern.margin)
        .apply()

    fun setDefaultBackgroundColors(light: Int, dark: Int) = prefs.edit()
        .putInt(KEY_BG_LIGHT, light)
        .putInt(KEY_BG_DARK, dark)
        .apply()

    private fun readPageSize(): PageSize = when (val kind = prefs.getString(KEY_PAGE_KIND, "A4")) {
        "custom" -> PageSize.Custom(prefs.getFloat(KEY_PAGE_W, 595f), prefs.getFloat(KEY_PAGE_H, 842f))
        else -> PageSize.Fixed(
            PageSize.Kind.entries.firstOrNull { it.name == kind } ?: PageSize.Kind.A4
        )
    }

    private fun readBackground(): PageBackground {
        val defaults = PageBackground.DEFAULT
        return PageBackground(
            color = prefs.getInt(KEY_BG_LIGHT, defaults.color),
            darkColor = prefs.getInt(KEY_BG_DARK, defaults.darkColor),
            pattern = PagePattern(
                type = PatternType.fromKey(prefs.getString(KEY_PATTERN_TYPE, "none") ?: "none"),
                spacing = prefs.getFloat(KEY_PATTERN_SPACING, 24f),
                color = prefs.getInt(KEY_PATTERN_COLOR, PagePattern.NONE.color),
                opacity = prefs.getFloat(KEY_PATTERN_OPACITY, 0.35f),
                margin = prefs.getFloat(KEY_PATTERN_MARGIN, 0f),
            ),
        )
    }

    private companion object {
        const val KEY_FINGER_DRAWING = "input.fingerDrawing"
        const val KEY_PALM_WINDOW = "input.palmWindowMs"
        const val KEY_PALM_SIZE = "input.palmTouchMajor"
        const val KEY_MULTITOUCH_NAV = "input.multiTouchNavigates"
        const val KEY_SMOOTHING_SCALE = "input.smoothingScale"
        const val KEY_MIN_PRESSURE = "input.minPressure"
        const val KEY_CURVE_X1 = "input.curveX1"
        const val KEY_CURVE_Y1 = "input.curveY1"
        const val KEY_CURVE_X2 = "input.curveX2"
        const val KEY_CURVE_Y2 = "input.curveY2"

        const val KEY_THEME = "ui.theme"
        const val KEY_TOOLBAR_POSITION = "ui.toolbarPosition"
        const val KEY_PATTERN_IN_DOC = "ui.patternInDocumentMode"
        const val KEY_LIBRARY_ROOT = "library.root"
        const val KEY_RECENT_COLORS = "ui.recentColors"
        const val KEY_DEFAULT_VIEW = "ui.defaultView"

        /** Two rows on a phone; beyond that the row becomes a scroll people do not scroll. */
        const val MAX_RECENT_COLORS = 12

        const val KEY_PAGE_KIND = "page.kind"
        const val KEY_PAGE_W = "page.width"
        const val KEY_PAGE_H = "page.height"
        const val KEY_BG_LIGHT = "page.bgLight"
        const val KEY_BG_DARK = "page.bgDark"
        const val KEY_PATTERN_TYPE = "page.patternType"
        const val KEY_PATTERN_SPACING = "page.patternSpacing"
        const val KEY_PATTERN_COLOR = "page.patternColor"
        const val KEY_PATTERN_OPACITY = "page.patternOpacity"
        const val KEY_PATTERN_MARGIN = "page.patternMargin"
    }
}
