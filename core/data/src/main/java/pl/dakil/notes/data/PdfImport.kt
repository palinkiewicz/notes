package pl.dakil.notes.data

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import pl.dakil.notes.format.DakNote
import pl.dakil.notes.format.FileMediaSpool
import pl.dakil.notes.model.MediaData
import pl.dakil.notes.model.PdfSourcePage
import pl.dakil.notes.pdf.PdfFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * A PDF ready to go into a note: where it is kept, the name it will be stored under, and its pages.
 *
 * [src] is derived from the content, as a picture's is, so one PDF imported into a note twice — the
 * second time for a few more of its pages — is stored once.
 */
class ImportedPdf(
    val src: String,
    val data: MediaData,
    val pages: List<PdfSourcePage>,
    /** What the file was called, without its extension, when the provider said. */
    val title: String?,
)

/** Why a PDF could not be imported, for the screen to put into words. */
class PdfImportException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason { NOT_PDF, PROTECTED, UNREADABLE, TOO_LARGE }
}

/**
 * Reads a PDF the user picked or shared into the media spool, unchanged.
 *
 * Never re-encoded, never flattened: the bytes a note keeps are the bytes the user handed over,
 * which is the whole promise of annotating a PDF rather than a picture of one.
 */
object PdfImport {

    /**
     * Far past any document a note is made from; a note this large is already slow to save, sync
     * and back up, and older builds read a note whole into memory.
     */
    const val MAX_BYTES = 100L * 1024 * 1024

    /**
     * Reads [uri] through [resolver] into [spool]. Blocking: call it off the main thread, and soon —
     * a URI handed over by another app is only readable for as long as its grant lasts.
     */
    fun read(resolver: ContentResolver, uri: Uri, spool: FileMediaSpool, scratch: File): ImportedPdf {
        scratch.mkdirs()
        val temp = File.createTempFile("import-", ".pdf", scratch)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val head = ByteArray(HEADER_WINDOW)
            var headLen = 0
            val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
                ?: throw PdfImportException(PdfImportException.Reason.UNREADABLE)
            input.use { stream ->
                temp.outputStream().use { out ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw PdfImportException(PdfImportException.Reason.TOO_LARGE)
                        if (headLen < head.size) {
                            val take = minOf(n, head.size - headLen)
                            System.arraycopy(chunk, 0, head, headLen, take)
                            headLen += take
                        }
                        digest.update(chunk, 0, n)
                        out.write(chunk, 0, n)
                    }
                }
            }
            if (!looksLikePdf(head, headLen)) throw PdfImportException(PdfImportException.Reason.NOT_PDF)

            val sha = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }.take(16)
            val src = DakNote.pdfEntry(sha)
            val data = temp.inputStream().use { spool.store(src, it) }
            val file = data.file ?: throw PdfImportException(PdfImportException.Reason.UNREADABLE)
            val pages = pagesOf(file)
            return ImportedPdf(src, data, pages, titleFromDisplayName(displayName(resolver, uri)))
        } finally {
            temp.delete()
        }
    }

    /**
     * The pages of [file], as they are shown.
     *
     * `PdfRenderer` is the authority on whether the file opens at all — it is what will draw it —
     * and on how many pages there are. The sizes come from the file itself where it can be read,
     * since the renderer rounds them to whole points and A4 is not a whole number of them.
     */
    fun pagesOf(file: File): List<PdfSourcePage> {
        val rendered = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    List(r.pageCount) { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
                }
            }
        } catch (e: SecurityException) {
            throw PdfImportException(PdfImportException.Reason.PROTECTED, e)
        } catch (e: Exception) {
            throw PdfImportException(PdfImportException.Reason.UNREADABLE, e)
        }
        if (rendered.isEmpty()) throw PdfImportException(PdfImportException.Reason.UNREADABLE)
        val exact = runCatching { PdfFile.open(file).use { pdf -> pdf.pages.map { it.width to it.height } } }
            .getOrNull()
            ?.takeIf { it.size == rendered.size }
        return rendered.indices.map { i ->
            val (w, h) = exact?.get(i) ?: rendered[i]
            PdfSourcePage(i, w, h)
        }
    }

    /**
     * The name a note made from a PDF called [displayName] is given: the file's name without `.pdf`.
     * Compared under `Locale.ROOT`, so `REPORT.PDF` loses its extension on a Turkish device too.
     */
    fun titleFromDisplayName(displayName: String?): String? {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bare = if (name.lowercase(Locale.ROOT).endsWith(".pdf")) name.dropLast(4) else name
        return bare.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [head] starts like a PDF. The header may follow some junk — the specification lets a
     * reader look for it in the first kilobyte, and real files take it up on that.
     */
    fun looksLikePdf(head: ByteArray, length: Int): Boolean {
        val marker = "%PDF-".toByteArray(Charsets.ISO_8859_1)
        for (i in 0..length - marker.size) {
            if ((marker.indices).all { head[i + it] == marker[it] }) return true
        }
        return false
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private const val HEADER_WINDOW = 1024
}
