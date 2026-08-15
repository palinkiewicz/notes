package pl.dakil.notes.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.Note
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DakNoteRoundTripTest {

    private val markdown =
        "# Eigenvalues\n\nA vector **v** is an eigenvector when...\n\n- [ ] revise\n"

    private fun sampleNote(): Note {
        val base = DakNote.newNote(title = "Linear Algebra — Week 3", now = 1_755_100_000_000L)
        val ink = base.sheet.inkLayers().single().copy(
            strokes = listOf(
                Stroke(
                    ToolId.PEN, 0xFF1B1B1F.toInt(), 2f, BlendId.NORMAL,
                    floatArrayOf(10f, 20f, 30f), floatArrayOf(10f, 25f, 15f),
                    widthFactors = floatArrayOf(0.3f, 0.8f, 0.5f),
                ),
                // Deliberately on the second page of the strip.
                Stroke(
                    ToolId.HIGHLIGHTER, 0x66FFE14D, 16f, BlendId.MULTIPLY,
                    floatArrayOf(40f, 120f), floatArrayOf(900f, 900f),
                ),
            ),
        )
        return base.copy(
            meta = base.meta.copy(tags = listOf("uni", "math"), view = ViewMode.CONTINUOUS),
            sheet = base.sheet.copy(
                markdown = markdown,
                blocks = listOf(ink),
                contentHeight = 1400f,
                format = base.sheet.format.copy(
                    background = PageBackground(
                        pattern = PagePattern(type = PatternType.RULED, spacing = 22f, margin = 64f),
                    ),
                    margins = PageMargins(60f, 50f, 40f, 70f),
                ),
            ),
        )
    }

    private fun roundTrip(note: Note): Note =
        DakNoteReader.read(ByteArrayInputStream(DakNoteWriter.toByteArray(note)))

    private fun entriesOf(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---- v2 round-trip ---------------------------------------------------------------------------

    @Test
    fun `text and ink survive together on one sheet`() {
        val restored = roundTrip(sampleNote())

        assertEquals("Linear Algebra — Week 3", restored.meta.title)
        assertEquals(listOf("uni", "math"), restored.meta.tags)
        assertEquals(ViewMode.CONTINUOUS, restored.meta.view)

        assertEquals(markdown, restored.sheet.markdown)
        assertEquals(PageSize.A4, restored.sheet.format.size)
        assertEquals(PageMargins(60f, 50f, 40f, 70f), restored.sheet.format.margins)
        assertEquals(PatternType.RULED, restored.sheet.format.background.pattern.type)

        val ink = restored.sheet.inkLayers().single()
        assertEquals(2, ink.strokes.size)
        assertEquals(BlendId.MULTIPLY, ink.strokes[1].blend)
        // Strip coordinates: the second stroke stays on page two.
        assertEquals(900f, ink.strokes[1].ys[0], 0.1f)
    }

    @Test
    fun `saving an unchanged note twice produces identical bytes`() {
        val first = DakNoteWriter.toByteArray(sampleNote())
        val second = DakNoteWriter.toByteArray(DakNoteReader.read(ByteArrayInputStream(first)))
        assertArrayEquals(first, second)
    }

    @Test
    fun `the container layout is the documented one`() {
        val names = entriesOf(DakNoteWriter.toByteArray(sampleNote())).keys.toList()
        assertEquals("mimetype", names.first())
        assertEquals("manifest.json", names[1])
        assertTrue(names.contains("content.md"))
        assertTrue(names.contains("sheet.json"))
        assertTrue(names.any { it.startsWith("ink/") && it.endsWith(".dsv") })
    }

    @Test
    fun `the note's text is a plain readable markdown file`() {
        // Recoverable with nothing but an unzip tool, and at the top level where it is obvious.
        val entries = entriesOf(DakNoteWriter.toByteArray(sampleNote()))
        assertEquals(markdown, entries["content.md"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `a drawing-only note has no markdown entry`() {
        val base = DakNote.newNote(now = 0L)
        val note = base.copy(
            sheet = base.sheet.withBlock(
                base.sheet.inkLayers().single().copy(
                    strokes = listOf(
                        Stroke(
                            ToolId.PEN, -1, 2f, BlendId.NORMAL,
                            floatArrayOf(1f, 2f), floatArrayOf(1f, 2f),
                        )
                    )
                )
            )
        )
        val entries = entriesOf(DakNoteWriter.toByteArray(note))
        assertFalse("content.md" in entries)
        assertEquals(1, roundTrip(note).sheet.inkLayers().single().strokes.size)
    }

    @Test
    fun `a text-only note round-trips with no ink`() {
        val base = DakNote.newNote(now = 0L)
        val note = base.copy(sheet = base.sheet.copy(markdown = "just words", blocks = emptyList()))
        val restored = roundTrip(note)
        assertEquals("just words", restored.sheet.markdown)
        assertTrue(restored.sheet.inkLayers().isEmpty())
    }

    // ---- Pagination ------------------------------------------------------------------------------

    @Test
    fun `page count follows content height, never below one`() {
        val sheet = DakNote.newNote(now = 0L).sheet.copy(blocks = emptyList())
        assertEquals(1, sheet.copy(contentHeight = 0f).pageCount())
        assertEquals(1, sheet.copy(contentHeight = 842f).pageCount())
        assertEquals(2, sheet.copy(contentHeight = 843f).pageCount())
        assertEquals(3, sheet.copy(contentHeight = 2000f).pageCount())
    }

    @Test
    fun `ink past the end of the text extends the page count`() {
        // A drawing on page four keeps page four, even with one line of text.
        val base = DakNote.newNote(now = 0L).sheet
        val far = base.inkLayers().single().copy(
            rect = Rect(0f, 2600f, 595f, 2900f),
            strokes = listOf(
                Stroke(
                    ToolId.PEN, -1, 2f, BlendId.NORMAL,
                    floatArrayOf(10f, 20f), floatArrayOf(2700f, 2800f),
                )
            ),
        )
        assertEquals(4, base.copy(contentHeight = 100f, blocks = listOf(far)).pageCount())
    }

    @Test
    fun `strip coordinates map to page indices`() {
        val sheet = DakNote.newNote(now = 0L).sheet
        assertEquals(0, sheet.pageAt(0f))
        assertEquals(0, sheet.pageAt(841f))
        assertEquals(1, sheet.pageAt(842f))
        assertEquals(2, sheet.pageAt(1700f))
    }

    // ---- v1 migration ----------------------------------------------------------------------------

    /** Builds a genuine v1 file: two pages, each with its own flow text and ink. */
    private fun legacyV1(): LinkedHashMap<String, ByteArray> {
        val manifest = """
            {"formatVersion":1,"minReaderVersion":1,"id":"old-note","revision":4,
             "created":1,"modified":2,"title":"Legacy","tags":["old"],
             "defaultMode":"canvas","pages":["p0","p1"]}
        """.trimIndent()

        fun page(id: String) = """
            {"id":"$id","size":{"kind":"A4"},
             "background":{"color":"#FFFFFFFF","darkColor":"#FF000000",
                           "pattern":{"type":"none","spacing":24,"color":"#FF5B7FD4",
                                      "opacity":0.35,"margin":0,"groupSpacing":0}},
             "blocks":[
               {"id":"b0","type":"ink","z":0,"rect":[0,0,595,842],
                "transform":[1,0,0,1,0,0],"src":"ink/$id-b0.dsv","name":"Layer 1",
                "visible":true,"locked":false},
               {"id":"b1","type":"text","z":1,"rect":[48,48,547,794],
                "transform":[1,0,0,1,0,0],"src":"text/$id-b1.md","flow":"document"}
             ]}
        """.trimIndent()

        val ink = StrokeCodec.encode(
            listOf(
                Stroke(
                    ToolId.PEN, 0xFF112233.toInt(), 2f, BlendId.NORMAL,
                    floatArrayOf(100f, 200f), floatArrayOf(100f, 150f),
                )
            )
        )

        return linkedMapOf(
            "mimetype" to DakNote.MIME_TYPE.toByteArray(),
            "manifest.json" to manifest.toByteArray(),
            "pages/p0.json" to page("p0").toByteArray(),
            "pages/p1.json" to page("p1").toByteArray(),
            "text/p0-b1.md" to "# Page one".toByteArray(),
            "text/p1-b1.md" to "# Page two".toByteArray(),
            "ink/p0-b0.dsv" to ink,
            "ink/p1-b0.dsv" to ink,
            "assets/legacy.bin" to byteArrayOf(9, 8, 7),
        )
    }

    @Test
    fun `a v1 note flattens into one continuous sheet`() {
        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(legacyV1())))

        assertEquals("Legacy", note.meta.title)
        assertEquals(listOf("old"), note.meta.tags)

        // Both pages' document text becomes one flow, in page order.
        assertEquals("# Page one\n\n# Page two", note.sheet.markdown)

        // Page two's ink is shifted into strip coordinates rather than overlapping page one's.
        val ys = note.sheet.inkLayers()
            .flatMap { layer -> layer.strokes.flatMap { it.ys.toList() } }
            .sorted()
        assertEquals(4, ys.size)
        assertEquals(100f, ys[0], 0.5f)
        assertEquals(150f, ys[1], 0.5f)
        assertEquals(942f, ys[2], 0.5f)   // 842 + 100
        assertEquals(992f, ys[3], 0.5f)   // 842 + 150
    }

    @Test
    fun `a migrated v1 note saves as v2 and keeps its foreign entries`() {
        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(legacyV1())))
        assertTrue("assets/legacy.bin" in note.foreignEntries)

        val entries = entriesOf(DakNoteWriter.toByteArray(note))
        val manifest = JsonReader.parseObject(entries["manifest.json"]!!.toString(Charsets.UTF_8))
        assertEquals(2, manifest.int("formatVersion"))
        assertEquals("old-note", manifest.string("id"))
        assertTrue("sheet.json" in entries)
        assertFalse(entries.keys.any { it.startsWith("pages/") })
        assertArrayEquals(byteArrayOf(9, 8, 7), entries["assets/legacy.bin"])
    }

    @Test
    fun `a v1 infinite page opens in continuous view`() {
        // "Infinite" used to be a page size; it is a way of looking at the sheet now, so a file
        // that asked for it must still look the way its author left it.
        val entries = legacyV1()
        entries["pages/p0.json"] = entries["pages/p0.json"]!!
            .toString(Charsets.UTF_8)
            .replace("""{"kind":"A4"}""", """{"kind":"infinite"}""")
            .toByteArray()

        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
        assertEquals(ViewMode.CONTINUOUS, note.meta.view)
        assertEquals(PageSize.A4, note.sheet.format.size)
    }

    // ---- Pages ------------------------------------------------------------------------------------

    @Test
    fun `explicitly added blank pages survive a save`() {
        // The whole point of storing this: blank pages have nothing on them to imply they exist,
        // so if the number is not written down they are gone on reopen.
        val note = sampleNote().let { it.withSheet(it.sheet.withPages(5)) }
        assertEquals(5, roundTrip(note).sheet.pageCount())
    }

    @Test
    fun `a note written before pages existed opens with the count its content implies`() {
        val entries = entriesOf(DakNoteWriter.toByteArray(sampleNote()))
        val sheet = JsonReader.parseObject(entries["sheet.json"]!!.toString(Charsets.UTF_8))
        entries["sheet.json"] = JsonWriter.write(sheet.without("pages")).toByteArray()

        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
        assertEquals(note.sheet.contentPageCount(), note.sheet.pageCount())
    }

    @Test
    fun `pages grown by content are not lost on the next save`() {
        val note = sampleNote().let {
            it.withSheet(it.sheet.copy(contentHeight = it.sheet.format.height * 2.5f))
        }
        assertEquals(3, roundTrip(note).sheet.pageCount())
    }

    // ---- Forward compatibility -------------------------------------------------------------------

    private fun futureNote(minReaderVersion: Int = 2): ByteArray {
        val entries = entriesOf(DakNoteWriter.toByteArray(sampleNote()))

        val manifest = JsonReader.parseObject(entries["manifest.json"]!!.toString(Charsets.UTF_8))
            .with("minReaderVersion", minReaderVersion)
            .with("audioTrackCount", 3)
        entries["manifest.json"] = JsonWriter.write(manifest).toByteArray(Charsets.UTF_8)

        val sheet = JsonReader.parseObject(entries["sheet.json"]!!.toString(Charsets.UTF_8))
        val chart = JsonReader.parseObject(
            """{"id":"b9","type":"chart","z":5,"rect":[48,320,499,520],
               "transform":[1,0,0,1,0,0],"src":"ext/b9.json","dataset":"quarterly","live":true}"""
        )
        val patched = (sheet.array("blocks")!!.items + chart).map { item ->
            val o = item as JsonObject
            if (o.string("type") == "ink") o.with("blurRadius", 4) else o
        }
        entries["sheet.json"] = JsonWriter.write(
            sheet.with("blocks", JsonArray(patched)).with("gridSnap", 8)
        ).toByteArray(Charsets.UTF_8)

        entries["ext/b9.json"] = """{"series":[1,2,3]}""".toByteArray(Charsets.UTF_8)
        entries["assets/recording-1.opus"] = byteArrayOf(1, 2, 3, 4, 5)
        return zipOf(entries)
    }

    @Test
    fun `an unknown block type is preserved through an edit`() {
        val note = DakNoteReader.read(ByteArrayInputStream(futureNote()))
        val opaque = note.sheet.blocks.filterIsInstance<OpaqueBlock>().single()
        assertEquals("chart", opaque.type)
        assertEquals("quarterly", opaque.raw.string("dataset"))

        val edited = note.withSheet(note.sheet.copy(markdown = "changed"))
        val survivor = roundTrip(edited).sheet.blocks.filterIsInstance<OpaqueBlock>().single()
        assertEquals("chart", survivor.type)
        assertTrue(survivor.raw.bool("live"))
        assertArrayEquals("""{"series":[1,2,3]}""".toByteArray(), survivor.payload)
    }

    @Test
    fun `unknown keys on the sheet and on known blocks are preserved`() {
        val restored = roundTrip(DakNoteReader.read(ByteArrayInputStream(futureNote())))
        assertEquals(3, restored.meta.unknown.int("audioTrackCount"))
        assertEquals(8, restored.sheet.unknown.int("gridSnap"))
        assertEquals(4, restored.sheet.inkLayers().single().unknown.int("blurRadius"))
    }

    @Test
    fun `unrecognised container entries are replayed byte for byte`() {
        val note = DakNoteReader.read(ByteArrayInputStream(futureNote()))
        assertTrue("assets/recording-1.opus" in note.foreignEntries)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5),
            roundTrip(note).foreignEntries["assets/recording-1.opus"],
        )
    }

    @Test
    fun `a file needing a newer reader opens read-only and refuses to save`() {
        val note = DakNoteReader.read(ByteArrayInputStream(futureNote(minReaderVersion = 99)))
        assertTrue(note.readOnly)
        assertTrue(
            runCatching { DakNoteWriter.toByteArray(note) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `a same-version file is editable`() {
        assertFalse(
            DakNoteReader.read(ByteArrayInputStream(futureNote(minReaderVersion = 2))).readOnly
        )
    }

    // ---- Robustness ------------------------------------------------------------------------------

    @Test
    fun `a corrupt sheet descriptor still yields the text`() {
        // The words are the irreplaceable part; they live in their own entry for exactly this reason.
        val entries = entriesOf(DakNoteWriter.toByteArray(sampleNote()))
        entries["sheet.json"] = "{ this is not json".toByteArray()

        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
        assertEquals(markdown, note.sheet.markdown)
        assertEquals("Linear Algebra — Week 3", note.meta.title)
    }

    @Test
    fun `a non-daknote zip is rejected clearly`() {
        val bytes = zipOf(mapOf("hello.txt" to "hi".toByteArray()))
        assertTrue(
            runCatching { DakNoteReader.read(ByteArrayInputStream(bytes)) }
                .exceptionOrNull() is DakNoteFormatException
        )
    }

    @Test
    fun `a pinned text label keeps its identity and geometry`() {
        val note = sampleNote()
        val label = TextBlock(
            id = BlockId("b7"), z = 3,
            rect = Rect(12f, 934f, 200f, 1000f),
            transform = Affine(1f, 0f, 0f, 1f, 5f, 7f),
            markdown = "a pinned label",
        )
        val back = roundTrip(note.withSheet(note.sheet.withBlock(label)))
            .sheet.blocks.filterIsInstance<TextBlock>().single()
        assertEquals(Rect(12f, 934f, 200f, 1000f), back.rect)
        assertEquals(Affine(1f, 0f, 0f, 1f, 5f, 7f), back.transform)
        assertEquals("a pinned label", back.markdown)
    }

    @Test
    fun `a custom page size round-trips`() {
        val note = DakNote.newNote(size = PageSize.Custom(400f, 900f), now = 0L)
        assertEquals(PageSize.Custom(400f, 900f), roundTrip(note).sheet.format.size)
    }

    @Test
    fun `every configurable colour survives a round-trip`() {
        val note = sampleNote()
        val customised = note.sheet.copy(
            format = note.sheet.format.copy(
                background = PageBackground(
                    color = 0xFFEDE7D9.toInt(),
                    darkColor = 0xFF0B0F14.toInt(),
                    adaptPatternToDark = false,
                    pattern = PagePattern(
                        type = PatternType.RULED,
                        spacing = 19f,
                        color = 0x99A3C77E.toInt(),
                        opacity = 0.62f,
                        margin = 72f,
                        marginColor = 0xFF2E86C1.toInt(),
                    ),
                ),
            ),
        )
        val bg = roundTrip(note.withSheet(customised)).sheet.format.background

        assertEquals(0xFFEDE7D9.toInt(), bg.color)
        assertEquals(0xFF0B0F14.toInt(), bg.darkColor)
        assertFalse(bg.adaptPatternToDark)
        assertEquals(0x99A3C77E.toInt(), bg.pattern.color)
        assertEquals(0xFF2E86C1.toInt(), bg.pattern.marginColor)
        assertEquals(0.62f, bg.pattern.opacity, 1e-3f)
    }

    @Test
    fun `translucent line colours keep their alpha`() {
        val note = sampleNote()
        val faint = note.sheet.copy(
            format = note.sheet.format.copy(
                background = note.sheet.format.background.copy(
                    pattern = note.sheet.format.background.pattern.copy(color = 0x3300FF00),
                ),
            ),
        )
        assertEquals(
            0x3300FF00,
            roundTrip(note.withSheet(faint)).sheet.format.background.pattern.color,
        )
    }

    @Test
    fun `an empty note is valid`() {
        val restored = roundTrip(DakNote.newNote(now = 0L))
        assertEquals("", restored.sheet.markdown)
        assertEquals(1, restored.sheet.pageCount())
        assertNotNull(restored.sheet.defaultInkLayer())
    }
}
