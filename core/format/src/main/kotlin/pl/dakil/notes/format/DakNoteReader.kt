package pl.dakil.notes.format

import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.Block
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.Rect
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

        val sheet = hydrateFlowText(
            if (manifest.formatVersion >= 2) {
                readSheet(entries, consumed)
            } else {
                migrateV1(manifest, entries, consumed)
            }
        )

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
            // Absent in files written before explicit pages existed; one is the old behaviour.
            pages = json.int("pages", 1).coerceAtLeast(1),
            unknown = Schema.sheetRemainder(json),
        )
    }

    /**
     * Turns a v2 document flow into the text box that replaced it.
     *
     * The box is laid over the text column of the first page and made as deep as the flow was, so
     * the words come back on screen exactly where the file last showed them.
     *
     * The guard is the important half. From v3 on, `content.md` is *derived* from the boxes on
     * save, so a file that has boxes already has its text in them — hydrating that entry as well
     * would add a second copy of the whole note on every open. Testing for boxes rather than for a
     * version number also covers the file some other tool wrote, and makes migrating twice a fixed
     * point rather than a way to lose a note to duplication.
     */
    private fun hydrateFlowText(sheet: Sheet): Sheet {
        if (sheet.markdown.isEmpty()) return sheet
        if (sheet.blocks.any { it is TextBlock }) return sheet.copy(markdown = "")

        val format = sheet.format
        val box = TextBlock(
            id = sheet.nextBlockId(),
            // Under the ink, which is where the flow was drawn: strokes were laid over the words.
            z = (sheet.blocks.minOfOrNull { it.z } ?: 0) - 1,
            rect = Rect(
                format.contentLeft,
                format.margins.top,
                format.contentRight,
                maxOf(sheet.contentHeight, format.margins.top + format.usableHeight),
            ),
            markdown = sheet.markdown,
        )
        // The cached flow height goes with the flow: the box states its own extent in its rect, and
        // a leftover floor would hold pages open after the box it was measured from is deleted.
        return sheet.copy(markdown = "", blocks = sheet.blocks + box, contentHeight = 0f)
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
     * strip coordinate they already occupied visually. Nothing moves on screen; only the coordinate
     * origin changes.
     *
     * v1's per-page document text stays one box per page, standing in that page's text column. It
     * used to be concatenated into the single flow v2 had, which was the only thing v2 could
     * represent — and it moved every page's words onto page one. A box goes where its page is, so
     * the round trip is now faithful.
     */
    private fun migrateV1(
        manifest: Schema.ManifestData,
        entries: Map<String, ByteArray>,
        consumed: MutableSet<String>,
    ): Sheet {
        var format = PageFormat.DEFAULT
        val blocks = ArrayList<Block>()
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

                // v1 document-flow text filled its page's text column, and the file usually says
                // so in the block's own rect. Only when it does not — a flow block was allowed to
                // leave its geometry implicit — is the column filled in here. Everything else
                // already knows where it is and only needs shifting into strip coordinates.
                val placed = if (
                    block is TextBlock && Schema.isLegacyFlowText(blockJson) && block.rect.isEmpty
                ) {
                    block.copy(rect = textColumnOf(format))
                } else {
                    block
                }

                val shifted = shiftDown(placed, offsetY)
                blocks += shifted
                maxBottom = maxOf(maxBottom, shifted.worldBounds().bottom)
            }

            pageIndex++
        }

        return Sheet(
            format = format,
            blocks = blocks,
            contentHeight = maxOf(maxBottom, pageIndex * format.height),
        )
    }

    /** The band a page's text occupied: the full column, between the margins. */
    private fun textColumnOf(format: PageFormat): Rect = Rect(
        format.contentLeft,
        format.margins.top,
        format.contentRight,
        format.height - format.margins.bottom,
    )

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
