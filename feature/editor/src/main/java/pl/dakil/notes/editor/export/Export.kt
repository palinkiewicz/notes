package pl.dakil.notes.editor.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import pl.dakil.notes.editor.markdown.ImageSource
import pl.dakil.notes.model.ExportFormat
import pl.dakil.notes.model.ExportOptions
import pl.dakil.notes.model.RenderOrder
import pl.dakil.notes.model.Sheet
import java.io.File
import java.util.Locale

/**
 * Renders an ink sheet as [options] say, applying their colour preset to everything but typed text.
 * [pages] are 0-based; null means every page. [order] is the user's drawing order, so a page prints
 * stacked the way the screen shows it.
 */
suspend fun exportInk(
    context: Context,
    sheet: Sheet,
    options: ExportOptions,
    pages: List<Int>?,
    order: RenderOrder,
): InkExport {
    val selected = pages?.filter { it < sheet.pageCount() } ?: List(sheet.pageCount()) { it }
    return when (options.format) {
        ExportFormat.PDF -> InkPageExporter.exportToPdf(context, sheet, options, selected, order)
            .let { InkExport(listOf(it.file), it.rasterisedPdfPages) }
        ExportFormat.PNG, ExportFormat.JPEG ->
            InkExport(InkPageExporter.exportToImages(context, sheet, options, selected, order), rasterisedPdfPages = false)
    }
}

/**
 * The files an ink export made, and whether a PDF export had to draw any of the note's PDF pages
 * as pictures rather than carry them across — which the user is told, since it is the one case
 * where a PDF note's export is not as sharp as the PDF it came from.
 */
class InkExport(val files: List<File>, val rasterisedPdfPages: Boolean)

/**
 * Renders a `.md` note's body as [options] say. Their colour *preset* is ignored: an ink sheet's
 * are for its paper and its strokes, and a `.md` note has neither.
 *
 * [attributes] is the user's Pandoc setting, passed in rather than read here: an export is a picture
 * of what the note looked like on screen, and a note whose sizes the editor was ignoring must not
 * come out of the printer with them applied. See `AppSettings.pandocTextNotes`.
 *
 * [images] is the note's own [ImageSource]: an `attachments/…` image is relative to the folder the
 * note is in, which only the open note knows.
 */
suspend fun exportText(
    context: Context,
    markdown: String,
    options: ExportOptions,
    attributes: Boolean,
    pages: List<Int>?,
    images: ImageSource,
): List<File> =
    when (options.format) {
        ExportFormat.PDF -> listOf(TextPageExporter.exportToPdf(context, markdown, options, attributes, pages, images))
        ExportFormat.PNG, ExportFormat.JPEG ->
            TextPageExporter.exportToImages(context, markdown, options, attributes, pages, images)
    }

/**
 * Hands the exported files to whatever app the user picks.
 *
 * Branches on how many files there are, not on the format: PDF is always one file, but so is a
 * single-page PNG export, and both want [Intent.ACTION_SEND] rather than the multi-file action.
 */
fun shareExport(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val authority = "${context.packageName}.fileprovider"
    val uris = files.map { FileProvider.getUriForFile(context, authority, it) }

    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(files.first())
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeTypeOf(files.first())
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

internal fun mimeTypeOf(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
    "pdf" -> "application/pdf"
    "jpg" -> "image/jpeg"
    else -> "image/png"
}
