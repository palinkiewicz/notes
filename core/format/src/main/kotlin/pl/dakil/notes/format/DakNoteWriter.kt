package pl.dakil.notes.format

import pl.dakil.notes.model.Block
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.OpaqueBlock
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
            writeDeflated(zip, DakNote.ENTRY_SHEET, jsonBytes(writeSheet(note.sheet, payloads)))
            written += DakNote.ENTRY_SHEET

            for ((name, bytes) in payloads) {
                if (written.add(name)) writeDeflated(zip, name, bytes)
            }

            // Everything this build did not understand, replayed byte for byte.
            for ((name, bytes) in note.foreignEntries.toSortedMap()) {
                if (written.add(name)) writeDeflated(zip, name, bytes)
            }
        }
    }

    fun toByteArray(note: Note): ByteArray {
        val buffer = ByteArrayOutputStream(16 * 1024)
        write(note, buffer)
        return buffer.toByteArray()
    }

    private fun writeSheet(sheet: Sheet, payloads: MutableMap<String, ByteArray>): JsonObject {
        val blocks = sheet.blocks.sortedBy { it.z }.map { block ->
            Schema.writeBlock(block) { b -> emitPayload(b, payloads) }
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
    private fun emitPayload(block: Block, payloads: MutableMap<String, ByteArray>): String? =
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
