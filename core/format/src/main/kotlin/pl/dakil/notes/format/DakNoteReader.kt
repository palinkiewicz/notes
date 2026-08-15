package pl.dakil.notes.format

import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.Block
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonParseException
import pl.dakil.notes.model.json.JsonReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a `.daknote` container.
 *
 * The reader is deliberately total: content that fails to parse is skipped rather than failing the
 * whole document, and any entry it does not recognise is retained in [Note.foreignEntries] so the
 * writer can put it back. Losing a user's note to a strict parser is a far worse outcome than
 * opening one with a block the app cannot draw yet.
 */
object DakNoteReader {

    /** Refuses absurd inputs before allocating. 256 MB is far beyond any real note. */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    fun read(input: InputStream): Note {
        val entries = readEntries(input)

        val manifestBytes = entries[DakNote.ENTRY_MANIFEST]
            ?: throw DakNoteFormatException("Missing ${DakNote.ENTRY_MANIFEST}; not a .daknote file")

        val manifestJson = try {
            JsonReader.parseObject(manifestBytes.toString(Charsets.UTF_8))
        } catch (e: JsonParseException) {
            throw DakNoteFormatException("Corrupt ${DakNote.ENTRY_MANIFEST}", e)
        }

        val manifest = Schema.readManifest(manifestJson)
        val readOnly = manifest.minReaderVersion > DakNote.READER_VERSION

        val consumed = HashSet<String>()
        consumed += DakNote.ENTRY_MANIFEST
        consumed += DakNote.ENTRY_MIMETYPE

        val sheet = if (manifest.formatVersion >= 2) {
            readSheet(entries, consumed)
        } else {
            migrateV1(manifest, entries, consumed)
        }

        // A v1 file that declared an "infinite" page was really asking for a continuous view.
        val view = if (manifest.formatVersion < 2 && wasInfinite(manifest, entries)) {
            ViewMode.CONTINUOUS
        } else {
            manifest.meta.view
        }

        return Note(
            meta = manifest.meta.copy(view = view),
            sheet = sheet,
            readOnly = readOnly,
            foreignEntries = entries.filterKeys { it !in consumed },
        )
    }

    // ---- v2 ------------------------------------------------------------------------------------

    private fun readSheet(entries: Map<String, ByteArray>, consumed: MutableSet<String>): Sheet {
        val bytes = entries[DakNote.ENTRY_SHEET] ?: return Sheet()
        consumed += DakNote.ENTRY_SHEET

        val json = try {
            JsonReader.parseObject(bytes.toString(Charsets.UTF_8))
        } catch (_: JsonParseException) {
            // A corrupt sheet descriptor should not cost the user their text, which lives in its
            // own entry and can still be recovered.
            return Sheet(markdown = readMarkdown(entries, consumed))
        }

        val blocks = ArrayList<Block>()
        json.array("blocks")?.items?.forEach { item ->
            val blockJson = item as? JsonObject ?: return@forEach
            blocks += Schema.readBlock(blockJson) { src -> entries[src]?.also { consumed += src } }
        }

        return Sheet(
            format = Schema.readFormat(json),
            markdown = readMarkdown(entries, consumed),
            blocks = blocks,
            contentHeight = json.float("contentHeight", 0f),
            unknown = Schema.sheetRemainder(json),
        )
    }

    private fun readMarkdown(entries: Map<String, ByteArray>, consumed: MutableSet<String>): String {
        val bytes = entries[DakNote.ENTRY_MARKDOWN] ?: return ""
        consumed += DakNote.ENTRY_MARKDOWN
        return bytes.toString(Charsets.UTF_8)
    }

    // ---- v1 migration --------------------------------------------------------------------------

    /**
     * Flattens a v1 multi-page note into one continuous sheet.
     *
     * Each old page's blocks are shifted down by `pageIndex × pageHeight`, which is exactly the
     * strip coordinate they already occupied visually, and the per-page document text is
     * concatenated in page order to form the base Markdown flow. Nothing moves on screen; only the
     * coordinate origin changes.
     */
    private fun migrateV1(
        manifest: Schema.ManifestData,
        entries: Map<String, ByteArray>,
        consumed: MutableSet<String>,
    ): Sheet {
        var format = PageFormat.DEFAULT
        val blocks = ArrayList<Block>()
        val text = StringBuilder()
        var pageIndex = 0
        var maxBottom = 0f

        for (pageId in manifest.legacyPageOrder) {
            val entryName = "${DakNote.DIR_PAGES}$pageId.json"
            val bytes = entries[entryName] ?: continue
            consumed += entryName

            val json = try {
                JsonReader.parseObject(bytes.toString(Charsets.UTF_8))
            } catch (_: JsonParseException) {
                continue
            }

            if (pageIndex == 0) {
                format = PageFormat(
                    size = Schema.readPageSize(json.obj("size")),
                    background = Schema.readBackground(json.obj("background")),
                    margins = format.margins,
                )
            }

            val offsetY = pageIndex * format.height

            json.array("blocks")?.items?.forEach { item ->
                val blockJson = item as? JsonObject ?: return@forEach
                val block = Schema.readBlock(blockJson) { src -> entries[src]?.also { consumed += src } }

                // v1 document-flow text becomes part of the base Markdown flow; everything else
                // keeps its place on the paper, shifted into strip coordinates.
                if (block is TextBlock && Schema.isLegacyFlowText(blockJson)) {
                    if (block.markdown.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append("\n\n")
                        text.append(block.markdown)
                    }
                    return@forEach
                }

                val shifted = shiftDown(block, offsetY)
                blocks += shifted
                maxBottom = maxOf(maxBottom, shifted.worldBounds().bottom)
            }

            pageIndex++
        }

        return Sheet(
            format = format,
            markdown = text.toString(),
            blocks = blocks,
            contentHeight = maxOf(maxBottom, pageIndex * format.height),
        )
    }

    private fun shiftDown(block: Block, dy: Float): Block {
        if (dy == 0f) return block
        val rect = block.rect.let { it.copy(top = it.top + dy, bottom = it.bottom + dy) }
        return when (block) {
            is InkBlock -> block.copy(
                rect = rect,
                strokes = block.strokes.map { it.transformed(Affine.translate(0f, dy)) },
            )
            is TextBlock -> block.copy(rect = rect)
            is OpaqueBlock -> block.copy(rect = rect)
        }
    }

    private fun wasInfinite(manifest: Schema.ManifestData, entries: Map<String, ByteArray>): Boolean {
        val first = manifest.legacyPageOrder.firstOrNull() ?: return false
        val bytes = entries["${DakNote.DIR_PAGES}$first.json"] ?: return false
        return runCatching {
            JsonReader.parseObject(bytes.toString(Charsets.UTF_8))
                .obj("size")?.string("kind") == "infinite"
        }.getOrDefault(false)
    }

    // ---- Container -----------------------------------------------------------------------------

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val buffer = ByteArrayOutputStream(maxOf(entry.size.toInt(), 256))
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL_BYTES) {
                            throw DakNoteFormatException("Note exceeds the $MAX_TOTAL_BYTES byte limit")
                        }
                        buffer.write(chunk, 0, n)
                    }
                    out[entry.name] = buffer.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            throw DakNoteFormatException("Could not read the .daknote container", e)
        }
        if (out.isEmpty()) throw DakNoteFormatException("Empty or unreadable .daknote container")
        return out
    }
}
