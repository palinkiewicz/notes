package pl.dakil.notes.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
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
import pl.dakil.notes.editor.canvas.InkCallbacks
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
    /** True when the text tool is chosen: a finger tap edits text rather than drawing. */
    val textToolActive: Boolean = false,
    val editingText: Boolean = false,
    val activeLayerId: BlockId? = null,
    val selection: Selection? = null,
    val inputConfig: InputConfig = InputConfig(),
    val recentColors: List<Int> = emptyList(),
    val saveState: SaveState = SaveState.Idle,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Bumped on every document mutation, so the ink overlay can invalidate without deep diffing. */
    val documentVersion: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val sheet: Sheet? get() = note?.sheet
    val isReadOnly: Boolean get() = note?.readOnly == true
    val pageCount: Int get() = note?.sheet?.pageCount() ?: 1
}

/**
 * Owns the open document: one sheet, Markdown underneath and ink on top.
 *
 * The state split is the important part. Screen state is a [StateFlow] read during composition; the
 * document is immutable data replaced wholesale on each edit; and the stroke under the pen never
 * reaches this class at all — the overlay hands over a finished [Stroke] on pointer-up. That is
 * what keeps a 240 Hz input stream off the recomposition path.
 */
class NoteViewModel(
    private val repository: NoteRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val history = EditHistory()

    init {
        settings.settings
            .onEach { app ->
                _state.update { it.copy(inputConfig = app.input, recentColors = app.recentColors) }
            }
            .launchIn(viewModelScope)

        repository.saveState
            .onEach { save -> _state.update { it.copy(saveState = save) } }
            .launchIn(viewModelScope)
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    fun open(ref: StoreRef) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            repository.load(ref).fold(
                onSuccess = { note -> adopt(note, ref) },
                onFailure = { cause ->
                    _state.update {
                        it.copy(isLoading = false, error = cause.message ?: "Could not open the note")
                    }
                },
            )
        }
    }

    private fun adopt(note: Note, ref: StoreRef?) {
        history.clear()
        _state.update { current ->
            current.copy(
                note = note,
                ref = ref,
                view = note.meta.view,
                activeLayerId = note.sheet.defaultInkLayer()?.id,
                editingText = false,
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

    /** Forces a write; called when the editor leaves the foreground. */
    fun flush() {
        val current = _state.value
        val note = current.note ?: return
        val ref = current.ref ?: return
        viewModelScope.launch { repository.flush(ref, note) }
    }

    // ---- Tools ---------------------------------------------------------------------------------

    fun selectTool(tool: ToolId) {
        _state.update { current ->
            val spec = current.toolPresets.firstOrNull { it.tool == tool } ?: ToolSpec.defaultFor(tool)
            current.copy(
                tool = spec,
                textToolActive = false,
                // Leaving the lasso must drop the selection, or its handles linger over the sheet.
                selection = if (tool == ToolId.LASSO) current.selection else null,
            )
        }
    }

    /** The text tool: taps land in the text rather than laying down ink. A stylus still draws. */
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

    fun setFingerDrawing(enabled: Boolean) = settings.setFingerDrawing(enabled)

    /** Records a colour the user settled on, so it is one tap away next time. */
    fun rememberColor(argb: Int) = settings.addRecentColor(argb)

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

    /** Reported by the text layout once it knows how tall the flow turned out. */
    fun reportContentHeight(heightPt: Float) {
        val note = _state.value.note ?: return
        if (kotlin.math.abs(note.sheet.contentHeight - heightPt) < 0.5f) return
        // Not an undoable edit: it is a measurement, not something the user did.
        _state.update { it.copy(note = note.withSheet(note.sheet.copy(contentHeight = heightPt))) }
    }

    // ---- Text ----------------------------------------------------------------------------------

    fun beginTextEditing() {
        if (_state.value.isReadOnly) return
        _state.update { it.copy(editingText = true, selection = null) }
    }

    fun endTextEditing() = _state.update { it.copy(editingText = false) }

    fun updateText(markdown: String) {
        val note = _state.value.note ?: return
        if (note.readOnly || note.sheet.markdown == markdown) return
        commitEdit(Edit.SetText(note.sheet.markdown, markdown))
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

            commitEdit(Edit.ReplaceBlock(layer, layer.copy(strokes = layer.strokes + stroke)))
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

        fun factory(repository: NoteRepository, settings: SettingsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NoteViewModel(repository, settings) as T
            }
    }
}
