package pl.dakil.notes.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.Rect
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ImageBlockFormatTest {

    // Not a real PNG: the format layer never decodes a picture, so any bytes will do, and these are
    // easy to recognise in a failure message.
    private val pixels = ByteArray(300) { (it * 7).toByte() }

    private fun image(id: String, rect: Rect = Rect(60f, 80f, 300f, 260f)) = ImageBlock(
        id = BlockId(id),
        z = 5,
        rect = rect,
        src = DakNote.mediaEntry("0123456789abcdef.png"),
        mimeType = "image/png",
        bytes = pixels,
    )

    private fun noteWith(vararg images: ImageBlock): Note {
        val base = DakNote.newNote(title = "Pictures", now = 1_755_100_000_000L)
        return base.copy(sheet = base.sheet.copy(blocks = base.sheet.blocks + images))
    }

    private fun roundTrip(note: Note): Note =
        DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(note)))

    private fun entries(bytes: ByteArray): Map<String, ZipEntry> {
        val out = LinkedHashMap<String, ZipEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = e
                zip.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `an image block keeps its place and its exact bytes`() {
        val restored = roundTrip(noteWith(image("p1"))).sheet.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(Rect(60f, 80f, 300f, 260f), restored.rect)
        assertEquals("image/png", restored.mimeType)
        assertArrayEquals(pixels, restored.bytes)
    }

    @Test
    fun `saving an unchanged note with a picture twice produces identical bytes`() {
        val first = DakNoteWriter.toByteArray(noteWith(image("p1")))
        val second = DakNoteWriter.toByteArray(DakNoteReader.read(ByteArrayInputStream(first)))
        assertArrayEquals(first, second)
    }

    @Test
    fun `a picture is stored uncompressed, once, however many blocks show it`() {
        // Deflating an already-compressed image buys nothing and costs every save; and the entry is
        // named by content, so two copies of one picture on a page are one entry in the file.
        val bytes = DakNoteWriter.toByteArray(noteWith(image("p1"), image("p2", Rect(0f, 400f, 100f, 500f))))
        val media = entries(bytes).filterKeys { it.startsWith(DakNote.DIR_MEDIA) }
        assertEquals(1, media.size)
        assertEquals(ZipEntry.STORED, media.values.single().method)
        assertEquals(2, DakNoteReader.read(ByteArrayInputStream(bytes)).sheet.blocks.filterIsInstance<ImageBlock>().size)
    }

    @Test
    fun `a build that does not know images carries one through a save untouched`() {
        // What an older reader holds: the descriptor as raw JSON and the payload as bytes. Writing
        // that back must leave a file this build still reads as the same picture — which is why the
        // block type could be added without a format bump.
        val written = DakNoteWriter.toByteArray(noteWith(image("p1")))
        val current = DakNoteReader.read(ByteArrayInputStream(written))
        val picture = current.sheet.blocks.filterIsInstance<ImageBlock>().single()
        val descriptor = Schema.writeBlock(picture) { picture.src }
        val asOlderBuild = current.copy(
            sheet = current.sheet.copy(
                blocks = current.sheet.blocks.map { block ->
                    if (block !is ImageBlock) block else OpaqueBlock(
                        id = block.id, z = block.z, rect = block.rect, transform = block.transform,
                        type = "image", raw = descriptor, payloadEntry = block.src, payload = block.bytes,
                    )
                },
            ),
        )

        val back = roundTrip(asOlderBuild).sheet.blocks.filterIsInstance<ImageBlock>().single()
        assertArrayEquals(pixels, back.bytes)
        assertEquals(Rect(60f, 80f, 300f, 260f), back.rect)
    }

    @Test
    fun `an image whose payload is missing stays opaque rather than becoming an empty picture`() {
        // Kept as it was found, so a save cannot turn a damaged file into one that has lost even
        // the record of where the picture was.
        val descriptor = Schema.writeBlock(image("p1")) { DakNote.mediaEntry("missing.png") }
        val block = Schema.readBlock(descriptor) { null }
        assertTrue(block is OpaqueBlock)
        assertEquals("image", (block as OpaqueBlock).type)
    }

    @Test
    fun `a colour filter and a turn survive a save, and the pixels stay the original`() {
        val tinted = image("p1").copy(
            tint = 0xFFC0392B.toInt(),
            transform = pl.dakil.notes.model.Affine.rotate(0.5f, 60f, 80f),
        )
        val back = roundTrip(noteWith(tinted)).sheet.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(0xFFC0392B.toInt(), back.tint)
        assertEquals(0.5f, kotlin.math.atan2(back.transform.b, back.transform.a), 1e-4f)
        assertArrayEquals(pixels, back.bytes)
    }

    @Test
    fun `an untinted picture's descriptor does not mention a tint`() {
        assertEquals(null, Schema.writeBlock(image("p1")) { "x" }.get("tint"))
    }

    @Test
    fun `a stretched and mirrored picture keeps its whole matrix across a save`() {
        // A picture can be sheared now — turned, then stretched along the page — and the block's
        // transform is a full matrix in the file already, so nothing new has to be written for it.
        val skewed = image("p1").copy(transform = pl.dakil.notes.model.Affine(-1f, 0.2f, 0.3f, 1f, 50f, 7f))
        val back = roundTrip(noteWith(skewed)).sheet.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(skewed.transform, back.transform)
    }
}
