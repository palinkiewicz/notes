package pl.dakil.notes.format

import pl.dakil.notes.model.Block
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.DiscardedMedia
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.MediaData
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.PdfPageBlock
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a `.daknote` container.
 *
 * Entry order and JSON formatting are both deterministic, so saving an unchanged note twice
 * produces identical bytes. That is a hard requirement for the file-based sync this format is
 * designed around — a note that rewrites itself differently on every save turns every sync into a
 * conflict.
 */
object DakNoteWriter {

    /** ZIP timestamps default to "now", which would break byte-stability. Pin them instead. */
    private const val FIXED_TIME = 946_684_800_000L // 2000-01-01T00:00:00Z

    fun write(note: Note, out: OutputStream) {
        require(!note.readOnly) {
            "Refusing to write a note opened read-only: it declares a minReaderVersion this build " +
                "does not implement, so a save could drop content it cannot represent."
        }
        require(note.sheet.blocks.none { it is PdfPageBlock && it.data is DiscardedMedia }) {
            "Refusing to write a note whose PDF was skipped on read: its bytes are not here to write."
        }

        val written = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            // Stored, uncompressed, first: makes the container type identifiable from its first
            // bytes without inflating anything, the same trick ODF and EPUB use.
            writeStored(zip, DakNote.ENTRY_MIMETYPE, DakNote.MIME_TYPE.toByteArray(Charsets.UTF_8))
            written += DakNote.ENTRY_MIMETYPE

            val manifest = Schema.writeManifest(note.meta)
            writeDeflated(zip, DakNote.ENTRY_MANIFEST, jsonBytes(manifest))
            written += DakNote.ENTRY_MANIFEST

            // The note's text as plain Markdown, at the top level of the archive: a user must be
            // able to recover their words with nothing but an unzip tool. Derived from the boxes
            // rather than stored, so there is exactly one copy of the text in the document and no
            // way for the two to drift apart. Reading order is fixed, so this stays byte-stable.
            val text = note.sheet.textInReadingOrder()
            if (text.isNotEmpty()) {
                writeDeflated(zip, DakNote.ENTRY_MARKDOWN, text.toByteArray(Charsets.UTF_8))
                written += DakNote.ENTRY_MARKDOWN
            }

            val payloads = LinkedHashMap<String, ByteArray>()
            val media = LinkedHashMap<String, MediaData>()
            writeDeflated(zip, DakNote.ENTRY_SHEET, jsonBytes(writeSheet(note.sheet, payloads, media)))
            written += DakNote.ENTRY_SHEET

            for ((name, bytes) in payloads) {
                if (!written.add(name)) continue
                // A picture is already compressed; deflating it again costs time on every save and
                // saves nothing.
                if (name.startsWith(DakNote.DIR_MEDIA)) writeStored(zip, name, bytes) else writeDeflated(zip, name, bytes)
            }

            // Everything this build did not understand, replayed byte for byte.
            for ((name, bytes) in note.foreignEntries.toSortedMap()) {
                if (written.add(name)) writeDeflated(zip, name, bytes)
            }

            // Last, after everything a reader needs to lay the note out: a PDF is the one entry that
            // can be megabytes, and a note without one is byte-identical to what it was before PDFs.
            for ((name, data) in media) {
                if (written.add(name)) writeStored(zip, name, data)
            }
        }
    }

    fun toByteArray(note: Note): ByteArray {
        val buffer = ByteArrayOutputStream(16 * 1024)
        write(note, buffer)
        return buffer.toByteArray()
    }

    private fun writeSheet(
        sheet: Sheet,
        payloads: MutableMap<String, ByteArray>,
        media: MutableMap<String, MediaData>,
    ): JsonObject {
        val blocks = sheet.blocks.sortedBy { it.z }.map { block ->
            Schema.writeBlock(block) { b -> emitPayload(b, payloads, media) }
        }
        return JsonObject.of(
            "size" to Schema.writePageSize(sheet.format.size),
            "margins" to Schema.writeMargins(sheet.format.margins),
            "background" to Schema.writeBackground(sheet.format.background),
            "contentHeight" to pl.dakil.notes.model.json.JsonNumber.of(sheet.contentHeight),
            "pages" to pl.dakil.notes.model.json.JsonNumber.of(sheet.pageCount()),
            "blocks" to JsonArray(blocks),
        ).withDefaults(sheet.unknown)
    }

    /**
     * Assigns a payload entry name and records its bytes. Known block types get canonical names so
     * a note's layout is predictable; opaque blocks keep whatever entry they arrived under, since
     * the app that wrote them may depend on it.
     */
    private fun emitPayload(
        block: Block,
        payloads: MutableMap<String, ByteArray>,
        media: MutableMap<String, MediaData>,
    ): String? =
        when (block) {
            is InkBlock -> {
                if (block.strokes.isEmpty()) null
                else DakNote.inkEntry(block.id).also {
                    payloads[it] = StrokeCodec.encode(block.strokes)
                }
            }

            is TextBlock -> {
                if (block.markdown.isEmpty()) null
                else DakNote.textEntry(block.id).also {
                    payloads[it] = block.markdown.toByteArray(Charsets.UTF_8)
                }
            }

            // Under the name it arrived with: it is derived from the content, so it is already
            // canonical, and two blocks showing one picture share the entry.
            is ImageBlock -> block.src.also { payloads[it] = block.bytes }

            // Likewise by content, and every page of one PDF names the same entry: one copy.
            is PdfPageBlock -> block.src.also { media[it] = block.data }

            is OpaqueBlock -> block.payloadEntry?.also { name ->
                block.payload?.let { payloads[name] = it }
            }
        }

    private fun jsonBytes(json: JsonObject): ByteArray =
        JsonWriter.write(json, pretty = true).toByteArray(Charsets.UTF_8)

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
            time = FIXED_TIME
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Streams [data] in as a STORED entry. Its size and checksum are declared up front, as STORED
     * requires, and the ZIP stream checks the bytes against them as they pass: a spooled file that
     * no longer matches fails the save rather than writing a note whose PDF is not the one it names.
     */
    private fun writeStored(zip: ZipOutputStream, name: String, data: MediaData) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size
            compressedSize = data.size
            crc = data.crc32
            time = FIXED_TIME
        }
        zip.putNextEntry(entry)
        data.open().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    private fun writeDeflated(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = FIXED_TIME
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}
