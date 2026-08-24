package pl.dakil.notes.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.dakil.notes.data.NoteRepository
import pl.dakil.notes.data.SaveState
import pl.dakil.notes.data.SettingsRepository
import pl.dakil.notes.data.StoreRef
import pl.dakil.notes.data.noteTitle
import pl.dakil.notes.editor.R
import pl.dakil.notes.editor.canvas.InkCallbacks
import pl.dakil.notes.editor.markdown.PendingStyles
import pl.dakil.notes.format.DakNote
import pl.dakil.notes.ink.HitTester
import pl.dakil.notes.ink.PathSplitter
import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ToolSpec
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.ViewMode

/** A lasso selection, held as block/stroke indices rather than copies of the geometry. */
@Immutable
data class Selection(
    val strokesByBlock: Map<BlockId, List<Int>> = emptyMap(),
    val textBlocks: Set<BlockId> = emptySet(),
    val bounds: Rect = Rect.ZERO,
) {
    val isEmpty: Boolean get() = strokesByBlock.isEmpty() && textBlocks.isEmpty()
    val strokeCount: Int get() = strokesByBlock.values.sumOf { it.size }
}

/**
 * A one-shot request to bring a page into view.
 *
 * [token] is what makes it one-shot: the editor keys an effect on the whole request, so asking for
 * the same page twice in a row still fires. Without it, adding a page, scrolling away, and adding
 * another would leave the second request looking identical to the first and quietly do nothing.
 */
@Immutable
data class ScrollRequest(val page: Int, val token: Int)

/**
 * Everything the editor UI renders.
 *
 * Deliberately coarse: this is read during composition, so it changes at user pace. Anything that
 * changes at input pace — the wet stroke, the scroll offset — lives outside it.
 */
@Immutable
data class EditorUiState(
    val note: Note? = null,
    val ref: StoreRef? = null,
    val view: ViewMode = ViewMode.PAGED,
    val tool: ToolSpec = ToolSpec.PEN,
    val toolPresets: List<ToolSpec> = ToolSpec.DEFAULTS,
    /**
     * What the toolbar's pen and eraser buttons go back to.
     *
     * Selecting the lasso or the text tool replaces [tool], so without these the bar would forget
     * which pen you were holding the moment you selected something over the page.
     */
    val lastDrawingTool: ToolId = ToolId.PEN,
    val lastEraser: ToolId = ToolId.ERASER_STROKE,
    /**
     * True when the text tool is chosen: a tap makes or opens a text box rather than drawing.
     *
     * This overrides the overlay's usual "a stylus always draws" rule, and deliberately. The tool
     * in the user's hand is the one they picked, and a text tool a pen cannot use is a text tool
     * that does nothing on the devices this app is for. Picking a pen again goes back to drawing.
     */
    val textToolActive: Boolean = false,
    /**
     * Whether the straightedge is out.
     *
     * Not a tool, which is the whole point of it: a ruler on the desk does not stop you writing,
     * erasing or typing, so this is independent of [tool] and of [textToolActive].
     */
    val rulerEnabled: Boolean = false,
    /** The box the caret is in, if any. Its text is in [NoteViewModel.textField]. */
    val editingTextBlock: BlockId? = null,
    /** The box under the handles: the one being edited, or one tapped with the text tool. */
    val activeTextBlock: BlockId? = null,
    val activeLayerId: BlockId? = null,
    val selection: Selection? = null,
    val inputConfig: InputConfig = InputConfig(),
    /**
     * Whether the toolbar offers the finger-drawing button — the app setting of the same name.
     *
     * With it off there is no button and a finger never draws; with it on the button appears and
     * [inputConfig] carries what the button currently says.
     */
    val fingerDrawingAvailable: Boolean = false,
    val recentColors: List<Int> = emptyList(),
    /** Zoom levels the user pinned, as whole percentages, ascending. */
    val zoomPresets: List<Int> = emptyList(),
    val saveState: SaveState = SaveState.Idle,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Bumped on every document mutation, so the ink overlay can invalidate without deep diffing. */
    val documentVersion: Int = 0,
    val scrollRequest: ScrollRequest? = null,
    /** The unit paper measurements are shown and typed in. An app setting, not a document one. */
    val measurementUnit: MeasurementUnit = MeasurementUnit.CENTIMETRE,
    val isLoading: Boolean = true,
    /**
     * A failure message that came up from the store or the file format.
     *
     * Those layers are pure JVM and have no resources, so their text arrives already written; when
     * a failure has nothing to say, [errorRes] carries the fallback for the screen to resolve.
     */
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
) {
    val sheet: Sheet? get() = note?.sheet
    val isReadOnly: Boolean get() = note?.readOnly == true
    val pageCount: Int get() = note?.sheet?.pageCount() ?: 1
}

