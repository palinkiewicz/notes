package pl.dakil.notes.model

/**
 * The colour scheme the app paints itself in.
 *
 * These live in the model rather than beside the theme itself because two modules that cannot see
 * each other both need the type: `:core:data` persists the choice and `:core:ui` resolves it to a
 * `ColorScheme`, and `:core:ui` is not allowed to depend on `:core:data`.
 *
 * Keyed by a stable string rather than by ordinal, like [MeasurementUnit] and `LibraryLayout`:
 * reordering the enum then cannot silently turn everyone's saved choice into a different one.
 */
enum class AppColorTheme(val key: String) {
    /** The default: warm paper and gold, the app's own identity. */
    DAKILS_NOTES("dakils_notes"),

    /** The wallpaper palette, where the platform offers one. Android 12 and up. */
    DYNAMIC("dynamic"),
    OCEAN("ocean"),
    LAVENDER("lavender"),
    SUNSET("sunset"),
    ROSE("rose"),
    TEAL("teal");

    companion object {
        fun fromKey(key: String): AppColorTheme =
            entries.firstOrNull { it.key == key } ?: DAKILS_NOTES
    }
}

/**
 * Whether the app follows the system dark setting or overrides it.
 *
 * Separate from [AppColorTheme] because they are genuinely independent: every colour theme has a
 * light and a dark form, and someone who wants a dark app on a light system should not have to give
 * up their colour to get it.
 */
enum class DarkThemeOption(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String): DarkThemeOption =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
