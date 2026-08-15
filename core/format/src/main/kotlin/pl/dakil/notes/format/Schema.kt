package pl.dakil.notes.format

import pl.dakil.notes.model.Affine
import pl.dakil.notes.model.Block
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.NoteId
import pl.dakil.notes.model.NoteMeta
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.OpaqueBlock
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonNumber
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonString

/**
 * Maps between the JSON documents inside a `.daknote` and the domain model.
 *
 * The recurring pattern here is [remainderOf]: every reader pulls out the keys it understands and
 * stashes everything else in the model's `unknown` field, and every writer merges that remainder
 * back. That is what lets a note written by a future version survive an edit round-trip through
 * this build without losing the fields it added.
 */
internal object Schema {

    // ---- Manifest ----------------------------------------------------------------------------

    private val MANIFEST_KEYS = setOf(
        "formatVersion", "minReaderVersion", "id", "revision",
        "created", "modified", "title", "tags", "view",
        // v1 keys, still stripped so a migrated file does not carry them into its remainder.
        "defaultMode", "pages",
    )

    fun readManifest(json: JsonObject): ManifestData = ManifestData(
        formatVersion = json.int("formatVersion", 1),
        minReaderVersion = json.int("minReaderVersion", 1),
        meta = NoteMeta(
            id = NoteId(json.string("id").ifEmpty { DakNote.newId() }),
            title = json.string("title"),
            tags = json.stringList("tags"),
            created = json.long("created"),
            modified = json.long("modified"),
            revision = json.long("revision"),
            view = ViewMode.fromKey(json.string("view", "paged")),
            unknown = remainderOf(json, MANIFEST_KEYS),
        ),
        legacyPageOrder = json.stringList("pages"),
    )

    fun writeManifest(meta: NoteMeta): JsonObject =
        JsonObject.of(
            "formatVersion" to JsonNumber.of(DakNote.FORMAT_VERSION),
            "minReaderVersion" to JsonNumber.of(DakNote.FORMAT_VERSION),
            "id" to JsonString(meta.id.raw),
            "revision" to JsonNumber.of(meta.revision),
            "created" to JsonNumber.of(meta.created),
            "modified" to JsonNumber.of(meta.modified),
            "title" to JsonString(meta.title),
            "tags" to JsonArray.ofStrings(meta.tags),
            "view" to JsonString(meta.view.key),
        ).withDefaults(meta.unknown)

    data class ManifestData(
        val formatVersion: Int,
        val minReaderVersion: Int,
        val meta: NoteMeta,
        /** v1 only: the page list that has to be flattened into one strip. */
        val legacyPageOrder: List<String>,
    )

    // ---- Page --------------------------------------------------------------------------------

    private val PAGE_KEYS = setOf("id", "size", "background", "blocks")

    fun readPageSize(json: JsonObject?): PageSize {
        if (json == null) return PageSize.A4
        return when (val kind = json.string("kind", "A4")) {
            // v1 could store "infinite" as a size. It is a view mode now, so such a file lands on
            // A4 and opens in continuous view, which is what it looked like before.
            "infinite" -> PageSize.A4
            "custom" -> PageSize.Custom(json.float("w", 595f), json.float("h", 842f))
            else -> PageSize.Fixed(
                PageSize.Kind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
                    ?: PageSize.Kind.A4
            )
        }
    }

    fun writePageSize(size: PageSize): JsonObject = when (size) {
        is PageSize.Fixed -> JsonObject.of("kind" to JsonString(size.kind.name))
        is PageSize.Custom -> JsonObject.of(
            "kind" to JsonString("custom"),
            "w" to JsonNumber.of(size.width),
            "h" to JsonNumber.of(size.height),
        )
    }

    fun readBackground(json: JsonObject?): PageBackground {
        if (json == null) return PageBackground.DEFAULT
        val d = PageBackground.DEFAULT
        return PageBackground(
            color = hexToColor(json.string("color"), d.color),
            darkColor = hexToColor(json.string("darkColor"), d.darkColor),
            pattern = readPattern(json.obj("pattern")),
            adaptPatternToDark = json.bool("adaptPatternToDark", d.adaptPatternToDark),
        )
    }

