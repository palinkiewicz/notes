package pl.dakil.notes.editor

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import pl.dakil.notes.editor.canvas.SheetTransform
import pl.dakil.notes.editor.pdf.PdfRenderPlan
import pl.dakil.notes.model.PdfPageBlock
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.ui.sheet.PdfPages
import pl.dakil.notes.ui.sheet.SheetPainter
import java.io.File
import kotlin.math.abs

/**
 * The sheet's PDF pages: the paper of every page that has one, under everything written on it.
 *
 * Drawn in two levels — see [PdfRenderPlan]. A whole-page base per page in the window, re-rendered
 * only when the zoom crosses a step, and over it, once a pinch or a pan has settled, a tile of the
 * part on screen at the glass's own resolution. Neither is rendered from inside a draw pass: the
 * renders run in effects and land in snapshot maps that the draw lambda reads, so a pan frame costs
 * a few bitmap blits and nothing else.
 *
 * The page's own colours, always. A PDF is a printed page and a dark theme does not reprint it.
 */
@OptIn(FlowPreview::class)
@Composable
fun SheetPdfLayer(
    sheet: Sheet,
    ptToPx: Float,
    paged: Boolean,
    transform: SheetTransform,
    pages: State<IntRange>,
    modifier: Modifier = Modifier,
) {
    val byPage = remember(sheet) {
        sheet.blocks.filterIsInstance<PdfPageBlock>().associateBy { sheet.pageOf(it) }
    }
    if (byPage.isEmpty()) return

    val cacheDir = LocalContext.current.cacheDir
    val format = sheet.format
    // One bitmap per PDF page shown, keyed by the page of the PDF rather than of the sheet: a page
    // duplicated is one bitmap drawn twice.
    val bases = remember { mutableStateMapOf<String, ImageBitmap>() }
    val tiles = remember { mutableStateMapOf<String, Pair<Rect, ImageBitmap>>() }
    val files = remember { HashMap<String, File>() }

    suspend fun fileOf(block: PdfPageBlock): File? = files[block.src]
        ?: withContext(Dispatchers.IO) { PdfPages.fileFor(block, cacheDir) }?.also { files[block.src] = it }

    val bucket by remember(transform, ptToPx) {
        derivedStateOf { PdfRenderPlan.bucket(ptToPx * transform.zoom) }
    }
    val window = pages.value

    LaunchedEffect(byPage, window, bucket) {
        val wanted = window.mapNotNull { byPage[it] }
        // Nearest the middle of the window first: the pages either side are only there in case.
        val middle = (window.first + window.last) / 2f
        for (block in wanted.sortedBy { abs(sheet.pageOf(it) - middle) }) {
            val size = PdfRenderPlan.baseSize(block.rect.width, block.rect.height, bucket)
            val id = identOf(block)
            if (bases[id]?.width == size[0]) continue
            val file = fileOf(block) ?: continue
            PdfPages.render(file, block.page, size[0], size[1])?.let { bases[id] = it }
        }
        val shown = wanted.mapTo(HashSet()) { identOf(it) }
        bases.keys.retainAll(shown)
        tiles.keys.retainAll(shown)
    }

    LaunchedEffect(byPage, transform, ptToPx, paged) {
        snapshotFlow {
            floatArrayOf(transform.zoom, transform.visibleLeft(), transform.visibleTop(), transform.visibleRight(), transform.visibleBottom())
        }
            .debounce(TILE_SETTLE_MS)
            .collectLatest { v ->
                val pxPerPt = ptToPx * v[0]
                val next = HashMap<String, Pair<Rect, ImageBitmap>>()
                val stride = SheetPainter.pageStridePx(format, ptToPx, paged)
                val visiblePages = SheetPainter.pageWindow(v[2], v[4], format, sheet.pageCount(), ptToPx, paged, slack = 0)
                for (page in visiblePages) {
                    val block = byPage[page] ?: continue
                    val base = PdfRenderPlan.baseSize(block.rect.width, block.rect.height, pxPerPt)
                    if (!PdfRenderPlan.needsDetail(block.rect.width, base[0], pxPerPt)) continue
                    // The window in this page's own sheet points: the strip has gaps between pages
                    // that the document does not, so each page is measured from its own top.
                    val pageTopPx = page * stride
                    val visible = Rect(
                        v[1] / ptToPx,
                        page * format.height + (v[2] - pageTopPx) / ptToPx,
                        v[3] / ptToPx,
                        page * format.height + (v[4] - pageTopPx) / ptToPx,
                    )
                    val tile = PdfRenderPlan.tile(block.rect, visible, pxPerPt) ?: continue
                    val file = fileOf(block) ?: continue
                    val bitmap = PdfPages.render(file, block.page, tile.widthPx, tile.heightPx, tile.region) ?: continue
                    next[identOf(block)] = tile.rect to bitmap
                }
                // Swapped in whole, so a pan never shows half of the old tiles and half of the new.
                tiles.clear()
                tiles.putAll(next)
            }
    }

    // A document closed with its note, rather than held open until another PDF pushes it out.
    DisposableEffect(Unit) { onDispose { PdfPages.release() } }

    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val white = remember { Paint().apply { color = android.graphics.Color.WHITE } }
    val dst = remember { RectF() }
    Canvas(modifier) {
        for (page in pages.value) {
            val block = byPage[page] ?: continue
            val id = identOf(block)
            stripRect(block.rect, page, ptToPx, paged, dst)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(dst, white)
                bases[id]?.let { canvas.nativeCanvas.drawBitmap(it.asAndroidBitmap(), null, dst, paint) }
                tiles[id]?.let { (rect, bitmap) ->
                    stripRect(rect, page, ptToPx, paged, dst)
                    canvas.nativeCanvas.drawBitmap(bitmap.asAndroidBitmap(), null, dst, paint)
                }
            }
        }
    }
}

/**
 * Where [rect], a part of sheet page [page], is in the strip, in pixels, into [out].
 *
 * Floats all the way, rather than the whole-pixel offsets Compose's own `drawImage` takes: the
 * strip is drawn unzoomed and scaled afterwards, so half a pixel here is four on the glass at an
 * eight-times zoom, and the PDF would sit visibly off the ink written over it.
 */
private fun stripRect(rect: Rect, page: Int, ptToPx: Float, paged: Boolean, out: RectF) {
    val shift = if (paged) page * SheetPainter.PAGE_GAP_PT * ptToPx else 0f
    out.set(rect.left * ptToPx, rect.top * ptToPx + shift, rect.right * ptToPx, rect.bottom * ptToPx + shift)
}

private fun identOf(block: PdfPageBlock): String = "${block.src}#${block.page}"

/** How long a pinch or a pan has to have been still before the screen's tile is redrawn. */
private const val TILE_SETTLE_MS = 150L
