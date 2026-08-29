package pl.dakil.notes.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import pl.dakil.notes.model.AppColorTheme
import pl.dakil.notes.model.DarkThemeOption
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PatternType
import pl.dakil.notes.model.PressureCurve
import pl.dakil.notes.model.ViewMode
import java.util.Locale

/** Where the toolbar sits, so left-handed users and tablet users can put it somewhere sensible. */
enum class ToolbarPosition { BOTTOM, LEFT, RIGHT, TOP }

/**
 * How the note library draws its contents.
 *
 * Keyed by a stable string rather than by ordinal, like [pl.dakil.notes.model.ViewMode]:
 * reordering the enum then cannot silently turn everyone's saved choice into a different one.
 */
/**
 * How often a background job runs.
 *
 * Keyed by an explicit string, never by ordinal or `name`: reordering the constants would otherwise
 * silently change everyone's saved choice, which is what `LocaleSafeKeysTest` exists to prevent.
 */
enum class SyncFrequency(val key: String, val intervalMs: Long) {
    MANUAL("manual", 0L),
    // The platform floors a periodic job at fifteen minutes, so anything shorter is a lie.
    QUARTER_HOUR("quarterHour", 15L * 60 * 1000),
    HOURLY("hourly", 60L * 60 * 1000),
    SIX_HOURLY("sixHourly", 6L * 60 * 60 * 1000),
    DAILY("daily", 24L * 60 * 60 * 1000),
    WEEKLY("weekly", 7L * 24 * 60 * 60 * 1000);

    companion object {
        fun fromKey(key: String?, fallback: SyncFrequency = WEEKLY): SyncFrequency =
            entries.firstOrNull { it.key == key } ?: fallback
    }
}

/** Which remote the library mirrors to, if any. */
enum class SyncProvider(val key: String) {
    NONE("none"),
    DRIVE("drive"),
    WEBDAV("webdav");

    companion object {
        fun fromKey(key: String?): SyncProvider = entries.firstOrNull { it.key == key } ?: NONE
    }
}

enum class LibraryLayout(val key: String) {
    /** A card each, with a preview of what is in the note. */
    CARDS("cards"),

    /** One row each, the denser view for a long library. */
    LIST("list");

    companion object {
        fun fromKey(key: String): LibraryLayout = entries.firstOrNull { it.key == key } ?: CARDS
    }
}

