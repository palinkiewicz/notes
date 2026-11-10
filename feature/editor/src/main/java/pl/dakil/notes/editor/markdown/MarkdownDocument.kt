package pl.dakil.notes.editor.markdown

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange

/**
 * Where a [MarkdownDocument] is scrolled to, hoisted so the screen can save and restore it.
 *
 * A position is a place in the *text* — the source offset the top block starts at, and how far
 * into that block — rather than pixels down the note, which a lazily laid out list does not have.
 */
@Stable
class MarkdownDocumentState internal constructor(internal val list: LazyListState) {
    internal var rows: MarkdownRows? = null

    /** The top block's source offset and how many of its pixels are scrolled past, once laid out. */
    fun position(): Pair<Int, Int>? {
        val rows = rows ?: return null
        val row = list.firstVisibleItemIndex
        if (row !in 0 until rows.size) return null
        return rows.startOf(row) to list.firstVisibleItemScrollOffset
    }
}

@Composable
fun rememberMarkdownDocumentState(): MarkdownDocumentState {
    val list = rememberLazyListState()
    return remember(list) { MarkdownDocumentState(list) }
}

/**
 * The rows of the list for one [MarkdownBlocks.Layout]: one per item, except that the items the
 * live window covers share the one row it is drawn in.
 */
internal class MarkdownRows(val layout: MarkdownBlocks.Layout) {
    private val keys = MarkdownBlocks.keys(layout.source, layout.items)
    private val itemOfRow: IntArray = layout.items.indices
        .filter { it !in layout.window || it == layout.window.first }
        .toIntArray()
    val size: Int get() = itemOfRow.size
    val liveRow: Int = itemOfRow.indexOf(layout.window.first)

    fun item(row: Int): MdItem = layout.items[itemOfRow[row]]
    fun startOf(row: Int): Int = item(row).start
    fun key(row: Int): Any = if (row == liveRow) LIVE_KEY else keys[itemOfRow[row]]

    fun rowOfSource(offset: Int): Int {
        val item = MarkdownBlocks.itemAt(layout.items, offset)
        if (item in layout.window) return liveRow
        var low = 0
        var high = itemOfRow.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (itemOfRow[mid] <= item) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        /** The live row's key never changes, so typing in it never recreates the field. */
        const val LIVE_KEY = "live"
    }
}

/**
 * A `.md` note as a list of blocks, one of them a live text field — the note screen's editor.
 *
 * One [MarkdownEditor] laid out over the whole of a long note re-laid and repainted all of it on
 * every keystroke, every scroll step and every blink of the caret. Here the document is cut into
 * blocks ([MarkdownBlocks]); the block the selection is in is the live field, which still holds
 * the whole document in [state] but shows only that block, and every other block is drawn by
 * [MarkdownSliceText] from a slice of the same plan, so nothing moves when a block goes live. Every
 * guard, the format bar, undo and autosave see the one [state] they always saw.
 */