    fun writeBackground(bg: PageBackground): JsonObject = JsonObject.of(
        "color" to JsonString(colorToHex(bg.color)),
        "darkColor" to JsonString(colorToHex(bg.darkColor)),
        "adaptPatternToDark" to pl.dakil.notes.model.json.JsonBool(bg.adaptPatternToDark),
        "pattern" to writePattern(bg.pattern),
    )

    private fun readPattern(json: JsonObject?): PagePattern {
        if (json == null) return PagePattern.NONE
        val d = PagePattern.NONE
        return PagePattern(
            type = PatternType.fromKey(json.string("type", "none")),
            spacing = json.float("spacing", d.spacing),
            color = hexToColor(json.string("color"), d.color),
            opacity = json.float("opacity", d.opacity),
            margin = json.float("margin", d.margin),
            marginColor = hexToColor(json.string("marginColor"), d.marginColor),
            groupSpacing = json.float("groupSpacing", d.groupSpacing),
        )
    }

    private fun writePattern(p: PagePattern): JsonObject = JsonObject.of(
        "type" to JsonString(p.type.key),
        "spacing" to JsonNumber.of(p.spacing),
        "color" to JsonString(colorToHex(p.color)),
        "opacity" to JsonNumber.of(p.opacity),
        "margin" to JsonNumber.of(p.margin),
        "marginColor" to JsonString(colorToHex(p.marginColor)),
        "groupSpacing" to JsonNumber.of(p.groupSpacing),
    )

    fun pageRemainder(json: JsonObject): JsonObject = remainderOf(json, PAGE_KEYS)

    // ---- Sheet -------------------------------------------------------------------------------

    /**
     * `pages` is an explicit page count, added after v2 shipped.
     *
     * No version bump: it is an optional key that defaults to the previous behaviour, so an older
     * build reads such a file correctly (deriving the count from the content, as it always did)
     * and carries the key through its remainder untouched. This is the forward-compatibility rule
     * doing the job it was designed for, instead of a migration.
     */
    private val SHEET_KEYS =
        setOf("size", "margins", "background", "markdown", "blocks", "contentHeight", "pages")

    fun readMargins(json: JsonObject?): PageMargins {
        if (json == null) return PageMargins.DEFAULT
        val d = PageMargins.DEFAULT
        return PageMargins(
            left = json.float("left", d.left),
            top = json.float("top", d.top),
            right = json.float("right", d.right),
            bottom = json.float("bottom", d.bottom),
        )
    }

    fun writeMargins(m: PageMargins): JsonObject = JsonObject.of(
        "left" to JsonNumber.of(m.left),
        "top" to JsonNumber.of(m.top),
        "right" to JsonNumber.of(m.right),
        "bottom" to JsonNumber.of(m.bottom),
    )

    fun readFormat(json: JsonObject): PageFormat = PageFormat(
        size = readPageSize(json.obj("size")),
        background = readBackground(json.obj("background")),
        margins = readMargins(json.obj("margins")),
    )

    fun sheetRemainder(json: JsonObject): JsonObject = remainderOf(json, SHEET_KEYS)

    // ---- Blocks ------------------------------------------------------------------------------

    private val COMMON_BLOCK_KEYS = setOf("id", "type", "z", "rect", "transform", "src")
    private val INK_BLOCK_KEYS = COMMON_BLOCK_KEYS + setOf("name", "visible", "locked")
    private val TEXT_BLOCK_KEYS = COMMON_BLOCK_KEYS + setOf("flow")

    /** v1 stored the document text on blocks carrying `flow: "document"`. */
    fun isLegacyFlowText(json: JsonObject): Boolean = json.string("flow", "document") == "document"

    const val TYPE_INK = "ink"
    const val TYPE_TEXT = "text"