/**
 * Owns the open document: one sheet, text boxes and ink side by side on it.
 *
 * The state split is the important part. Screen state is a [StateFlow] read during composition; the
 * document is immutable data replaced wholesale on each edit; and the stroke under the pen never
 * reaches this class at all — the overlay hands over a finished [Stroke] on pointer-up. That is
 * what keeps a 240 Hz input stream off the recomposition path.
 *
 * Typing is the third rate. A keystroke goes into [textField] and no further, and only when the
 * user pauses does it become an [Edit] on the document. Committing per keystroke would push a whole
 * `Sheet` through the state flow for every character, and would fill the undo stack with one entry
 * per letter — [Edit.ReplaceBlock] merges runs on the same block, so a pause is what separates one
 * undo from the next.
 */
class NoteViewModel(
    private val repository: NoteRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val history = EditHistory()

    /**
     * The live buffer for the box being edited, replaced wholesale when a different one opens.
     *
     * A `TextFieldState` per box would put every box's buffer and undo history on the heap at once
     * and let one box's edits reach another's; one field that is handed to whichever box has the
     * caret cannot. Its own undo is deliberately never wired up — the editor's [EditHistory] is the
     * single authority, so that one press of undo steps back through typing and ink in the order
     * they happened rather than through whichever stack happens to be listening.
     */
    var textField by mutableStateOf(TextFieldState())
        private set

    /**
     * What the formatting bar has been asked for at a bare caret in [textField].
     *
     * Held here for the same reason the field is: the bar that arms a style and the box that spends
     * it are in two different parts of the screen, and this is the one thing both of them can see.
     */
    val pendingStyles = PendingStyles()

    /** Watches [textField] and turns pauses in typing into document edits. */
    private var typingJob: Job? = null

    /** The box as it stood when a move or resize began. See [beginTextBlockDrag]. */
    private var dragOrigin: TextBlock? = null

    /** A box that has been put on the page but not yet into the history. See [createTextBlock]. */
    private var pendingBox: BlockId? = null

    init {
        settings.settings
            .onEach { app ->
                _state.update {
                    it.copy(
                        inputConfig = app.input.copy(
                            // The setting says the finger *may* draw; the button says whether it is
                            // doing so now. Turning the setting on starts it drawing, because that
                            // is what the person who turned it on asked for; turning it off stops
                            // it, because the button that would turn it back on is gone.
                            fingerDrawingEnabled = app.fingerDrawingAvailable &&
                                (!it.fingerDrawingAvailable || it.inputConfig.fingerDrawingEnabled),
                        ),
                        fingerDrawingAvailable = app.fingerDrawingAvailable,
                        recentColors = app.recentColors,
                        zoomPresets = app.zoomPresets,
                        measurementUnit = app.measurementUnit,
                    )
                }
            }
            .launchIn(viewModelScope)

        repository.saveState
            .onEach { save -> _state.update { it.copy(saveState = save) } }
            .launchIn(viewModelScope)
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    fun open(ref: StoreRef) {
        // Re-opening the note already in hand would throw away the undo history and any edit still
        // sitting inside the autosave debounce. Worth guarding because the ref of an open note is
        // not fixed: renaming one changes it, and the shell asks for it again when it does.
        val current = _state.value
        if (current.ref == ref && current.note != null && !current.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            repository.load(ref).fold(
                onSuccess = { note -> adopt(note, ref) },
                onFailure = { cause ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = cause.message,
                            errorRes = if (cause.message == null) R.string.editor_error_open else null,
                        )
                    }
                },
            )
        }
    }

    private fun adopt(note: Note, ref: StoreRef?) {
        history.clear()
        closeTextField()
        _state.update { current ->
            current.copy(
                note = note,
                ref = ref,
                view = note.meta.view,
                activeLayerId = note.sheet.defaultInkLayer()?.id,
                editingTextBlock = null,
                activeTextBlock = null,
                selection = null,
                isLoading = false,
                error = null,
                canUndo = false,
                canRedo = false,
                documentVersion = current.documentVersion + 1,
            )
        }
    }

    fun openInMemory(note: Note, ref: StoreRef?) = adopt(note, ref)

    /**
     * Renames the note, on disk and in the document.
     *
     * Not a pure file operation: an ink note also carries its title in its manifest, and the two
     * disagreeing is what makes a note show one name in the library and another in its own app bar.
     * The repository settles both, then hands back where the file ended up — [onRenamed] passes
     * that on to whoever is holding the ref, because the file's name is its identity here.
     */
    fun rename(title: String, onRenamed: (StoreRef) -> Unit = {}) {
        val current = _state.value
        val note = current.note ?: return
        val ref = current.ref ?: return
        val wanted = title.trim()
        if (wanted.isEmpty() || wanted == note.meta.title) return
        viewModelScope.launch {
            // A failure leaves the old title on screen, which is the truth about the file and the
            // only report this screen can make: `error` here replaces the whole editor, and losing
            // the open note over a rename that did not happen would be the worse outcome.
            repository.rename(ref, wanted).onSuccess { moved ->
                // Read back rather than assumed: a collision makes the store step aside, and a
                // title the file does not have is exactly the disagreement this is avoiding.
                val landed = moved.noteTitle()
                _state.update {
                    it.copy(
                        ref = moved,
                        note = it.note?.let { open ->
                            open.copy(meta = open.meta.copy(title = landed))
                        },
                    )
                }
                onRenamed(moved)
            }
        }
    }

    /** Forces a write; called when the editor leaves the foreground. */
    fun flush() {
        val current = _state.value
        val note = current.note ?: return
        val ref = current.ref ?: return
        viewModelScope.launch { repository.flush(ref, note) }
    }

    // ---- Tools ---------------------------------------------------------------------------------

    fun selectTool(tool: ToolId) {
        // Leaving the text tool takes the caret out of the box with it. A caret still blinking in a
        // box while the pen draws over the page is a keyboard the user cannot get rid of, and a
        // Backspace away from deleting something they are no longer looking at.
        clearTextSelection()
        _state.update { current ->
            val spec = current.toolPresets.firstOrNull { it.tool == tool } ?: ToolSpec.defaultFor(tool)
            current.copy(
                tool = spec,
                textToolActive = false,
                lastDrawingTool = if (tool.isDrawing) tool else current.lastDrawingTool,
                lastEraser = if (tool.isEraser) tool else current.lastEraser,
                // Leaving the lasso must drop the selection, or its handles linger over the sheet.
                selection = if (tool == ToolId.LASSO) current.selection else null,
            )
        }
    }

    /**
     * The text tool: a tap makes or opens a box instead of laying down ink, pen included.
     *
     * The lasso selection goes, because the two are different ways of pointing at the same paper
     * and leaving handles from one under the tool for the other is how a drag ends up doing
     * something nobody asked for.
     */
    fun selectTextTool() {
        _state.update { it.copy(textToolActive = true, selection = null) }
    }

    fun updateTool(spec: ToolSpec) {
        _state.update { current ->
            current.copy(
                tool = spec,
                toolPresets = current.toolPresets.map { if (it.tool == spec.tool) spec else it },
            )
        }
    }

    fun setToolColor(tool: ToolId, argb: Int) {
        _state.update { current ->
            val spec = (current.toolPresets.firstOrNull { it.tool == tool }
                ?: ToolSpec.defaultFor(tool)).copy(color = argb)
            current.copy(
                tool = if (current.tool.tool == tool) spec else current.tool,
                toolPresets = current.toolPresets.map { if (it.tool == tool) spec else it },
            )
        }
    }

    /**
     * Flips the finger between drawing and panning.
     *
     * Editor state rather than a setting, like [setRuler]: it is the answer to "what is this hand
     * doing right now", which changes several times in a sitting. What persists is whether the
     * button is on the bar at all, which is `AppSettings.fingerDrawingAvailable`.
     */
    fun setFingerDrawing(enabled: Boolean) = _state.update {
        it.copy(inputConfig = it.inputConfig.copy(fingerDrawingEnabled = enabled))
    }

    /**
     * Puts the straightedge on the page, or takes it away.
     *
     * Kept with the editor rather than in settings: it is a thing you reach for mid-sentence and
     * put down again, and a ruler that was still lying across the page a week later would be a
     * surprise rather than a convenience.
     */
    fun setRuler(enabled: Boolean) = _state.update { it.copy(rulerEnabled = enabled) }

    /** Records a colour the user settled on, so it is one tap away next time. */
    fun rememberColor(argb: Int) = settings.addRecentColor(argb)

    /** Pins the current zoom so it can be picked by name later. Kept app-wide, not per-note. */
    fun addZoomPreset(percent: Int) = settings.addZoomPreset(percent)

    fun removeZoomPreset(percent: Int) = settings.removeZoomPreset(percent)

    fun setActiveLayer(id: BlockId) = _state.update { it.copy(activeLayerId = id) }

    // ---- View ----------------------------------------------------------------------------------

    /**
     * Switches between discrete pages and one continuous scroll.
     *
     * Purely presentational: the document, its paper size and its pagination are identical either
     * way, which is what lets a note written as an endless scroll still print onto A4.
     */
    fun setView(view: ViewMode) {
        val note = _state.value.note ?: return
        _state.update { it.copy(view = view) }
        if (note.meta.view != view) commitEdit(Edit.SetMeta(note.meta, note.meta.copy(view = view)))
    }

    fun setPageFormat(format: PageFormat) = applyDocument { it.withSheet(it.sheet.copy(format = format)) }

    fun setPageBackground(background: PageBackground) {
        val sheet = _state.value.sheet ?: return
        setPageFormat(sheet.format.copy(background = background))
    }

    fun setPageSize(size: PageSize) {
        val sheet = _state.value.sheet ?: return
        setPageFormat(sheet.format.copy(size = size))
    }

    fun setMargins(margins: PageMargins) {
        val sheet = _state.value.sheet ?: return
        setPageFormat(sheet.format.copy(margins = margins))
    }

    // ---- Pages ---------------------------------------------------------------------------------

    /** Appends a blank page to the end of the sheet and scrolls to it. */
    fun addPage() {
        val sheet = _state.value.sheet ?: return
        if (_state.value.isReadOnly) return
        // From the *effective* count, so adding a page after the text has already spilled onto a
        // third one gives a fourth, rather than silently doing nothing.
        val added = sheet.pageCount()
        commitEdit(Edit.SetPages(sheet.pages, added + 1))
        // A page added below the fold, with the view left where it was, is indistinguishable from
        // a button that does nothing.
        _state.update { it.copy(scrollRequest = ScrollRequest(added, it.documentVersion)) }
    }

    /** Copies page [index] and its ink onto a new page directly after it. */
    fun duplicatePage(index: Int) {
        val sheet = _state.value.sheet ?: return
        if (_state.value.isReadOnly || !sheet.canEditPage(index)) return
        commitEdit(Edit.ReplaceSheet(sheet, sheet.withPageDuplicated(index)))
        _state.update { it.copy(scrollRequest = ScrollRequest(index + 1, it.documentVersion)) }
    }

    /** Removes page [index] along with the ink on it. */
    fun removePage(index: Int) {
        val sheet = _state.value.sheet ?: return
        if (_state.value.isReadOnly || !sheet.canRemovePage(index)) return
        commitEdit(Edit.ReplaceSheet(sheet, sheet.withPageRemoved(index)))
    }

    /**
     * Exchanges page [index] with its neighbour [target].
     *
     * Returns true when the swap happened, so the caller can keep the moved page under the reader's
     * eye. That has to be the caller's job: the scroll lives in the view's transform, and doing it
     * here would mean a round trip through state and a frame where the page has moved but the view
     * has not — visible as a jump precisely when the user is looking straight at it.
     */
    fun movePage(index: Int, target: Int): Boolean {
        val sheet = _state.value.sheet ?: return false
        if (_state.value.isReadOnly) return false
        val moved = sheet.withPagesSwapped(index, target)
        if (moved === sheet) return false
        commitEdit(Edit.ReplaceSheet(sheet, moved))
        return true
    }

    // ---- Text boxes ----------------------------------------------------------------------------

    /**
     * Opens a new box at a point on the paper, and puts the caret in it.
     *
     * The width is the page's text column from the tap rightwards, so a box made anywhere near the
     * left margin lines up with the one above it and a box made out in the margin is still as wide
     * as there is room for. Height is a starting guess only: the box reports what it measures and
     * grows to fit from the first keystroke on.
     */
    fun createTextBlock(x: Float, y: Float) {
        val current = _state.value
        val note = current.note ?: return
        val sheet = note.sheet
        if (current.isReadOnly) return

        val format = sheet.format
        val left = x.coerceIn(0f, (format.contentRight - MIN_TEXT_WIDTH).coerceAtLeast(0f))
        val top = y.coerceAtLeast(0f)
        val box = TextBlock(
            id = sheet.nextBlockId(),
            // Over the ink, because this box is the thing the user is about to type into and a
            // stroke drawn earlier must not be painted across the words going onto it.
            z = (sheet.blocks.maxOfOrNull { it.z } ?: 0) + 1,
            rect = Rect(left, top, maxOf(format.contentRight, left + MIN_TEXT_WIDTH), top + NEW_TEXT_HEIGHT),
        )

        // Put on the page with no history behind it. An empty box is not yet something the user has
        // *done* — they have aimed, and aiming somewhere else instead should not cost them an undo.
        // The first characters typed into it are what make it real: see [commitTypedText].
        pendingBox = box.id
        _state.update {
            it.copy(
                note = note.withSheet(sheet.withBlock(box)),
                documentVersion = it.documentVersion + 1,
            )
        }
        beginTextEditing(box.id)
    }

    /** Puts the caret in [id]'s text, loading it into [textField]. */
    fun beginTextEditing(id: BlockId) {
        val current = _state.value
        if (current.isReadOnly) return
        if (current.editingTextBlock == id) return
        val box = current.sheet?.block(id) as? TextBlock ?: return

        // Whatever was in the old field is flushed before it is thrown away: the debounce means the
        // last few characters typed into the box being left have not been committed yet.
        commitTypedText()
        typingJob?.cancel()
        textField = TextFieldState(box.markdown)
        _state.update { it.copy(editingTextBlock = id, activeTextBlock = id, selection = null) }

        // `drop(1)` skips the value the flow emits on subscription — the text just loaded out of
        // the document, which is already what the document says.
        typingJob = snapshotFlow { textField.text.toString() }
            .drop(1)
            .debounce(TYPING_COMMIT_MS)
            .onEach { commitTypedText() }
            .launchIn(viewModelScope)
    }

    /**
     * Takes the caret out of the text, leaving the box selected so it can still be moved.
     *
     * A box left with nothing in it goes away again. There is nothing to see in one and nothing to
     * be done with one, so leaving it behind would mean invisible blocks accumulating wherever the
     * user had tapped and changed their mind — each of them still able to catch the next tap meant
     * for the paper underneath.
     */
    fun endTextEditing() {
        commitTypedText()
        typingJob?.cancel()
        typingJob = null

        val leaving = _state.value.editingTextBlock
        val box = leaving?.let { _state.value.sheet?.block(it) } as? TextBlock
        if (box != null && box.markdown.isEmpty()) discardTextBlock(box)

        _state.update { it.copy(editingTextBlock = null, activeTextBlock = it.activeTextBlock?.takeIf { id -> id != box?.id }) }
    }

    /** Drops the handles as well: nothing on the sheet is singled out any more. */
    fun clearTextSelection() {
        endTextEditing()
        _state.update { it.copy(activeTextBlock = null) }
    }

    /** Singles a box out for its handles without opening the keyboard on it. */
    fun selectTextBlock(id: BlockId?) {
        if (_state.value.editingTextBlock != null && _state.value.editingTextBlock != id) endTextEditing()
        _state.update { it.copy(activeTextBlock = id) }
    }

    /**
     * Starts a move or resize, remembering the box as it stood before the finger went down.
     *
     * A drag is one thing the user did and has to be one thing to undo, but the text has to reflow
     * under the handle as it moves or the drag is guesswork. So the frames in between are written
     * straight onto the document with no history behind them, and [endTextBlockDrag] pushes a
     * single edit from where the box started to where it ended up.
     */
    fun beginTextBlockDrag(id: BlockId) {
        dragOrigin = _state.value.sheet?.block(id) as? TextBlock
    }

    /**
     * Moves the box being dragged to [rect], live and un-undoably.
     *
     * Refuses to shrink past a usable minimum. A box dragged to nothing cannot be dragged back —
     * there would be no handle left to take hold of — and a box one character wide is a column of
     * single letters, which is never what the drag meant.
     */
    fun dragTextBlockTo(id: BlockId, rect: Rect) {
        val note = _state.value.note ?: return
        val sheet = note.sheet
        val block = sheet.block(id) as? TextBlock ?: return
        if (note.readOnly) return

        val sized = Rect(
            left = rect.left,
            top = rect.top,
            right = maxOf(rect.right, rect.left + MIN_TEXT_WIDTH),
            bottom = maxOf(rect.bottom, rect.top + MIN_TEXT_HEIGHT),
        )

        // And kept on the paper. A box dragged off the edge is text that will not print and, once
        // it is far enough out, handles that cannot be reached to drag it back — the page is the
        // only thing the view can be scrolled to, so there is nowhere to go and look for it. Slid
        // back rather than resized: what the drag asked for was a position, not a width.
        val page = sheet.format
        val overshootX = (sized.right - page.width).coerceAtLeast(0f) - sized.left.coerceAtMost(0f)
        val strip = sheet.stripHeight()
        val overshootY = (sized.top + MIN_TEXT_HEIGHT - strip).coerceAtLeast(0f) -
            sized.top.coerceAtMost(0f)
        val clamped = if (sized.width <= page.width) {
            Rect(
                sized.left - overshootX, sized.top - overshootY,
                sized.right - overshootX, sized.bottom - overshootY,
            )
        } else {
            sized
        }
        if (clamped == block.rect) return
        _state.update {
            it.copy(
                note = note.withSheet(sheet.withBlock(block.copy(rect = clamped))),
                documentVersion = it.documentVersion + 1,
            )
        }
    }

    /** Ends a drag, recording the whole of it as one undoable edit. */
    fun endTextBlockDrag() {
        val before = dragOrigin ?: return
        dragOrigin = null
        val after = _state.value.sheet?.block(before.id) as? TextBlock ?: return
        if (after.rect == before.rect) return
        // Put the document back first: `commitEdit` applies the edit itself, and applying it on top
        // of the dragged state would leave the history's "before" pointing at a box that never was.
        _state.update { current ->
            val note = current.note ?: return@update current
            current.copy(note = note.withSheet(note.sheet.withBlock(before)))
        }
        commitEdit(Edit.ReplaceBlock(before, after))
    }

    /**
     * Grows or shrinks a box to the height its text actually needed.
     *
     * Reported by the renderer, because only the text engine knows how tall a paragraph turned out.
     * It is not an undoable edit — nobody *did* this, it is the consequence of what they typed, and
     * an undo stack with a height change between every two keystrokes would be unusable.
     */
    fun reportTextHeight(id: BlockId, heightPt: Float) {
        val note = _state.value.note ?: return
        val sheet = note.sheet
        val box = sheet.block(id) as? TextBlock ?: return

        // Never past the foot of its own page. The text beyond that is clipped rather than shown —
        // it is in the next slice the printer takes — and a rect that reached onto the page below
        // would say there is something on that page: enough to hold it open against deletion, and
        // enough to conjure a blank page at the end of the note that nothing is ever drawn on.
        val pageBottom = (sheet.pageOf(box) + 1) * sheet.format.height
        val room = (pageBottom - box.rect.top).coerceAtLeast(MIN_TEXT_HEIGHT)
        val wanted = heightPt.coerceIn(MIN_TEXT_HEIGHT, room)
        if (kotlin.math.abs(box.rect.height - wanted) < 0.5f) return
        val grown = box.copy(rect = box.rect.copy(bottom = box.rect.top + wanted))
        _state.update { it.copy(note = note.withSheet(sheet.withBlock(grown))) }
    }

    fun deleteTextBlock(id: BlockId) {
        val current = _state.value
        val block = current.sheet?.block(id) as? TextBlock ?: return
        if (current.isReadOnly) return
        closeTextField()
        discardTextBlock(block)
        _state.update { it.copy(editingTextBlock = null, activeTextBlock = null) }
    }

    /** Flips one task's checkbox in a box nobody is typing in. */
    fun toggleTask(id: BlockId, sourceMark: Int, checked: Boolean) {
        val current = _state.value
        val box = current.sheet?.block(id) as? TextBlock ?: return
        if (current.isReadOnly) return
        // The layout the tap was aimed with can be a frame behind the text, so a stale offset has
        // to miss rather than overwrite whatever moved into its place.
        if (sourceMark !in box.markdown.indices || box.markdown[sourceMark] !in " xX") return
        val flipped = box.markdown.replaceRange(sourceMark, sourceMark + 1, if (checked) " " else "x")
        commitEdit(Edit.ReplaceBlock(box, box.copy(markdown = flipped)))
    }

    /**
     * Writes whatever is in [textField] back into its box.
     *
     * Runs on a pause in typing, when the caret leaves, and before the field is reused for another
     * box. Doing nothing when the text has not changed is what keeps an idle field from pushing an
     * empty edit onto the undo stack every time the user taps in and out of a box.
     */
    private fun commitTypedText() {
        val current = _state.value
        val id = current.editingTextBlock ?: return
        val box = current.sheet?.block(id) as? TextBlock ?: return
        if (current.isReadOnly) return
        val typed = textField.text.toString()
        if (typed == box.markdown) return

        val filled = box.copy(markdown = typed)
        if (id != pendingBox) {
            commitEdit(Edit.ReplaceBlock(box, filled))
            return
        }

        // The first thing typed into a brand-new box is what puts the box in the history, so that
        // one undo takes the whole thing away rather than emptying it and leaving the frame behind.
        // The document is put back first because `commitEdit` applies the edit itself.
        pendingBox = null
        _state.update { state ->
            val note = state.note ?: return@update state
            state.copy(note = note.withSheet(note.sheet.withoutBlock(id)))
        }
        commitEdit(Edit.AddBlock(filled))
    }

    /** Takes a box off the page without troubling the history — see [createTextBlock]. */
    private fun discardTextBlock(box: TextBlock) {
        if (box.id != pendingBox) {
            commitEdit(Edit.RemoveBlock(box))
            return
        }
        pendingBox = null
        _state.update { state ->
            val note = state.note ?: return@update state
            state.copy(
                note = note.withSheet(note.sheet.withoutBlock(box.id)),
                documentVersion = state.documentVersion + 1,
            )
        }
    }

    private fun closeTextField() {
        typingJob?.cancel()
        typingJob = null
        pendingBox = null
        textField = TextFieldState()
    }

    fun setTitle(title: String) {
        val note = _state.value.note ?: return
        if (note.meta.title == title) return
        commitEdit(Edit.SetMeta(note.meta, note.meta.copy(title = title)))
    }

    fun setTags(tags: List<String>) {
        val note = _state.value.note ?: return
        commitEdit(Edit.SetMeta(note.meta, note.meta.copy(tags = tags)))
    }

    // ---- Ink callbacks ---------------------------------------------------------------------------

    val inkCallbacks: InkCallbacks = object : InkCallbacks {

        override fun onStrokeCommitted(stroke: Stroke) {
            val current = _state.value
            val sheet = current.sheet ?: return
            if (current.isReadOnly) return
            val layer = current.activeLayer(sheet) ?: return
            if (layer.locked) return

            // Each stroke is its own undo step: fast handwriting is many strokes, not one edit.
            commitEdit(
                Edit.ReplaceBlock(
                    layer,
                    layer.copy(strokes = layer.strokes + stroke),
                    mergeable = false,
                )
            )
        }

        override fun onEraseAlong(
            x0: Float, y0: Float, x1: Float, y1: Float,
            radius: Float, wholeStroke: Boolean,
        ) {
            val current = _state.value
            val sheet = current.sheet ?: return
            if (current.isReadOnly) return

            val edits = ArrayList<Edit>(2)
            for (block in sheet.blocks) {
                if (block !is InkBlock || block.locked || !block.visible) continue

                val updated = if (wholeStroke) {
                    val hits = HitTester.strokesTouchedBySegment(block.strokes, x0, y0, x1, y1, radius)
                    if (hits.isEmpty()) continue
                    val doomed = hits.toHashSet()
                    block.strokes.filterIndexed { i, _ -> i !in doomed }
                } else {
                    var changed = false
                    val out = ArrayList<Stroke>(block.strokes.size)
                    for (stroke in block.strokes) {
                        val pieces = PathSplitter.eraseAlongSegment(stroke, x0, y0, x1, y1, radius)
                        // PathSplitter returns the same instance when nothing was hit, so identity
                        // is a reliable and allocation-free "unchanged" test.
                        if (pieces.size == 1 && pieces[0] === stroke) out += stroke
                        else {
                            changed = true
                            out += pieces
                        }
                    }
                    if (!changed) continue
                    out
                }

                edits += Edit.ReplaceBlock(block, block.copy(strokes = updated))
            }

            if (edits.isEmpty()) return
            commitEdit(if (edits.size == 1) edits[0] else Edit.Batch(edits))
        }

        override fun onLassoCommitted(xs: FloatArray, ys: FloatArray, count: Int) {
            val sheet = _state.value.sheet ?: return

            val byBlock = LinkedHashMap<BlockId, List<Int>>()
            var bounds = Rect.EMPTY
            for (block in sheet.blocks) {
                if (block !is InkBlock || !block.visible) continue
                val hits = HitTester.strokesInPolygon(block.strokes, xs, ys, count)
                if (hits.isEmpty()) continue
                byBlock[block.id] = hits
                bounds = bounds.union(HitTester.boundsOf(block.strokes, hits))
            }

            val texts = sheet.blocks.filterIsInstance<TextBlock>()
                .filter { text ->
                    val r = text.worldBounds()
                    HitTester.pointInPolygon(r.left, r.top, xs, ys, count) &&
                        HitTester.pointInPolygon(r.right, r.bottom, xs, ys, count)
                }
                .map { it.id }
            for (id in texts) sheet.block(id)?.let { bounds = bounds.union(it.worldBounds()) }

            val selection = Selection(byBlock, texts.toSet(), bounds)
            _state.update { it.copy(selection = selection.takeUnless { s -> s.isEmpty }) }
        }
    }

    // ---- Selection -------------------------------------------------------------------------------

    fun transformSelection(matrix: Affine) = mutateSelection { it.transformed(matrix) }

    fun recolorSelection(color: Int) = mutateSelection { it.withStyle(color = color) }

    fun clearSelection() = _state.update { it.copy(selection = null) }

    fun deleteSelection() {
        val current = _state.value
        val selection = current.selection ?: return
        val sheet = current.sheet ?: return
        if (current.isReadOnly) return

        val edits = ArrayList<Edit>()
        for ((blockId, indices) in selection.strokesByBlock) {
            val block = sheet.block(blockId) as? InkBlock ?: continue
            val doomed = indices.toHashSet()
            edits += Edit.ReplaceBlock(
                block,
                block.copy(strokes = block.strokes.filterIndexed { i, _ -> i !in doomed }),
            )
        }
        for (blockId in selection.textBlocks) {
            sheet.block(blockId)?.let { edits += Edit.RemoveBlock(it) }
        }
        if (edits.isEmpty()) return

        commitEdit(if (edits.size == 1) edits[0] else Edit.Batch(edits))
        _state.update { it.copy(selection = null) }
    }

    fun duplicateSelection() {
        val current = _state.value
        val selection = current.selection ?: return
        val sheet = current.sheet ?: return

        // Offset the copy so it is visibly distinct rather than hidden behind the original.
        val offset = Affine.translate(DUPLICATE_OFFSET, DUPLICATE_OFFSET)
        val edits = ArrayList<Edit>()
        for ((blockId, indices) in selection.strokesByBlock) {
            val block = sheet.block(blockId) as? InkBlock ?: continue
            val copies = indices.mapNotNull { block.strokes.getOrNull(it)?.transformed(offset) }
            edits += Edit.ReplaceBlock(block, block.copy(strokes = block.strokes + copies))
        }
        if (edits.isEmpty()) return
        commitEdit(if (edits.size == 1) edits[0] else Edit.Batch(edits))
    }

    private fun mutateSelection(transform: (Stroke) -> Stroke) {
        val current = _state.value
        val selection = current.selection ?: return
        val sheet = current.sheet ?: return
        if (current.isReadOnly) return

        val edits = ArrayList<Edit>()
        for ((blockId, indices) in selection.strokesByBlock) {
            val block = sheet.block(blockId) as? InkBlock ?: continue
            val targets = indices.toHashSet()
            edits += Edit.ReplaceBlock(
                block,
                block.copy(
                    strokes = block.strokes.mapIndexed { i, s -> if (i in targets) transform(s) else s },
                ),
            )
        }
        if (edits.isEmpty()) return
        commitEdit(if (edits.size == 1) edits[0] else Edit.Batch(edits))
    }

    // ---- Layers ----------------------------------------------------------------------------------

    fun addInkLayer() {
        val sheet = _state.value.sheet ?: return
        if (_state.value.isReadOnly) return
        val id = sheet.nextBlockId()
        val layer = InkBlock(
            id = id,
            z = (sheet.blocks.maxOfOrNull { it.z } ?: 0) + 1,
            rect = Rect(0f, 0f, sheet.format.width, sheet.stripHeight()),
            name = "Layer ${sheet.inkLayers().size + 1}",
        )
        commitEdit(Edit.AddBlock(layer))
        _state.update { it.copy(activeLayerId = id) }
    }

    // ---- History ---------------------------------------------------------------------------------

    fun undo() {
        val note = _state.value.note ?: return
        publish(history.undo(note) ?: return)
    }

    fun redo() {
        val note = _state.value.note ?: return
        publish(history.redo(note) ?: return)
    }

    // ---- Plumbing --------------------------------------------------------------------------------

    private fun commitEdit(edit: Edit) {
        val note = _state.value.note ?: return
        history.push(edit)
        publish(edit.apply(note))
    }

    private fun applyDocument(transform: (Note) -> Note) {
        val note = _state.value.note ?: return
        publish(transform(note))
    }

    private fun publish(note: Note) {
        _state.update { current ->
            current.copy(
                note = note,
                view = note.meta.view,
                documentVersion = current.documentVersion + 1,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            )
        }
        _state.value.ref?.let { repository.requestSave(it, note) }
    }

    private fun EditorUiState.activeLayer(sheet: Sheet): InkBlock? =
        (activeLayerId?.let { sheet.block(it) } as? InkBlock) ?: sheet.defaultInkLayer()

    companion object {
        private const val DUPLICATE_OFFSET = 16f

        /** How long a pause in typing ends one undo step and starts the next. */
        private const val TYPING_COMMIT_MS = 400L

        /** A new box is a line tall and as wide as the column; both are only a starting point. */
        private const val NEW_TEXT_HEIGHT = 28f
        private const val MIN_TEXT_WIDTH = 48f
        private const val MIN_TEXT_HEIGHT = 16f

        fun factory(repository: NoteRepository, settings: SettingsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NoteViewModel(repository, settings) as T
            }
    }
}
