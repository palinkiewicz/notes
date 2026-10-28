package pl.dakil.notes.editor.export

import android.graphics.Bitmap
import pl.dakil.notes.model.ExportFormat
import pl.dakil.notes.model.ExportOptions
import java.io.File
import java.io.FileOutputStream

/** `export_3.png` or `export_3.jpg`: the page number is what the saver carries into the real name. */
internal fun ExportOptions.rasterFile(dir: File, page: Int): File =
    File(dir, "export_${page + 1}.${if (format == ExportFormat.JPEG) "jpg" else "png"}")

/**
 * Writes one rendered page in the chosen format. A JPEG has no alpha, so the page must already be
 * painted opaque; every caller paints its paper first unless the PNG was asked to be transparent.
 */
internal fun writeRaster(bitmap: Bitmap, file: File, options: ExportOptions) {
    FileOutputStream(file).use {
        if (options.format == ExportFormat.JPEG) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality.value, it)
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
