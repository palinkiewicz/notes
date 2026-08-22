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

/**
 * The same block, shifted across the paper.
 *
 * A block that has never been rotated or scaled — which is every box this app creates — carries the
 * move in its [Block.rect], so its geometry stays readable in the file and a later resize has plain
 * numbers to work from. Anything with a transform of its own gets the translation composed onto it
 * instead, because shifting the rect underneath a rotation would move the block somewhere else
 * entirely.
 */
fun Block.translated(dx: Float, dy: Float): Block {
    if (dx == 0f && dy == 0f) return this
    if (!transform.isIdentity) {
        val moved = Affine.translate(dx, dy).then(transform)
        return when (this) {
            is InkBlock -> copy(transform = moved)
            is TextBlock -> copy(transform = moved)
            is OpaqueBlock -> copy(transform = moved)
        }
    }
    val moved = Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
    return when (this) {
        is InkBlock -> copy(rect = moved)
        is TextBlock -> copy(rect = moved)
        is OpaqueBlock -> copy(rect = moved)
    }
}

/** The same block under a different id, for the copy a page duplication leaves behind. */
fun Block.withId(id: BlockId): Block = when (this) {
    is InkBlock -> copy(id = id)
    is TextBlock -> copy(id = id)
    is OpaqueBlock -> copy(id = id)
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
) : Block {

    /**
     * Re-derives [rect] from the strokes actually in the layer.
     *
     * Needed after any operation that moves or deletes strokes wholesale — duplicating a page, say.
     * A stale rect is not cosmetic: it feeds culling and [Sheet.contentBottom], so a layer claiming to
     * extend further than it does silently holds pages open that nothing is drawn on.
     */
    fun withRecomputedBounds(): InkBlock {
        if (strokes.isEmpty()) return copy(rect = Rect.ZERO)
        var bounds = Rect.EMPTY
        for (stroke in strokes) bounds = bounds.union(stroke.bounds)
        return copy(rect = bounds)
    }
}

/**
 * A box of Markdown, positioned on the paper.
 *
 * This is *all* the typed text a sheet has. There was once a second kind — one continuous flow
 * anchored to the top of the note — and it is gone because it could not answer the questions a page
 * asks: which page is this text on, and where does it go when that page moves? A box can answer
 * both, so a sheet's text now moves with its paper exactly as its ink does.
 *
 * [rect] is the box in strip coordinates and [transform] is left identity by everything this app
 * creates; text wraps to [rect]'s width and the box grows downwards to fit what is typed in it.
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
