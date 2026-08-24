package pl.dakil.notes.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import pl.dakil.notes.model.ShapeSpec
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
        val text = TextBlock(
            id = BlockId("b1"), z = 1,
            rect = Rect(60f, 50f, 555f, 400f),
            markdown = markdown,
        )
        return base.copy(
            meta = base.meta.copy(tags = listOf("uni", "math"), view = ViewMode.CONTINUOUS),
            sheet = base.sheet.copy(
                blocks = listOf(ink, text),
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

        assertEquals(markdown, restored.sheet.textBlocks().single().markdown)
        assertEquals(Rect(60f, 50f, 555f, 400f), restored.sheet.textBlocks().single().rect)
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
        // Derived from the boxes rather than stored, so it says what the note says.
        val entries = entriesOf(DakNoteWriter.toByteArray(sampleNote()))
        assertEquals(markdown, entries["content.md"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `the derived markdown file reads the boxes down the page`() {
        val base = DakNote.newNote(now = 0L)
        fun box(id: String, top: Float, text: String) =
            TextBlock(BlockId(id), z = 1, rect = Rect(50f, top, 500f, top + 40f), markdown = text)

        // Deliberately out of document order in the list: reading order is a fact about where the
        // boxes are on the paper, not about which one the user happened to type first.
        val note = base.copy(
            sheet = base.sheet.copy(
                blocks = listOf(box("b1", 900f, "second"), box("b2", 100f, "first")),
            )
        )
        val entries = entriesOf(DakNoteWriter.toByteArray(note))
        assertEquals("first\n\nsecond", entries["content.md"]!!.toString(Charsets.UTF_8))
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
        val box = TextBlock(BlockId("b1"), z = 0, rect = Rect(50f, 50f, 500f, 100f), markdown = "just words")
        val note = base.copy(sheet = base.sheet.copy(blocks = listOf(box)))
        val restored = roundTrip(note)
        assertEquals("just words", restored.sheet.textBlocks().single().markdown)
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

        // Each page's document text stays on its own page, in the column it filled — where v2
        // would have dragged both onto page one by concatenating them into a single flow.
        val boxes = note.sheet.textBlocks()
        assertEquals(listOf("# Page one", "# Page two"), boxes.map { it.markdown })
        // Each keeps the column the v1 file gave it, shifted into strip coordinates.
        assertEquals(48f, boxes[0].rect.top, 0.5f)
        assertEquals(842f + 48f, boxes[1].rect.top, 0.5f)
        assertEquals("", note.sheet.markdown)

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
    fun `a migrated v1 note saves at the current version and keeps its foreign entries`() {
        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(legacyV1())))
        assertTrue("assets/legacy.bin" in note.foreignEntries)

        val entries = entriesOf(DakNoteWriter.toByteArray(note))
        val manifest = JsonReader.parseObject(entries["manifest.json"]!!.toString(Charsets.UTF_8))
        assertEquals(DakNote.FORMAT_VERSION, manifest.int("formatVersion"))
        assertEquals("old-note", manifest.string("id"))
        assertTrue("sheet.json" in entries)
        assertFalse(entries.keys.any { it.startsWith("pages/") })
        assertArrayEquals(byteArrayOf(9, 8, 7), entries["assets/legacy.bin"])
    }

    // ---- v2 flow migration -------------------------------------------------------------------

    /** A v2 file: a document flow in `content.md`, and no text block anywhere. */
    private fun legacyV2(text: String, contentHeight: Float): LinkedHashMap<String, ByteArray> {
        val manifest = """
            {"formatVersion":2,"minReaderVersion":2,"id":"flow-note","revision":3,
             "created":1,"modified":2,"title":"Flow","tags":[],"view":"paged"}
        """.trimIndent()

        val sheet = """
            {"size":{"kind":"A4"},
             "margins":{"left":56,"top":56,"right":56,"bottom":56},
             "background":{"color":"#FFFFFFFF","pattern":{"type":"none"}},
             "contentHeight":$contentHeight,"pages":1,
             "blocks":[{"id":"b0","type":"ink","z":0,"rect":[0,0,595,842],
                        "transform":[1,0,0,1,0,0],"name":"Layer 1","visible":true,"locked":false}]}
        """.trimIndent()

        return linkedMapOf(
            "mimetype" to DakNote.MIME_TYPE.toByteArray(),
            "manifest.json" to manifest.toByteArray(),
            "sheet.json" to sheet.toByteArray(),
            "content.md" to text.toByteArray(),
        )
    }

    @Test
    fun `a v2 document flow opens as a text box on the text column`() {
        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(legacyV2("# Notes\n\nbody", 1200f))))
        val format = note.sheet.format
        val box = note.sheet.textBlocks().single()

        assertEquals("# Notes\n\nbody", box.markdown)
        // Exactly the band the flow occupied, so the words come back where the file last showed them.
        assertEquals(format.contentLeft, box.rect.left, 0.5f)
        assertEquals(format.margins.top, box.rect.top, 0.5f)
        assertEquals(format.contentRight, box.rect.right, 0.5f)
        assertEquals(1200f, box.rect.bottom, 0.5f)
        assertEquals("", note.sheet.markdown)
    }

    @Test
    fun `migrating a v2 flow does not change how many pages the note has`() {
        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(legacyV2("body", 1200f))))
        // The flow reached onto page two; the box that replaced it has to reach just as far, or
        // the note loses a page the moment it is opened.
        assertEquals(2, note.sheet.pageCount())
    }

    @Test
    fun `migrating twice is a fixed point`() {
        // The trap this guards: content.md is derived from the boxes on save, so a reader that
        // hydrated it unconditionally would add a second copy of the whole note on every open.
        val once = DakNoteReader.read(ByteArrayInputStream(zipOf(legacyV2("# Notes\n\nbody", 1200f))))
        val twice = roundTrip(once)
        val thrice = roundTrip(twice)

        assertEquals(1, twice.sheet.textBlocks().size)
        assertEquals(1, thrice.sheet.textBlocks().size)
        assertEquals("# Notes\n\nbody", thrice.sheet.textBlocks().single().markdown)
        assertArrayEquals(DakNoteWriter.toByteArray(twice), DakNoteWriter.toByteArray(thrice))
    }

    @Test
    fun `a v2 flow beside a text box is left to the box`() {
        // Some other tool could write both. The boxes are the document; content.md is a copy of it.
        val entries = legacyV2("stale copy", 400f)
        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
        val withBox = note.copy(
            sheet = note.sheet.copy(
                blocks = listOf(TextBlock(BlockId("bx"), 0, Rect(1f, 2f, 3f, 4f), markdown = "real")),
            )
        )
        val entriesWithBoth = entriesOf(DakNoteWriter.toByteArray(withBox)).also {
            it["content.md"] = "stale copy".toByteArray()
        }
        val reread = DakNoteReader.read(ByteArrayInputStream(zipOf(entriesWithBoth)))
        assertEquals(listOf("real"), reread.sheet.textBlocks().map { it.markdown })
    }

    @Test
    fun `a note written now is closed to older builds`() {
        // A v2 build renders content.md as the flow and cannot see text blocks at all, so it would
        // show a migrated note's words in the wrong place and write them out twice on save.
        val manifest = entriesOf(DakNoteWriter.toByteArray(sampleNote()))["manifest.json"]!!
        val json = JsonReader.parseObject(manifest.toString(Charsets.UTF_8))
        assertEquals(3, json.int("minReaderVersion"))
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

    // ---- Recognised shapes -----------------------------------------------------------------------

    /** A note whose ink layer holds one plain stroke and one snapped shape. */
    private fun noteWithShape(spec: ShapeSpec = ShapeSpec.Ngon(120f, 300f, 48f, 0.31f, 5)): Note {
        val note = sampleNote()
        val ink = note.sheet.inkLayers().single()
        val shaped = Stroke(
            ToolId.PEN, 0xFF1B1B1F.toInt(), 2.5f, BlendId.NORMAL,
            floatArrayOf(100f, 140f, 120f, 100f), floatArrayOf(100f, 100f, 140f, 100f),
            shape = spec,
        )
        return note.copy(sheet = note.sheet.copy(blocks = listOf(ink.copy(strokes = ink.strokes + shaped))))
    }

    @Test
    fun `a recognised shape survives a round trip`() {
        for (spec in listOf(
            ShapeSpec.Line(10f, 20f, 300f, 40f),
            ShapeSpec.Arc(120f, 90f, 64f, 0.3f, -2.75f),
            ShapeSpec.Poly(floatArrayOf(0f, 90f, 40f), floatArrayOf(0f, 10f, 80f)),
            ShapeSpec.Rect(200f, 200f, 50f, 50f, 0.4f, equilateral = true),
            ShapeSpec.Ngon(120f, 300f, 48f, 0.31f, 7),
            ShapeSpec.Ellipse(100f, 100f, 60f, 30f, 0.2f, equilateral = false),
        )) {
            val strokes = roundTrip(noteWithShape(spec)).sheet.inkLayers().single().strokes
            assertEquals("$spec", spec, strokes.last().shape)
            assertNull("plain ink must not acquire a shape", strokes.first().shape)
        }
    }

    @Test
    fun `a note with no shapes is written exactly as it was before shapes existed`() {
        // The key is optional and absent by default, so adding the feature must not rewrite every
        // existing note on first save — which is the whole point of an optional key over a
        // migration, and what keeps file sync quiet.
        val descriptor = inkDescriptorOf(DakNoteWriter.toByteArray(sampleNote()))
        assertFalse("shapes", "shapes" in descriptor)
    }

    @Test
    fun `a note written before shapes existed opens with plain strokes`() {
        val entries = entriesOf(DakNoteWriter.toByteArray(noteWithShape()))
        val sheet = JsonReader.parseObject(entries["sheet.json"]!!.toString(Charsets.UTF_8))
        val stripped = (sheet.array("blocks")!!.items).map { (it as JsonObject).without("shapes") }
        entries["sheet.json"] = JsonWriter.write(sheet.with("blocks", JsonArray(stripped)))
            .toByteArray(Charsets.UTF_8)

        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
        val strokes = note.sheet.inkLayers().single().strokes
        assertEquals("the ink itself is unaffected", 3, strokes.size)
        assertNull(strokes.last().shape)
    }

    @Test
    fun `a shape record naming a stroke of the wrong length is discarded`() {
        // Strokes are identified by their position in the layer, so an older build erasing one
        // shifts every later index. The recorded point count is what catches that: the shape is
        // dropped and the stroke stays honest ink, rather than being described as something it is
        // not. Simulated here by pointing the record at a stroke of a different length.
        val entries = entriesOf(DakNoteWriter.toByteArray(noteWithShape()))
        val sheet = JsonReader.parseObject(entries["sheet.json"]!!.toString(Charsets.UTF_8))
        val patched = (sheet.array("blocks")!!.items).map { item ->
            val o = item as JsonObject
            val shapes = o.array("shapes") ?: return@map o
            o.with("shapes", JsonArray(shapes.items.map { (it as JsonObject).with("i", 0) }))
        }
        entries["sheet.json"] = JsonWriter.write(sheet.with("blocks", JsonArray(patched)))
            .toByteArray(Charsets.UTF_8)

        val strokes = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
            .sheet.inkLayers().single().strokes
        assertNull("stroke 0 is three points long, the record claims four", strokes[0].shape)
    }

    @Test
    fun `a shape kind from a future release is ignored rather than guessed at`() {
        val entries = entriesOf(DakNoteWriter.toByteArray(noteWithShape()))
        val sheet = JsonReader.parseObject(entries["sheet.json"]!!.toString(Charsets.UTF_8))
        val patched = (sheet.array("blocks")!!.items).map { item ->
            val o = item as JsonObject
            val shapes = o.array("shapes") ?: return@map o
            o.with("shapes", JsonArray(shapes.items.map { (it as JsonObject).with("kind", "spline") }))
        }
        entries["sheet.json"] = JsonWriter.write(sheet.with("blocks", JsonArray(patched)))
            .toByteArray(Charsets.UTF_8)

        val strokes = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
            .sheet.inkLayers().single().strokes
        assertNull(strokes.last().shape)
        assertEquals("the ink is still there", 4, strokes.last().pointCount)
    }

    @Test
    fun `saving a note that contains a shape twice produces identical bytes`() {
        val first = DakNoteWriter.toByteArray(noteWithShape())
        val second = DakNoteWriter.toByteArray(DakNoteReader.read(ByteArrayInputStream(first)))
        assertArrayEquals(first, second)
    }

    private fun inkDescriptorOf(bytes: ByteArray): JsonObject {
        val sheet = JsonReader.parseObject(entriesOf(bytes)["sheet.json"]!!.toString(Charsets.UTF_8))
        return sheet.array("blocks")!!.items
            .map { it as JsonObject }
            .first { it.string("type") == "ink" }
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
        // The words are the irreplaceable part, and content.md is the copy of them that survives
        // losing every block descriptor. It comes back as a box on the text column: the geometry
        // was in the entry that was lost, but the writing was not.
        val entries = entriesOf(DakNoteWriter.toByteArray(sampleNote()))
        entries["sheet.json"] = "{ this is not json".toByteArray()

        val note = DakNoteReader.read(ByteArrayInputStream(zipOf(entries)))
        assertEquals(markdown, note.sheet.textBlocks().single().markdown)
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
            .sheet.textBlocks().single { it.id == BlockId("b7") }
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
                    pattern = PagePattern(
                        type = PatternType.RULED,
                        spacing = 19f,
                        color = 0x99A3C77E.toInt(),
                        margin = 72f,
                        marginColor = 0xFF2E86C1.toInt(),
                    ),
                ),
            ),
        )
        val bg = roundTrip(note.withSheet(customised)).sheet.format.background

        assertEquals(0xFFEDE7D9.toInt(), bg.color)
        assertEquals(0x99A3C77E.toInt(), bg.pattern.color)
        assertEquals(0xFF2E86C1.toInt(), bg.pattern.marginColor)
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