    /**
     * Reads one block descriptor. [resolve] supplies the bytes of the entry named by `src`, which
     * the caller reads out of the ZIP.
     */
    fun readBlock(json: JsonObject, resolve: (String) -> ByteArray?): Block {
        val id = BlockId(json.string("id").ifEmpty { "b${json.int("z")}" })
        val z = json.int("z")
        val rect = readRect(json.array("rect"))
        val transform = readAffine(json.array("transform"))
        val src = json.string("src").takeIf { it.isNotEmpty() }

        return when (val type = json.string("type")) {
            TYPE_INK -> InkBlock(
                id = id, z = z, rect = rect, transform = transform,
                unknown = remainderOf(json, INK_BLOCK_KEYS),
                strokes = src?.let { resolve(it) }?.let { StrokeCodec.readLenient(it) } ?: emptyList(),
                name = json.string("name", "Layer"),
                visible = json.bool("visible", true),
                locked = json.bool("locked", false),
            )

            TYPE_TEXT -> TextBlock(
                id = id, z = z, rect = rect, transform = transform,
                unknown = remainderOf(json, TEXT_BLOCK_KEYS),
                markdown = src?.let { resolve(it) }?.toString(Charsets.UTF_8) ?: "",
            )

            // Anything else: keep the descriptor and its payload verbatim. The user can still move
            // and reorder it, because z/rect/transform are understood generically.
            else -> OpaqueBlock(
                id = id, z = z, rect = rect, transform = transform,
                type = type,
                raw = json,
                payloadEntry = src,
                payload = src?.let { resolve(it) },
            )
        }
    }

    /** Serialises a block descriptor. [entryFor] assigns the payload entry name. */
    fun writeBlock(block: Block, entryFor: (Block) -> String?): JsonObject {
        val base = JsonObject.of(
            "id" to JsonString(block.id.raw),
            "type" to JsonString(typeOf(block)),
            "z" to JsonNumber.of(block.z),
            "rect" to writeRect(block.rect),
            "transform" to writeAffine(block.transform),
        )
        val src = entryFor(block)
        val withSrc = if (src != null) base.with("src", JsonString(src)) else base

        return when (block) {
            is InkBlock -> withSrc
                .with("name", block.name)
                .with("visible", block.visible)
                .with("locked", block.locked)
                .withDefaults(block.unknown)

            is TextBlock -> withSrc.withDefaults(block.unknown)

            // Start from the original descriptor so unknown keys keep their position, then
            // overwrite only the generic fields the editor is allowed to change.
            is OpaqueBlock -> block.raw
                .with("id", block.id.raw)
                .with("type", block.type)
                .with("z", block.z)
                .with("rect", writeRect(block.rect))
                .with("transform", writeAffine(block.transform))
                .let { if (src != null) it.with("src", src) else it }
        }
    }

    fun typeOf(block: Block): String = when (block) {
        is InkBlock -> TYPE_INK
        is TextBlock -> TYPE_TEXT
        is OpaqueBlock -> block.type
    }

    // ---- Primitives --------------------------------------------------------------------------

    fun readRect(a: JsonArray?): Rect {
        if (a == null || a.size < 4) return Rect.ZERO
        fun n(i: Int) = (a[i] as? JsonNumber)?.toFloat() ?: 0f
        return Rect(n(0), n(1), n(2), n(3))
    }

    fun writeRect(r: Rect): JsonArray =
        JsonArray.ofFloats(listOf(r.left, r.top, r.right, r.bottom))

    fun readAffine(a: JsonArray?): Affine {
        if (a == null || a.size < 6) return Affine.IDENTITY
        fun n(i: Int) = (a[i] as? JsonNumber)?.toFloat() ?: 0f
        return Affine(n(0), n(1), n(2), n(3), n(4), n(5))
    }

    fun writeAffine(m: Affine): JsonArray =
        JsonArray.ofFloats(listOf(m.a, m.b, m.c, m.d, m.tx, m.ty))

    /** Every entry of [json] whose key is not in [known] — the forward-compatibility carrier. */
    fun remainderOf(json: JsonObject, known: Set<String>): JsonObject {
        var out: JsonObject? = null
        json.forEach { k, v ->
            if (k !in known) out = (out ?: JsonObject.EMPTY).with(k, v)
        }
        return out ?: JsonObject.EMPTY
    }
}