/** Everything the user can configure. Defaults are what a casual note-taker should never touch. */
data class AppSettings(
    val input: InputConfig = InputConfig(),
    /**
     * Whether the editor offers the finger-drawing button at all.
     *
     * The setting is about the device, not the moment: someone with a stylus never wants the
     * button, and someone without one wants it on the bar. Whether the finger is drawing *now* is
     * the button's own state, held in the editor — see [InputConfig.fingerDrawingEnabled].
     */
    val fingerDrawingAvailable: Boolean = false,
    /** The colour scheme the app paints itself in. */
    val colorTheme: AppColorTheme = AppColorTheme.DAKILS_NOTES,
    /** Whether the app follows the system dark setting or overrides it. */
    val darkTheme: DarkThemeOption = DarkThemeOption.SYSTEM,
    /** True black backgrounds in dark mode, for OLED screens. Ignored in light mode. */
    val pureBlack: Boolean = false,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM,
    val defaultPageSize: PageSize = PageSize.A4,
    val defaultBackground: PageBackground = PageBackground.DEFAULT,
    val libraryRoot: String? = null,

    // ---- Backup and sync. Secrets are not here: they live in `TokenStore`, sealed by the keystore.
    val backupEnabled: Boolean = false,
    /** A SAF tree URI. Any `DocumentsProvider` works, which is most of the point. */
    val backupDestination: String? = null,
    val backupFrequency: SyncFrequency = SyncFrequency.WEEKLY,
    /** How many archives to keep. Zero means keep every one. */
    val backupKeep: Int = 5,
    val backupOnCharging: Boolean = true,
    val backupWifiOnly: Boolean = true,
    val syncProvider: SyncProvider = SyncProvider.NONE,
    val syncFrequency: SyncFrequency = SyncFrequency.MANUAL,
    val syncWifiOnly: Boolean = true,
    val syncTwoWay: Boolean = true,
    val webDavUrl: String = "",
    val webDavUser: String = "",
    /** Non-empty once a Drive client id has been entered; the secret is sealed away. */
    val driveClientId: String = "",
    val driveFolder: String = "DakNote",
    val lastSyncAt: Long = 0L,
    val lastBackupAt: Long = 0L,
    /** Which view new notes open in. */
    val defaultView: ViewMode = ViewMode.PAGED,
    /** How the note library draws its contents. */
    val libraryLayout: LibraryLayout = LibraryLayout.CARDS,
    /**
     * Whether a `.md` note obeys the sizes and colours a Pandoc bracketed span can name.
     *
     * Off by default, and deliberately a choice rather than a given. `[big]{size=24}` is a real
     * Pandoc convention, but it is not something every Markdown tool renders — a note written with
     * it opens elsewhere with the braces on show. Someone who keeps their notes to themselves, or
     * whose other tools understand Pandoc, loses nothing by turning it on; someone syncing to an
     * editor that does not is better off never being offered the buttons. A sheet is unaffected:
     * it is paper, not a Markdown file, and always obeys them.
     */
    val pandocTextNotes: Boolean = false,
    /** The unit every paper measurement is shown and typed in. */
    val measurementUnit: MeasurementUnit = localeDefaultUnit(),
    /** Most-recently-used custom colours, newest first. Shared by every picker in the app. */
    val recentColors: List<Int> = emptyList(),
    /** Zoom levels the user pinned, as whole percentages, ascending. 100% is always offered. */
    val zoomPresets: List<Int> = emptyList(),
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
            palmRejectionWindowMs = prefs.getLong(KEY_PALM_WINDOW, 100L),
            palmTouchMajorThreshold = prefs.getFloat(KEY_PALM_SIZE, 160f),
            smoothingScale = prefs.getFloat(KEY_SMOOTHING_SCALE, 1f),
            pressureCurve = PressureCurve(
                x1 = prefs.getFloat(KEY_CURVE_X1, PressureCurve.LINEAR.x1),
                y1 = prefs.getFloat(KEY_CURVE_Y1, PressureCurve.LINEAR.y1),
                x2 = prefs.getFloat(KEY_CURVE_X2, PressureCurve.LINEAR.x2),
                y2 = prefs.getFloat(KEY_CURVE_Y2, PressureCurve.LINEAR.y2),
            ),
            minPressure = prefs.getFloat(KEY_MIN_PRESSURE, 0f),
            autoShapeEnabled = prefs.getBoolean(KEY_AUTO_SHAPE, true),
            autoShapeHoldMs = prefs.getLong(KEY_AUTO_SHAPE_HOLD, 500L),
        ),
        fingerDrawingAvailable = prefs.getBoolean(KEY_FINGER_DRAWING, false),
        colorTheme = prefs.getString(KEY_COLOR_THEME, null)
            ?.let(AppColorTheme::fromKey) ?: AppColorTheme.DAKILS_NOTES,
        darkTheme = prefs.getString(KEY_DARK_THEME, null)
            ?.let(DarkThemeOption::fromKey) ?: DarkThemeOption.SYSTEM,
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
        toolbarPosition = ToolbarPosition.entries
            .getOrElse(prefs.getInt(KEY_TOOLBAR_POSITION, 0)) { ToolbarPosition.BOTTOM },
        defaultPageSize = readPageSize(),
        defaultBackground = readBackground(),
        libraryRoot = prefs.getString(KEY_LIBRARY_ROOT, null),
        backupEnabled = prefs.getBoolean(KEY_BACKUP_ENABLED, false),
        backupDestination = prefs.getString(KEY_BACKUP_DESTINATION, null),
        backupFrequency = SyncFrequency.fromKey(prefs.getString(KEY_BACKUP_FREQUENCY, null)),
        backupKeep = prefs.getInt(KEY_BACKUP_KEEP, 5),
        backupOnCharging = prefs.getBoolean(KEY_BACKUP_CHARGING, true),
        backupWifiOnly = prefs.getBoolean(KEY_BACKUP_WIFI, true),
        syncProvider = SyncProvider.fromKey(prefs.getString(KEY_SYNC_PROVIDER, null)),
        syncFrequency = SyncFrequency.fromKey(prefs.getString(KEY_SYNC_FREQUENCY, null), SyncFrequency.MANUAL),
        syncWifiOnly = prefs.getBoolean(KEY_SYNC_WIFI, true),
        syncTwoWay = prefs.getBoolean(KEY_SYNC_TWO_WAY, true),
        webDavUrl = prefs.getString(KEY_WEBDAV_URL, "").orEmpty(),
        webDavUser = prefs.getString(KEY_WEBDAV_USER, "").orEmpty(),
        driveClientId = prefs.getString(KEY_DRIVE_CLIENT_ID, "").orEmpty(),
        driveFolder = prefs.getString(KEY_DRIVE_FOLDER, "DakNote").orEmpty().ifEmpty { "DakNote" },
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
        lastBackupAt = prefs.getLong(KEY_LAST_BACKUP, 0L),
        recentColors = readRecentColors(),
        zoomPresets = readZoomPresets(),
        defaultView = ViewMode.fromKey(prefs.getString(KEY_DEFAULT_VIEW, "paged") ?: "paged"),
        libraryLayout = LibraryLayout.fromKey(
            prefs.getString(KEY_LIBRARY_LAYOUT, LibraryLayout.CARDS.key) ?: LibraryLayout.CARDS.key
        ),
        pandocTextNotes = prefs.getBoolean(KEY_PANDOC_TEXT_NOTES, false),
        measurementUnit = prefs.getString(KEY_UNIT, null)
            ?.let(MeasurementUnit::fromKey) ?: localeDefaultUnit(),
    )

    // ---- Individual setters. Kept granular so a settings screen can write one field at a time. ---

    fun setFingerDrawing(enabled: Boolean) = prefs.edit().putBoolean(KEY_FINGER_DRAWING, enabled).apply()

    fun setPalmRejectionWindowMs(value: Long) = prefs.edit().putLong(KEY_PALM_WINDOW, value).apply()

    fun setPalmTouchMajorThreshold(value: Float) = prefs.edit().putFloat(KEY_PALM_SIZE, value).apply()

    fun setSmoothingScale(value: Float) = prefs.edit().putFloat(KEY_SMOOTHING_SCALE, value).apply()

    fun setMinPressure(value: Float) = prefs.edit().putFloat(KEY_MIN_PRESSURE, value).apply()

    fun setAutoShape(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_SHAPE, enabled).apply()

    fun setAutoShapeHoldMs(value: Long) = prefs.edit().putLong(KEY_AUTO_SHAPE_HOLD, value).apply()

    fun setPressureCurve(curve: PressureCurve) = prefs.edit()
        .putFloat(KEY_CURVE_X1, curve.x1)
        .putFloat(KEY_CURVE_Y1, curve.y1)
        .putFloat(KEY_CURVE_X2, curve.x2)
        .putFloat(KEY_CURVE_Y2, curve.y2)
        .apply()

    fun setColorTheme(theme: AppColorTheme) =
        prefs.edit().putString(KEY_COLOR_THEME, theme.key).apply()

    fun setDarkTheme(option: DarkThemeOption) =
        prefs.edit().putString(KEY_DARK_THEME, option.key).apply()

    fun setPureBlack(enabled: Boolean) = prefs.edit().putBoolean(KEY_PURE_BLACK, enabled).apply()

    fun setToolbarPosition(position: ToolbarPosition) =
        prefs.edit().putInt(KEY_TOOLBAR_POSITION, position.ordinal).apply()

    fun setLibraryRoot(ref: String?) = prefs.edit().putString(KEY_LIBRARY_ROOT, ref).apply()

    fun setBackupEnabled(on: Boolean) = prefs.edit().putBoolean(KEY_BACKUP_ENABLED, on).apply()
    fun setBackupDestination(uri: String?) = prefs.edit().putString(KEY_BACKUP_DESTINATION, uri).apply()
    fun setBackupFrequency(v: SyncFrequency) = prefs.edit().putString(KEY_BACKUP_FREQUENCY, v.key).apply()
    fun setBackupKeep(n: Int) = prefs.edit().putInt(KEY_BACKUP_KEEP, n).apply()
    fun setBackupOnCharging(on: Boolean) = prefs.edit().putBoolean(KEY_BACKUP_CHARGING, on).apply()
    fun setBackupWifiOnly(on: Boolean) = prefs.edit().putBoolean(KEY_BACKUP_WIFI, on).apply()
    fun setSyncProvider(v: SyncProvider) = prefs.edit().putString(KEY_SYNC_PROVIDER, v.key).apply()
    fun setSyncFrequency(v: SyncFrequency) = prefs.edit().putString(KEY_SYNC_FREQUENCY, v.key).apply()
    fun setSyncWifiOnly(on: Boolean) = prefs.edit().putBoolean(KEY_SYNC_WIFI, on).apply()
    fun setSyncTwoWay(on: Boolean) = prefs.edit().putBoolean(KEY_SYNC_TWO_WAY, on).apply()
    fun setWebDav(url: String, user: String) =
        prefs.edit().putString(KEY_WEBDAV_URL, url).putString(KEY_WEBDAV_USER, user).apply()
    fun setDriveClientId(id: String) = prefs.edit().putString(KEY_DRIVE_CLIENT_ID, id).apply()
    fun setDriveFolder(name: String) = prefs.edit().putString(KEY_DRIVE_FOLDER, name).apply()
    fun setLastSyncAt(at: Long) = prefs.edit().putLong(KEY_LAST_SYNC, at).apply()
    fun setLastBackupAt(at: Long) = prefs.edit().putLong(KEY_LAST_BACKUP, at).apply()

    /**
     * A short, stable name for this device, minted on first use.
     *
     * Five characters from an unambiguous alphabet — no `I`, `O`, `0` or `1` — because it ends up
     * in file names a person has to read out and type: `Standup.sync-conflict-20260828-141233-K7QF2`.
     * It identifies the *installation*, not the user, and is never sent anywhere; a conflict copy
     * simply has to say which device made it.
     */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        val minted = buildString { repeat(5) { append(alphabet[random.nextInt(alphabet.length)]) } }
        prefs.edit().putString(KEY_DEVICE_ID, minted).apply()
        return minted
    }

    fun setLibraryLayout(layout: LibraryLayout) =
        prefs.edit().putString(KEY_LIBRARY_LAYOUT, layout.key).apply()

    fun setPandocTextNotes(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_PANDOC_TEXT_NOTES, enabled).apply()

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

    fun setMeasurementUnit(unit: MeasurementUnit) =
        prefs.edit().putString(KEY_UNIT, unit.key).apply()

    /**
     * Pins a zoom level so it can be picked again by name.
     *
     * Kept ascending rather than newest-first, unlike [addRecentColor]: this is a menu the user
     * reads down, and a scale is ordered by nature — a list that reshuffled itself on every use
     * would mean hunting for the entry that was second from the top a moment ago.
     */
    fun addZoomPreset(percent: Int) {
        val updated = (readZoomPresets() + percent).distinct().sorted().take(MAX_ZOOM_PRESETS)
        prefs.edit().putString(KEY_ZOOM_PRESETS, updated.joinToString(",")).apply()
    }

    fun removeZoomPreset(percent: Int) {
        val updated = readZoomPresets().filterNot { it == percent }
        prefs.edit().putString(KEY_ZOOM_PRESETS, updated.joinToString(",")).apply()
    }

    private fun readZoomPresets(): List<Int> =
        prefs.getString(KEY_ZOOM_PRESETS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.distinct()
            ?.sorted()
            ?.take(MAX_ZOOM_PRESETS)
            ?: emptyList()

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
            pattern = PagePattern(
                type = PatternType.fromKey(prefs.getString(KEY_PATTERN_TYPE, "none") ?: "none"),
                spacing = prefs.getFloat(KEY_PATTERN_SPACING, 24f),
                color = prefs.getInt(KEY_PATTERN_COLOR, PagePattern.NONE.color),
                margin = prefs.getFloat(KEY_PATTERN_MARGIN, 0f),
            ),
        )
    }

    private companion object {
        const val KEY_FINGER_DRAWING = "input.fingerDrawing"
        const val KEY_PALM_WINDOW = "input.palmWindowMs"
        const val KEY_PALM_SIZE = "input.palmTouchMajor"
        const val KEY_SMOOTHING_SCALE = "input.smoothingScale"
        const val KEY_MIN_PRESSURE = "input.minPressure"
        const val KEY_CURVE_X1 = "input.curveX1"
        const val KEY_CURVE_Y1 = "input.curveY1"
        const val KEY_CURVE_X2 = "input.curveX2"
        const val KEY_CURVE_Y2 = "input.curveY2"
        const val KEY_AUTO_SHAPE = "input.autoShape"
        const val KEY_AUTO_SHAPE_HOLD = "input.autoShapeHoldMs"

        const val KEY_COLOR_THEME = "ui.colorTheme"
        const val KEY_DARK_THEME = "ui.darkTheme"
        const val KEY_PURE_BLACK = "ui.pureBlack"
        const val KEY_TOOLBAR_POSITION = "ui.toolbarPosition"
        const val KEY_LIBRARY_ROOT = "library.root"
        const val KEY_DEVICE_ID = "library.deviceId"
        const val KEY_BACKUP_ENABLED = "backup.enabled"
        const val KEY_BACKUP_DESTINATION = "backup.destination"
        const val KEY_BACKUP_FREQUENCY = "backup.frequency"
        const val KEY_BACKUP_KEEP = "backup.keep"
        const val KEY_BACKUP_CHARGING = "backup.charging"
        const val KEY_BACKUP_WIFI = "backup.wifiOnly"
        const val KEY_SYNC_PROVIDER = "sync.provider"
        const val KEY_SYNC_FREQUENCY = "sync.frequency"
        const val KEY_SYNC_WIFI = "sync.wifiOnly"
        const val KEY_SYNC_TWO_WAY = "sync.twoWay"
        const val KEY_WEBDAV_URL = "sync.webdav.url"
        const val KEY_WEBDAV_USER = "sync.webdav.user"
        const val KEY_DRIVE_CLIENT_ID = "sync.drive.clientId"
        const val KEY_DRIVE_FOLDER = "sync.drive.folder"
        const val KEY_LAST_SYNC = "sync.lastAt"
        const val KEY_LAST_BACKUP = "backup.lastAt"
        const val KEY_LIBRARY_LAYOUT = "ui.libraryLayout"
        const val KEY_PANDOC_TEXT_NOTES = "ui.pandocTextNotes"
        const val KEY_RECENT_COLORS = "ui.recentColors"
        const val KEY_DEFAULT_VIEW = "ui.defaultView"
        const val KEY_UNIT = "ui.measurementUnit"
        const val KEY_ZOOM_PRESETS = "ui.zoomPresets"

        /** Two rows on a phone; beyond that the row becomes a scroll people do not scroll. */
        const val MAX_RECENT_COLORS = 12

        /** The menu carries three fixed entries already; past this it stops fitting the screen. */
        const val MAX_ZOOM_PRESETS = 8

        const val KEY_PAGE_KIND = "page.kind"
        const val KEY_PAGE_W = "page.width"
        const val KEY_PAGE_H = "page.height"
        const val KEY_BG_LIGHT = "page.bgLight"
        const val KEY_BG_DARK = "page.bgDark"
        const val KEY_PATTERN_TYPE = "page.patternType"
        const val KEY_PATTERN_SPACING = "page.patternSpacing"
        const val KEY_PATTERN_COLOR = "page.patternColor"
        const val KEY_PATTERN_MARGIN = "page.patternMargin"
    }
}

/**
 * The unit to start out in, taken from the device locale.
 *
 * `Locale.getDefault()` costs nothing and needs no permission, so a user in a country that measures
 * paper in inches should not have to go and find a setting first. It is only the *default*: the
 * moment the setting is written it wins, so a later locale change never moves a chosen unit.
 */
internal fun localeDefaultUnit(): MeasurementUnit =
    if (Locale.getDefault().country in IMPERIAL_COUNTRIES) MeasurementUnit.INCH
    else MeasurementUnit.CENTIMETRE

private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