@Composable
fun MarkdownDocument(
    state: TextFieldState,
    sourceMode: Boolean,
    documentState: MarkdownDocumentState,
    pending: PendingStyles,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    attributes: Boolean = false,
    images: ImageSource? = null,
    onRequestLink: (() -> Unit)? = null,
    /** Where to open the note: a source offset and pixels into its block. */
    initialPosition: Pair<Int, Int>? = null,
) {
    val styles = rememberMarkdownStyles(attributes)
    val pictures = rememberMarkdownImages(images)
    val baseStyle = markdownTextStyle(sourceMode)
    val body = windowBodyStyle(baseStyle)
    val textStyle = windowBaseStyle(baseStyle)

    val layout by remember(state, sourceMode) {
        derivedStateOf {
            val selection = state.selection
            MarkdownBlocks.layout(state.text.toString(), selection.start, selection.end, !sourceMode)
        }
    }
    val rows = remember(layout) { MarkdownRows(layout) }
    val list = documentState.list
    SideEffect { documentState.rows = rows }

    // The list keeps its place by the key of the row at the top. When that row was the live one and
    // the window has moved on, "live" now names some other block and the list would jump to it;
    // the block that was on top is a static row now, so the place is kept by pointing at that.
    val previous = remember { arrayOfNulls<MarkdownRows>(1) }
    val before = previous[0]
    if (before != null && before !== rows && before.layout.windowStart != layout.windowStart) {
        Snapshot.withoutReadObservation {
            val top = list.firstVisibleItemIndex
            if (top == before.liveRow && top < before.size) {
                list.requestScrollToItem(rows.rowOfSource(before.startOf(top)), list.firstVisibleItemScrollOffset)
            }
        }
    }
    previous[0] = rows

    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var focusPending by remember { mutableStateOf(false) }
    val currentRows by rememberUpdatedState(rows)

    LaunchedEffect(documentState) {
        val (anchor, offset) = initialPosition ?: return@LaunchedEffect
        val at = currentRows
        list.scrollToItem(at.rowOfSource(anchor.coerceIn(0, at.layout.source.length)), offset)
    }

    // The keyboard was handed the block as it was; a window that moves while it is up has to be
    // handed again, or the keyboard goes on editing text that is no longer what it thinks.
    //
    // Keyed on the window as composed, not observed as it changes: the rows have to be the new
    // ones by the time this looks at them, and so does the list's layout — a check made before the
    // list had placed the new rows found the live row still where the *old* window was, off
    // screen, and scrolled back to it, away from the block the user had just tapped.
    val view = LocalView.current
    val windowStart = layout.windowStart
    val firstWindow = remember(state, sourceMode) { intArrayOf(windowStart) }
    LaunchedEffect(windowStart) {
        if (windowStart == firstWindow[0]) return@LaunchedEffect
        firstWindow[0] = -1
        if (!focused) return@LaunchedEffect
        withFrameNanos { }
        // Somewhere the list cannot see — an undo, a jump to the end — has to be brought on
        // screen before the field can bring its caret into view.
        val visible = list.layoutInfo.visibleItemsInfo.any { it.key == MarkdownRows.LIVE_KEY }
        if (!visible) list.scrollToItem(currentRows.liveRow)
        view.context.getSystemService(InputMethodManager::class.java)?.restartInput(view)
    }

    LazyColumn(state = list, modifier = modifier.fillMaxSize()) {
        items(
            count = rows.size,
            key = { rows.key(it) },
            contentType = { if (it == rows.liveRow) 1 else 0 },
        ) { row ->
            if (row == rows.liveRow) {
                Box(Modifier.graphicsLayer()) {
                    // Kept composed while it has focus, however far it is scrolled away: a field
                    // the list disposed of would take the keyboard and the composition with it.
                    val pinnable = LocalPinnableContainer.current
                    DisposableEffect(focused, pinnable) {
                        val handle = if (focused) pinnable?.pin() else null
                        onDispose { handle?.release() }
                    }
                    MarkdownEditor(
                        state = state,
                        sourceMode = sourceMode,
                        modifier = Modifier.onFocusChanged { focused = it.isFocused },
                        autoFocus = autoFocus,
                        attributes = attributes,
                        pending = pending,
                        images = images,
                        onRequestLink = onRequestLink,
                        windowed = true,
                        sharedImages = pictures,
                        focusRequester = focusRequester,
                    )
                    // Requested from in here rather than from the tap: the tap moves the window,
                    // and until this row has been composed there is no field to give focus to.
                    // The keyboard is asked for separately, because a field that kept its focus
                    // after Back put the keyboard away takes a focus request as nothing to do — a
                    // tap on the field itself brings the keyboard back, and a tap on any other
                    // block has to as well.
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(focusPending) {
                        if (focusPending) {
                            focusRequester.requestFocus()
                            keyboard?.show()
                            focusPending = false
                        }
                    }
                }
            } else {
                val item = rows.item(row)
                MarkdownSliceText(
                    slice = MarkdownBlocks.sliceOf(layout, item.start, item.end),
                    source = layout.source,
                    styles = styles,
                    textStyle = textStyle,
                    body = body,
                    pictures = pictures,
                    selection = state.selection,
                    onTap = { offset ->
                        state.edit { selection = TextRange(offset.coerceIn(0, length)) }
                        focusPending = true
                    },
                    onToggleTask = { toggleTask(state, it) },
                )
            }
        }
    }
}
