package pl.dakil.notes.model

import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonWriter
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Which container an export lands in.
 *
 * Keyed by an explicit string, never by ordinal or `name`: it is remembered between exports, and
 * reordering the constants must not silently turn everyone's choice into a different one.
 */
enum class ExportFormat(val key: String) {
    PDF("pdf"),
    PNG("png"),
    JPEG("jpeg");

    /** Whether the export is one picture per page, sized by [RasterScale], rather than a document. */
    val isRaster: Boolean get() = this != PDF

    companion object {
        fun fromKey(key: String?): ExportFormat? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How large a PNG or JPEG page comes out. [dpi] is against the page's own size in points, so a
 * letter page and an A4 page at the same choice print at the same sharpness; [CUSTOM] names a width
 * in pixels instead, see [ExportOptions.customWidthPx].
 */
enum class RasterSize(val key: String, val dpi: Int?) {
    SMALL("small", 96),
    MEDIUM("medium", 150),
    LARGE("large", 300),
    EXTRA_LARGE("extraLarge", 600),
    CUSTOM("custom", null);

    companion object {
        fun fromKey(key: String?): RasterSize? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How far a PDF's pictures are shrunk. A PDF stores a picture's pixels losslessly, so its size is
 * nearly all pixel count, and this is the one lever on it.
 */
enum class PdfImageQuality(val key: String, val dpi: Int?) {
    ORIGINAL("original", null),
    HIGH("high", 300),
    MEDIUM("medium", 150),
    LOW("low", 96);

    /**
     * The width to decode a picture printed [printWidthPt] wide at, or 0 for its full size — which
     * is what the picture decoder already reads 0 as. Never more than the picture has: a decoder
     * asked for more pixels than there are hands back what there is.
     */
    fun decodeWidthPx(printWidthPt: Float): Int {
        val dpi = dpi ?: return 0
        return ceil(printWidthPt * dpi / POINTS_PER_INCH).toInt().coerceAtLeast(1)
    }

    companion object {
        fun fromKey(key: String?): PdfImageQuality? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The JPEG encoder's quality, offered as three steps rather than a number nobody knows the scale of.
 * Declared smallest file first, the order the sheet offers them in, like [RasterSize].
 */
enum class JpegQuality(val key: String, val value: Int) {
    LOW("low", 70),
    MEDIUM("medium", 85),
    HIGH("high", 95);

    companion object {
        fun fromKey(key: String?): JpegQuality? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Every choice the export sheet offers apart from the pages, as last left.
 *
 * Kept between exports — the page selection is not, since it belongs to one note — and shared by
 * both kinds of note: [preset] is simply not offered for a `.md` note, which has no colours of its
 * own for it to act on.
 */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val preset: ExportColorPreset = ExportColorPreset.AS_IS,
    val rasterSize: RasterSize = RasterSize.MEDIUM,
    val customWidthPx: Int = DEFAULT_CUSTOM_WIDTH_PX,
    val pdfImages: PdfImageQuality = PdfImageQuality.MEDIUM,
    val jpegQuality: JpegQuality = JpegQuality.MEDIUM,
    /** PNG only: leave the paper colour out. A JPEG has no alpha channel to leave it out into. */
    val transparent: Boolean = false,
) {
    /** Whether the page is left unpainted, which only a PNG can carry. */
    val transparentBackground: Boolean get() = transparent && format == ExportFormat.PNG

    fun encode(): String = JsonWriter.write(
        JsonObject.EMPTY
            .with(FORMAT, format.key)
            .with(PRESET, preset.key)
            .with(SIZE, rasterSize.key)
            .with(CUSTOM_WIDTH, customWidthPx)
            .with(PDF_IMAGES, pdfImages.key)
            .with(JPEG_QUALITY, jpegQuality.key)
            .with(TRANSPARENT, transparent),
        pretty = false,
    )

    companion object {
        const val MIN_WIDTH_PX = 100
        const val MAX_WIDTH_PX = 10_000

        /** An A4 page at 300 dpi: a number someone typing a width is likely to want near. */
        const val DEFAULT_CUSTOM_WIDTH_PX = 2480

        /**
         * Reads what [encode] wrote, starting from the defaults and overlaying whatever is valid.
         *
         * Never throws, for the reason `ToolState.decode` gives: a forgotten choice is a nuisance,
         * a corrupt preference that stopped the export sheet opening is a broken button.
         */
        fun decode(text: String?): ExportOptions {
            val defaults = ExportOptions()
            if (text.isNullOrBlank()) return defaults
            val root = runCatching { JsonReader.parseObject(text) }.getOrNull() ?: return defaults
            return ExportOptions(
                format = ExportFormat.fromKey(root.string(FORMAT)) ?: defaults.format,
                preset = ExportColorPreset.entries.firstOrNull { it.key == root.string(PRESET) } ?: defaults.preset,
                rasterSize = RasterSize.fromKey(root.string(SIZE)) ?: defaults.rasterSize,
                customWidthPx = root.int(CUSTOM_WIDTH, defaults.customWidthPx).coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
                pdfImages = PdfImageQuality.fromKey(root.string(PDF_IMAGES)) ?: defaults.pdfImages,
                jpegQuality = JpegQuality.fromKey(root.string(JPEG_QUALITY)) ?: defaults.jpegQuality,
                transparent = root.bool(TRANSPARENT, defaults.transparent),
            )
        }

        private const val FORMAT = "format"
        private const val PRESET = "preset"
        private const val SIZE = "size"
        private const val CUSTOM_WIDTH = "customWidth"
        private const val PDF_IMAGES = "pdfImages"
        private const val JPEG_QUALITY = "jpegQuality"
        private const val TRANSPARENT = "transparent"
    }
}

/** One rasterised page: its scale from points, and the bitmap it fills. */
data class Raster(val ptToPx: Float, val widthPx: Int, val heightPx: Int, val capped: Boolean)

/** How large a PNG or JPEG page is, from the page's size in points and the user's choice. */
object RasterScale {

    /**
     * The most pixels one page may have. A4 at 600 dpi is just under it; a custom width on a tall
     * custom page is what it stops — an ARGB bitmap is four bytes a pixel, and the whole page is
     * held at once.
     */
    const val MAX_PIXELS = 36_000_000L

    /**
     * The scale is uniform, so the picture keeps the page's proportions: a custom width sets the
     * height. If that is more than [MAX_PIXELS], both sides shrink by the same factor and [Raster.capped]
     * says so, so the sheet can tell the user the size they asked for is not the one they will get.
     */
    fun of(widthPt: Float, heightPt: Float, options: ExportOptions): Raster {
        val w = widthPt.coerceAtLeast(1f)
        val h = heightPt.coerceAtLeast(1f)
        var ptToPx = options.rasterSize.dpi?.let { it / POINTS_PER_INCH } ?: (options.customWidthPx / w)
        var capped = false
        val pixels = (w * ptToPx).toDouble() * (h * ptToPx).toDouble()
        if (pixels > MAX_PIXELS) {
            ptToPx *= sqrt(MAX_PIXELS / pixels).toFloat()
            capped = true
        }
        return Raster(
            ptToPx = ptToPx,
            widthPx = (w * ptToPx).toInt().coerceAtLeast(1),
            heightPx = (h * ptToPx).toInt().coerceAtLeast(1),
            capped = capped,
        )
    }

    /** A typed custom width, or null for anything that is not a whole number in range. */
    fun parseWidth(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in ExportOptions.MIN_WIDTH_PX..ExportOptions.MAX_WIDTH_PX }
}

private const val POINTS_PER_INCH = 72f
