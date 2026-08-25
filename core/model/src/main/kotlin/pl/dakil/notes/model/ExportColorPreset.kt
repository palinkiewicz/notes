package pl.dakil.notes.model

import java.util.Locale

/**
 * A colour transform offered when exporting a sheet to PDF or PNG.
 *
 * [apply] is the whole rule for every colour except one: [INTELLIGENT] also forces the page
 * background to pure white, but that special case does not apply uniformly to every colour on the
 * sheet — a caller applies it only to the background field, never inside [apply] itself.
 */
enum class ExportColorPreset {
    /** No transform. */
    AS_IS,

    /** Full RGB inversion — see [ColorCodec.invertRgb]. */
    INVERSION,

    /**
     * Every colour keeps its hue and saturation but has its lightness inverted — see
     * [ColorCodec.invertLightness] — while the caller forces the background to pure white.
     */
    INTELLIGENT;

    fun apply(argb: Int): Int = when (this) {
        AS_IS -> argb
        INVERSION -> ColorCodec.invertRgb(argb)
        INTELLIGENT -> ColorCodec.invertLightness(argb)
    }

    companion object {
        fun fromKey(key: String): ExportColorPreset =
            entries.firstOrNull { it.key == key } ?: AS_IS
    }

    /**
     * Stable lower-case token, decoupled from the enum name.
     *
     * `Locale.ROOT`, not the device's: Turkish lower-cases `I` to a dotless `ı`, which would write
     * a key no other device could read back.
     */
    val key: String get() = name.lowercase(Locale.ROOT)
}
