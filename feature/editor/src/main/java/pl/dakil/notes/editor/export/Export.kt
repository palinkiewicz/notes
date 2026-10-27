package pl.dakil.notes.editor.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import pl.dakil.notes.editor.markdown.ImageSource
import pl.dakil.notes.model.ExportColorPreset
import pl.dakil.notes.model.Sheet
import java.io.File

/**
 * Renders an ink sheet in the chosen [format], applying [preset] to everything but typed text.
 * [pages] are 0-based; null means every page.
 */
fun exportInk(
    context: Context,
    sheet: Sheet,
    preset: ExportColorPreset,
    format: ExportFormat,
    pages: List<Int>?,
): List<File> {
    val selected = pages?.filter { it < sheet.pageCount() } ?: List(sheet.pageCount()) { it }
    return when (format) {
        ExportFormat.PDF -> listOf(InkPageExporter.exportToPdf(context, sheet, preset, selected))
        ExportFormat.PNG -> InkPageExporter.exportToPngs(context, sheet, preset, selected)
    }
}

/**
 * Renders a `.md` note's body in the chosen [format]. There is no colour *preset*: an ink sheet's
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
    format: ExportFormat,
    attributes: Boolean,
    pages: List<Int>?,
    images: ImageSource,
): List<File> =
    when (format) {
        ExportFormat.PDF -> listOf(TextPageExporter.exportToPdf(context, markdown, attributes, pages, images))
        ExportFormat.PNG -> TextPageExporter.exportToPngs(context, markdown, attributes, pages, images)
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

private fun mimeTypeOf(file: File): String =
    if (file.extension.equals("pdf", ignoreCase = true)) "application/pdf" else "image/png"
