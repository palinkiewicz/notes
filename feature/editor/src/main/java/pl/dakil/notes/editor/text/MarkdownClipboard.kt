package pl.dakil.notes.editor.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.contextmenu.builder.TextContextMenuBuilderScope
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.launch

/**
 * Copy and cut that carry away the Markdown rather than the rendering of it.
 *
 * A text field copies what it is *showing*, and what this one shows is the formatted view: a list
 * came out as `•  milk`, a bold word came out with its asterisks gone, and none of it could be
 * pasted back anywhere as anything but prose. What the user selected was three list items, and three
 * list items is what they meant to take with them.
 *
 * Two routes were tried before this one, and both are traps worth naming. Wrapping `LocalClipboard`
 * looks like the obvious answer and brings the selection toolbar down on sight: the toolbar reaches
 * past the `Clipboard` interface for the platform clipboard behind it, and refuses anything that is
 * not the implementation it shipped. Replacing only Copy and Cut works but buries them in the
 * overflow menu, because the supported API can add and filter components but not reorder them, and
 * whatever is appended lands after everything the field added itself.
 *
 * So the whole set is ours: the field's four items are filtered out and four replacements are added
 * in the order Android puts them in. Paste and Select all behave exactly as the originals did — they
 * are here only to hold their places in the row.
 */
@Composable
fun Modifier.markdownClipboard(state: TextFieldState): Modifier {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val labels = remember(context) { MenuLabels(context) }

    fun carryAway(cut: Boolean) {
        val selection = state.selection
        if (selection.collapsed) return
        val source = state.text.toString().substring(selection.min, selection.max)
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("markdown", source)))
        }
        state.edit {
            if (cut) replace(selection.min, selection.max, "")
            // Copy leaves the caret at the far end of what was taken, as the field's own copy does.
            this.selection = TextRange(if (cut) selection.min else selection.max)
        }
    }

    fun pasteOver() {
        scope.launch {
            val entry = clipboard.getClipEntry() ?: return@launch
            val pasted = entry.clipData.plainText(context) ?: return@launch
            val selection = state.selection
            state.edit {
                replace(selection.min, selection.max, pasted)
                this.selection = TextRange(selection.min + pasted.length)
            }
        }
    }

    return this
        .onPreviewKeyEvent { event ->
            // The same two actions from a hardware keyboard, which never reaches the context menu.
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
            when (event.key) {
                Key.C -> carryAway(cut = false)
                Key.X -> carryAway(cut = true)
                else -> return@onPreviewKeyEvent false
            }
            true
        }
        .filterTextContextMenuComponents { it.key !in ReplacedKeys }
        .appendTextContextMenuComponents { markdownItems(state, labels, ::carryAway, ::pasteOver) }
}

/** The four the field adds for itself, each of which is replaced below. */
private val ReplacedKeys = setOf(
    TextContextMenuKeys.CutKey,
    TextContextMenuKeys.CopyKey,
    TextContextMenuKeys.PasteKey,
    TextContextMenuKeys.SelectAllKey,
)

/**
 * The row, in Android's own order, showing each item only when it would do something.
 *
 * Read inside the builder rather than captured outside it: the builder is snapshot-aware and runs
 * when the menu opens, so the selection it sees is the one the user is looking at.
 */
private fun TextContextMenuBuilderScope.markdownItems(
    state: TextFieldState,
    labels: MenuLabels,
    carryAway: (cut: Boolean) -> Unit,
    pasteOver: () -> Unit,
) {
    val selection = state.selection
    if (!selection.collapsed) {
        item(MarkdownCutKey, labels.cut, labels.cutIcon) { carryAway(true); close() }
        item(MarkdownCopyKey, labels.copy, labels.copyIcon) { carryAway(false); close() }
    }
    if (labels.clipboard?.hasPrimaryClip() == true) {
        item(MarkdownPasteKey, labels.paste, labels.pasteIcon) { pasteOver(); close() }
    }
    if (selection.min != 0 || selection.max != state.text.length) {
        item(MarkdownSelectAllKey, labels.selectAll, labels.selectAllIcon) {
            state.edit { this.selection = TextRange(0, length) }
            close()
        }
    }
}

/** Whatever text a clip holds, coerced from whatever it actually is. */
private fun ClipData.plainText(context: Context): String? {
    val text = (0 until itemCount)
        .mapNotNull { getItemAt(it).coerceToText(context)?.toString() }
        .joinToString("\n")
    return text.ifEmpty { null }
}

/**
 * The platform's own labels and icons, so the replacements are indistinguishable from the items
 * they stand in for — in every language the device has, without a string of our own.
 */
private class MenuLabels(context: Context) {
    val copy: String = context.getString(android.R.string.copy)
    val cut: String = context.getString(android.R.string.cut)
    val paste: String = context.getString(android.R.string.paste)
    val selectAll: String = context.getString(android.R.string.selectAll)
    val clipboard: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    val cutIcon: Int
    val copyIcon: Int
    val pasteIcon: Int
    val selectAllIcon: Int

    init {
        val icons = context.theme.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.actionModeCutDrawable,
                android.R.attr.actionModeCopyDrawable,
                android.R.attr.actionModePasteDrawable,
                android.R.attr.actionModeSelectAllDrawable,
            ),
        )
        cutIcon = icons.getResourceId(0, 0)
        copyIcon = icons.getResourceId(1, 0)
        pasteIcon = icons.getResourceId(2, 0)
        selectAllIcon = icons.getResourceId(3, 0)
        icons.recycle()
    }
}

private val MarkdownCutKey = Any()
private val MarkdownCopyKey = Any()
private val MarkdownPasteKey = Any()
private val MarkdownSelectAllKey = Any()
