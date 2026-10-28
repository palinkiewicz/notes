package pl.dakil.notes.ui.sheet

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import pl.dakil.notes.model.MediaData
import pl.dakil.notes.model.PdfPageBlock
import java.io.File
import java.util.concurrent.Executors

/**
 * Renders pages of the PDFs inside notes, for the screen and for raster export.
 *
 * Everything goes through one thread. A `PdfRenderer` allows one open page at a time and is not
 * safe to share, and PDFium itself is happiest single-threaded; one thread is also the natural
 * queue for a screen that asks for the same page at three zoom levels while the user pinches, of
 * which only the last matters.
 *
 * A few documents are kept open, most recently used first, because opening one parses its whole
 * cross-reference: a note scrolled page by page would otherwise reopen its PDF for every page.
 */
object PdfPages {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }
    private val thread = executor.asCoroutineDispatcher()

    private class Open(val file: File, val fd: ParcelFileDescriptor, val renderer: PdfRenderer)

    /** Touched only on [thread]. */
    private val open = ArrayDeque<Open>()

    private const val MAX_OPEN = 3

    /**
     * The file [block]'s PDF is in.
     *
     * A spooled note's PDF already is one. One held in memory — a note read without a spool, which
     * the app does not do but tests and older paths can — is written once to [cacheDir] under its
     * content-derived name, so the same bytes are never written twice.
     */
    fun fileFor(block: PdfPageBlock, cacheDir: File): File? = fileFor(block.data, block.src, cacheDir)

    fun fileFor(data: MediaData, src: String, cacheDir: File): File? {
        data.file?.let { return it }
        val dir = File(cacheDir, "pdf").apply { mkdirs() }
        val target = File(dir, src.substringAfterLast('/'))
        if (target.isFile && target.length() == data.size) return target
        return runCatching {
            val temp = File.createTempFile("pdf-", ".part", dir)
            data.open().use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) temp.delete()
            target.takeIf { it.isFile }
        }.getOrNull()
    }

    /** How many pages [file] has, and each one's size in points as shown; null when it will not open. */
    suspend fun pageSizes(file: File): List<Pair<Float, Float>>? = withContext(thread) {
        runCatching {
            val r = renderer(file)
            List(r.pageCount) { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
        }.getOrNull()
    }

    /**
     * Page [page] of [file], [widthPx] × [heightPx], on [paper].
     *
     * With [region] — left, top, right, bottom as fractions of the page — only that part is drawn,
     * filling the bitmap: the tile a deep zoom needs, rendered at the resolution of the glass rather
     * than as a crop of a page bitmap too large to allocate.
     */
    suspend fun render(
        file: File,
        page: Int,
        widthPx: Int,
        heightPx: Int,
        region: FloatArray? = null,
        paper: Int = Color.WHITE,
    ): ImageBitmap? = withContext(thread) {
        renderBitmap(file, page, widthPx, heightPx, region, paper)?.asImageBitmap()
    }

    /** As [render], as a platform bitmap, for a canvas that is not Compose's. */
    suspend fun renderBitmap(
        file: File,
        page: Int,
        widthPx: Int,
        heightPx: Int,
        region: FloatArray? = null,
        paper: Int = Color.WHITE,
    ): Bitmap? = withContext(thread) {
        if (widthPx <= 0 || heightPx <= 0) return@withContext null
        runCatching {
            val r = renderer(file)
            if (page !in 0 until r.pageCount) return@runCatching null
            r.openPage(page).use { p ->
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                // PDFium draws over what is there; a page is paper, so it starts as paper.
                bitmap.eraseColor(paper)
                val matrix = region?.let {
                    val scaleX = widthPx / ((it[2] - it[0]) * p.width)
                    val scaleY = heightPx / ((it[3] - it[1]) * p.height)
                    Matrix().apply {
                        setScale(scaleX, scaleY)
                        preTranslate(-it[0] * p.width, -it[1] * p.height)
                    }
                }
                p.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    /** Closes every open document, for when the note they belong to is closed. */
    suspend fun closeAll() = withContext(thread) {
        while (open.isNotEmpty()) close(open.removeFirst())
    }

    /** As [closeAll], from somewhere that cannot wait: a screen being disposed. */
    fun release() {
        executor.execute { while (open.isNotEmpty()) close(open.removeFirst()) }
    }

    private fun renderer(file: File): PdfRenderer {
        val i = open.indexOfFirst { it.file == file }
        if (i >= 0) {
            val hit = open.removeAt(i)
            open.addFirst(hit)
            return hit.renderer
        }
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
        open.addFirst(Open(file, fd, renderer))
        while (open.size > MAX_OPEN) close(open.removeLast())
        return renderer
    }

    private fun close(doc: Open) {
        runCatching { doc.renderer.close() }
        runCatching { doc.fd.close() }
    }
}
