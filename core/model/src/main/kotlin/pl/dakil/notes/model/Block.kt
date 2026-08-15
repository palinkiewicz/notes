package pl.dakil.notes.model

import pl.dakil.notes.model.json.JsonObject

@JvmInline
value class BlockId(val raw: String) {
    override fun toString(): String = raw
}

/**
 * A positioned element on a page.
 *
 * Everything on a page is a block, including ink layers. This is what makes the format extensible:
 * a future release can add a chart or an audio clip as a new [Block] subtype, and every older build
 * still round-trips it as an [OpaqueBlock] without losing a byte.
 */
sealed interface Block {
    val id: BlockId
    /** Paint order within the page; also the ordering used to build the Document-mode flow. */
    val z: Int
    /** Position and extent in page units, before [transform]. */
    val rect: Rect
    val transform: Affine
    /** Descriptor keys this build does not understand, kept so a save cannot drop them. */
    val unknown: JsonObject

    /** The block's on-page bounds with [transform] applied. */
    fun worldBounds(): Rect = transform.mapBounds(rect)
}

/** A layer of vector strokes. A page normally has one; more exist when the user adds layers. */
data class InkBlock(
    override val id: BlockId,
    override val z: Int,
    override val rect: Rect,
    override val transform: Affine = Affine.IDENTITY,
    override val unknown: JsonObject = JsonObject.EMPTY,
    val strokes: List<Stroke> = emptyList(),
    val name: String = "Layer",
    val visible: Boolean = true,
    val locked: Boolean = false,
) : Block

/**
 * A floating Markdown text box, positioned on the sheet rather than in the main flow.
 *
 * The note's primary text is [Sheet.markdown], which reflows and paginates. This is for labels
 * pinned beside a diagram — text that belongs at a place on the paper, not in the argument.
 */
data class TextBlock(
    override val id: BlockId,
    override val z: Int,
    override val rect: Rect,
    override val transform: Affine = Affine.IDENTITY,
    override val unknown: JsonObject = JsonObject.EMPTY,
    val markdown: String = "",
) : Block

/**
 * A block whose `type` this build does not know — written by a newer version of the app, or by
 * another tool.
 *
 * It keeps the full descriptor ([raw]) and the bytes of its payload entry ([payload]), so an edit
 * session that never touches it writes it back unchanged. The user can still select and move it,
 * because [z]/[rect]/[transform] are understood generically and re-merged into [raw] on write.
 */
data class OpaqueBlock(
    override val id: BlockId,
    override val z: Int,
    override val rect: Rect,
    override val transform: Affine = Affine.IDENTITY,
    override val unknown: JsonObject = JsonObject.EMPTY,
    val type: String,
    val raw: JsonObject,
    val payloadEntry: String? = null,
    val payload: ByteArray? = null,
) : Block {
    // Generated equals/hashCode would compare ByteArray by identity; compare by content instead so
    // round-trip tests and change detection behave.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpaqueBlock) return false
        return id == other.id && z == other.z && rect == other.rect &&
            transform == other.transform && type == other.type && raw == other.raw &&
            payloadEntry == other.payloadEntry &&
            (payload?.contentEquals(other.payload) ?: (other.payload == null))
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + z
        h = 31 * h + rect.hashCode()
        h = 31 * h + type.hashCode()
        h = 31 * h + raw.hashCode()
        h = 31 * h + (payloadEntry?.hashCode() ?: 0)
        h = 31 * h + (payload?.contentHashCode() ?: 0)
        return h
    }
}
