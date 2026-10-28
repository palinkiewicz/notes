package pl.dakil.notes.editor.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.dakil.notes.data.ImportedPdf
import pl.dakil.notes.editor.R
import pl.dakil.notes.model.PageSelection
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.sheet.PdfPages

/** Where the chosen pages go. */
enum class PdfInsertPosition { AFTER_CURRENT, START, END }

/**
 * Which pages of a PDF go into the note, and where.
 *
 * Two ways to choose, kept in step: tapping pages in the grid, and typing them — `1-3, 7` — which
 * is quicker than tapping through a two-hundred-page reader for three chapters. All of them are
 * chosen to begin with, since the commonest case is the whole document.
 *
 * Full screen because the grid is the point of it: on a phone a dialog's worth of thumbnails is
 * four pages, which is not enough to find anything by.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfInsertDialog(
    pdf: ImportedPdf,
    /** The 0-based page the screen is on, offered as "after page n". */
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onInsert: (pages: List<Int>, at: Int) -> Unit,
) {
    val total = pdf.pages.size
    var chosen by remember(pdf) { mutableStateOf((0 until total).toSet()) }
    var typed by remember(pdf) { mutableStateOf("") }
    var position by remember { mutableStateOf(PdfInsertPosition.AFTER_CURRENT) }
    val typedValid = PageSelection.parse(typed, total) != null

    fun choose(pages: Set<Int>) {
        chosen = pages
        typed = PageSelection.format(pages, total)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.editor_pdf_insert_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(NotesIcons.Close, contentDescription = stringResource(R.string.editor_cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = chosen.isNotEmpty(),
                            onClick = {
                                val at = when (position) {
                                    PdfInsertPosition.AFTER_CURRENT -> currentPage + 1
                                    PdfInsertPosition.START -> 0
                                    PdfInsertPosition.END -> pageCount
                                }
                                onInsert(chosen.sorted(), at)
                            },
                        ) { Text(stringResource(R.string.editor_pdf_insert_confirm, chosen.size)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val options = PdfInsertPosition.entries
                    options.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = position == option,
                            onClick = { position = option },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) {
                            Text(
                                when (option) {
                                    PdfInsertPosition.AFTER_CURRENT -> stringResource(R.string.editor_pdf_after_page, currentPage + 1)
                                    PdfInsertPosition.START -> stringResource(R.string.editor_pdf_at_start)
                                    PdfInsertPosition.END -> stringResource(R.string.editor_pdf_at_end)
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { text ->
                            typed = text
                            PageSelection.parse(text, total)?.let { chosen = it.toSet() }
                        },
                        label = { Text(stringResource(R.string.editor_pdf_pages_field)) },
                        placeholder = { Text(stringResource(R.string.editor_pdf_all_pages, total)) },
                        isError = !typedValid,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { choose((0 until total).toSet()) }) {
                        Text(stringResource(R.string.editor_pdf_select_all))
                    }
                    TextButton(onClick = { chosen = emptySet(); typed = "" }) {
                        Text(stringResource(R.string.editor_pdf_select_none))
                    }
                }
                PageGrid(pdf, chosen) { index ->
                    choose(if (index in chosen) chosen - index else chosen + index)
                }
            }
        }
    }
}

@Composable
private fun PageGrid(pdf: ImportedPdf, chosen: Set<Int>, onToggle: (Int) -> Unit) {
    val thumbnails = remember(pdf) { mutableStateMapOf<Int, ImageBitmap>() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pdf.pages, key = { _, page -> page.index }) { _, page ->
            val file = pdf.data.file
            // Rendered as it scrolls into view, on the renderer's one thread, and kept for the
            // dialog's lifetime: a few hundred small thumbnails are cheaper than rendering twice.
            LaunchedEffect(page.index) {
                if (file == null || page.index in thumbnails) return@LaunchedEffect
                val w = THUMB_PX
                val h = (w * page.height / page.width.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                PdfPages.render(file, page.index, w, h)?.let { thumbnails[page.index] = it }
            }
            val selected = page.index in chosen
            OutlinedCard(Modifier.clickable { onToggle(page.index) }) {
                Box {
                    val bitmap = thumbnails[page.index]
                    val ratio = (page.width / page.height.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White))
                    }
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle(page.index) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                Text(
                    text = "${page.index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private const val THUMB_PX = 240
