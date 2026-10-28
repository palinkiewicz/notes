package pl.dakil.notes.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.dakil.notes.model.InMemoryMedia
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PdfPageBlock
import pl.dakil.notes.model.PdfSourcePage
import pl.dakil.notes.model.json.JsonString
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PdfBlockFormatTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Not a real PDF: the format layer never parses one, and these bytes are easy to spot.
    private val pdf = ByteArray(5000) { (it * 13).toByte() }
    private val src = DakNote.pdfEntry("0123456789abcdef")

    private fun pdfNote(pages: Int = 3, bytes: ByteArray = pdf): Note = DakNote.newPdfNote(
        title = "Paper",
        src = src,
        data = InMemoryMedia(bytes),
        pages = List(pages) { PdfSourcePage(it, 595.276f, 841.89f) },
        now = 1_755_100_000_000L,
    )

    private fun entries(bytes: ByteArray): List<ZipEntry> {
        val out = ArrayList<ZipEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                out += zip.nextEntry ?: break
                zip.closeEntry()
            }
        }
        return out
    }

    private fun pdfBlocks(note: Note) = note.sheet.blocks.filterIsInstance<PdfPageBlock>()

    @Test
    fun `a note made from a PDF has one page per PDF page on paper the size of the first`() {
        val note = pdfNote(pages = 3)
        assertEquals(3, note.sheet.pageCount())
        assertEquals(PageSize.Custom(595.276f, 841.89f), note.sheet.format.size)
        assertEquals(listOf(0, 1, 2), pdfBlocks(note).map { it.page })
        assertEquals(listOf(0, 1, 2), pdfBlocks(note).map { note.sheet.pageOf(it) })
    }

    @Test
    fun `every page of one PDF shares a single stored entry, written last`() {
        // Last so a note's layout is all a reader meets before the megabytes, and once because the
        // entry is named by content: three pages of one file are one file.
        val all = entries(DakNoteWriter.toByteArray(pdfNote(pages = 3)))
        val media = all.filter { it.name.startsWith(DakNote.DIR_MEDIA) }
        assertEquals(listOf(src), media.map { it.name })
        assertEquals(ZipEntry.STORED, media.single().method)
        assertEquals(src, all.last().name)
    }

    @Test
    fun `a PDF page survives a save with its page index and exact bytes`() {
        val back = DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(pdfNote())))
        val blocks = pdfBlocks(back)
        assertEquals(listOf(0, 1, 2), blocks.map { it.page })
        assertArrayEquals(pdf, blocks.first().data.open().use { it.readBytes() })
    }

    @Test
    fun `saving an unchanged PDF note twice produces identical bytes, through the spool too`() {
        val first = DakNoteWriter.toByteArray(pdfNote())
        val spool = FileMediaSpool(temp.newFolder("media"))
        val second = DakNoteWriter.toByteArray(DakNoteReader.read(ByteArrayInputStream(first), spool))
        val third = DakNoteWriter.toByteArray(DakNoteReader.read(ByteArrayInputStream(second)))
        assertArrayEquals(first, second)
        assertArrayEquals(first, third)
    }

    @Test
    fun `unknown keys on a PDF block keep their order across a save`() {
        val note = pdfNote(pages = 1)
        val block = pdfBlocks(note).single()
        val marked = block.copy(unknown = block.unknown.with("future", JsonString("kept")).with("also", 2))
        val sheet = note.sheet.withBlock(marked)
        val back = DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(note.copy(sheet = sheet))))
        assertEquals(listOf("future", "also"), pdfBlocks(back).single().unknown.keys.toList())
    }

    @Test
    fun `a build that does not know PDF pages carries them through a save untouched`() {
        // What today's release holds for a "pdf" block: raw JSON and the payload as bytes. Written
        // back, it must still read as the same PDF page — which is why there is no format bump.
        val current = DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(pdfNote(pages = 2))))
        val asOlderBuild = current.copy(
            sheet = current.sheet.copy(
                blocks = current.sheet.blocks.map { block ->
                    if (block !is PdfPageBlock) block else OpaqueBlock(
                        id = block.id, z = block.z, rect = block.rect, transform = block.transform,
                        type = "pdf", raw = Schema.writeBlock(block) { block.src },
                        payloadEntry = block.src, payload = block.data.open().use { it.readBytes() },
                    )
                },
            ),
        )
        val back = DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(asOlderBuild)))
        assertEquals(listOf(0, 1), pdfBlocks(back).map { it.page })
        assertArrayEquals(pdf, pdfBlocks(back).first().data.open().use { it.readBytes() })
    }

    @Test
    fun `a PDF page whose file is missing stays opaque rather than becoming a blank page`() {
        val block = pdfBlocks(pdfNote(pages = 1)).single()
        val read = Schema.readBlock(Schema.writeBlock(block) { block.src }, resolve = { null })
        assertTrue(read is OpaqueBlock)
        assertEquals("pdf", (read as OpaqueBlock).type)
    }

    @Test
    fun `a note read past its PDF cannot be written, so the scan can never save it blank`() {
        val note = DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(pdfNote())), MediaSink.Discard)
        assertEquals(3, pdfBlocks(note).size)
        assertFalse(runCatching { DakNoteWriter.toByteArray(note) }.isSuccess)
    }

    @Test
    fun `a spooled PDF lives in a file, and a second read of the same note copies nothing`() {
        val dir = temp.newFolder("media")
        val bytes = DakNoteWriter.toByteArray(pdfNote())
        val first = pdfBlocks(DakNoteReader.read(ByteArrayInputStream(bytes), FileMediaSpool(dir))).first()
        val file = first.data.file
        assertNotNull(file)
        file!!
        file.setLastModified(0L)
        val again = pdfBlocks(DakNoteReader.read(ByteArrayInputStream(bytes), FileMediaSpool(dir))).first()
        assertEquals(file, again.data.file)
        assertEquals(1, dir.listFiles()!!.size)
        // Touched rather than rewritten, so the trim at start-up can tell it is still wanted.
        assertTrue(file.lastModified() > 0L)
    }

    @Test
    fun `a spooled file that no longer matches its checksum fails the save instead of writing it`() {
        val dir = temp.newFolder("media")
        val note = DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(pdfNote())), FileMediaSpool(dir))
        val file = pdfBlocks(note).first().data.file!!
        file.writeBytes(ByteArray(pdf.size))
        assertFalse(runCatching { DakNoteWriter.toByteArray(note) }.isSuccess)
    }

    @Test
    fun `trimming the spool drops stale files and unfinished copies and keeps the rest`() {
        val dir = temp.newFolder("media")
        val spool = FileMediaSpool(dir)
        val kept = spool.store(DakNote.pdfEntry("aaaa"), ByteArrayInputStream(pdf)).file!!
        val stale = spool.store(DakNote.pdfEntry("bbbb"), ByteArrayInputStream(ByteArray(10))).file!!
        val partial = java.io.File(dir, "spool-123.part").apply { writeBytes(ByteArray(3)) }
        val now = 10_000_000_000L
        kept.setLastModified(now)
        stale.setLastModified(now - 20L * 24 * 3600 * 1000)
        partial.setLastModified(now)
        spool.trim(now, maxAgeMs = 14L * 24 * 3600 * 1000, maxBytes = Long.MAX_VALUE)
        assertEquals(listOf(kept.name), dir.listFiles()!!.map { it.name })
    }
}
