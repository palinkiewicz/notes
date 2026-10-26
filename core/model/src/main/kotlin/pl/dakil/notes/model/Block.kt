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
    // A text box or a picture is turned about its own corner. Shifting the rect and that pivot
    // together keeps the rect where the block is drawn, which is what the live editor and the
    // handles read it as.
    if (this is TextBlock || this is ImageBlock) {
        val rect = Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
        val transform = if (transform.isIdentity) transform
        else Affine.translate(dx, dy).then(transform).then(Affine.translate(-dx, -dy))
        return when (this) {
            is TextBlock -> copy(rect = rect, transform = transform)
            is ImageBlock -> copy(rect = rect, transform = transform)
            else -> this
        }
    }
    if (!transform.isIdentity) {
        val moved = Affine.translate(dx, dy).then(transform)
        return when (this) {
            is InkBlock -> copy(transform = moved)
            is TextBlock -> copy(transform = moved)
            is ImageBlock -> copy(transform = moved)
            is OpaqueBlock -> copy(transform = moved)
        }
    }
    val moved = Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
    return when (this) {
        is InkBlock -> copy(rect = moved)
        is TextBlock -> copy(rect = moved)
        is ImageBlock -> copy(rect = moved)
        is OpaqueBlock -> copy(rect = moved)
    }
}

/** The same block under a different id, for the copy a page duplication leaves behind. */
fun Block.withId(id: BlockId): Block = when (this) {
    is InkBlock -> copy(id = id)
    is TextBlock -> copy(id = id)
    is ImageBlock -> copy(id = id)
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
 * A picture placed on the paper.
 *
 * Its own block rather than an image inside a [TextBlock]: a picture on a page is moved, sized and
 * written over the way a stroke is, not reflowed with prose. [rect] is where it is drawn, in strip
 * coordinates, and the picture is stretched to fill it — the editor keeps the aspect when resizing,
 * so nothing written in the file has to say what the aspect was.
 *
 * [bytes] are the encoded image, kept exactly as they were imported and written to the archive
 * under [src] unchanged. [src] is named by the content, so two blocks showing one picture share one
 * entry, and a block whose picture has not changed has the same name on every save.
 */
data class ImageBlock(
    override val id: BlockId,
    override val z: Int,
    override val rect: Rect,
    override val transform: Affine = Affine.IDENTITY,
    override val unknown: JsonObject = JsonObject.EMPTY,
    val src: String,
    val mimeType: String,
    val bytes: ByteArray,
    /**
     * A colour the picture is shown in, or null for its own colours.
     *
     * A filter over the pixels, never baked into them: [bytes] stay exactly what was imported, so
     * taking the colour off gives back the original picture.
     */
    val tint: Int? = null,
) : Block {
    // By [src] rather than by the bytes: the name is derived from the content, and comparing a
    // photograph byte for byte on every equality check would be paid on every edit to the sheet.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageBlock) return false
        return id == other.id && z == other.z && rect == other.rect && transform == other.transform &&
            unknown == other.unknown && src == other.src && mimeType == other.mimeType && tint == other.tint
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + z
        h = 31 * h + rect.hashCode()
        h = 31 * h + transform.hashCode()
        h = 31 * h + src.hashCode()
        h = 31 * h + (tint ?: 0)
        return h
    }
}

/**
 * Where a block that is taken whole stands: the top-left corner of its rect on the paper, and how
 * far it is turned about that corner, in radians.
 *
 * Read off the block's [Block.transform] whatever is in it — a page move composes a translation
 * onto it, a rotation is written as one about the corner — so every way a transform can have been
 * built reads back as the same three numbers.
 */
data class Placement(val left: Float, val top: Float, val angle: Float)

fun Block.placement(): Placement = Placement(
    left = transform.mapX(rect.left, rect.top),
    top = transform.mapY(rect.left, rect.top),
    angle = if (transform.isIdentity) 0f else kotlin.math.atan2(transform.b, transform.a),
)

/** The rect and transform that stand a [width] × [height] block at [placement]. */
private fun placed(placement: Placement, width: Float, height: Float): Pair<Rect, Affine> {
    val rect = Rect(placement.left, placement.top, placement.left + width, placement.top + height)
    // Unturned is written as no transform at all, so a block nobody has rotated is byte-identical to
    // one written before rotation existed.
    val angle = normalisedAngle(placement.angle)
    val transform = if (kotlin.math.abs(angle) < ANGLE_EPSILON) {
        Affine.IDENTITY
    } else {
        Affine.rotate(angle, placement.left, placement.top)
    }
    return rect to transform
}

private fun normalisedAngle(angle: Float): Float {
    val turn = (2 * Math.PI).toFloat()
    var a = angle % turn
    if (a > Math.PI) a -= turn
    if (a < -Math.PI) a += turn
    return a
}

/** Below this a block is upright: about a hundredth of a degree. */
private const val ANGLE_EPSILON = 2e-4f

/** The turn [matrix] applies, in radians; zero for a pure scale or move. */
private fun Affine.turn(): Float = kotlin.math.atan2(b, a)

/**
 * Where [placement] ends up under a selection transform: its corner carried by [matrix], and turned
 * by as much as [matrix] turns.
 */
private fun Placement.carriedBy(matrix: Affine) = Placement(
    left = matrix.mapX(left, top),
    top = matrix.mapY(left, top),
    angle = angle + matrix.turn(),
)

/**
 * The same text box after a selection transform: moved and turned with it, never scaled.
 *
 * A box's width is a measurement the reader chose — it is what decides where the lines break — and
 * its height is whatever the words need, so a scale has nothing honest to change. A turn does: the
 * box is rotated about its corner, and the words with it.
 */
fun TextBlock.carriedBy(matrix: Affine): TextBlock {
    if (matrix.isIdentity) return this
    val (rect, transform) = placed(placement().carriedBy(matrix), rect.width, rect.height)
    return copy(rect = rect, transform = transform)
}

/**
 * The same picture after a selection transform: moved, turned, and scaled evenly — never skewed.
 *
 * A drag on a corner handle scales the two axes apart, and a picture pulled out of its aspect is a
 * picture of something else, so the scale taken is the one that keeps the area the transform asks
 * for, applied to both sides alike.
 */
fun ImageBlock.placedBy(matrix: Affine): ImageBlock {
    if (matrix.isIdentity) return this
    val scale = kotlin.math.sqrt(kotlin.math.abs(matrix.a * matrix.d - matrix.b * matrix.c))
        .takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = (rect.width * scale).coerceAtLeast(MIN_IMAGE_SIDE)
    val height = (rect.height * width / rect.width.coerceAtLeast(1e-3f)).coerceAtLeast(MIN_IMAGE_SIDE)
    val (rect, transform) = placed(placement().carriedBy(matrix), width, height)
    return copy(rect = rect, transform = transform)
}

/** The smallest a picture can be scaled to, in points: small enough to tuck away, big enough to grab. */
const val MIN_IMAGE_SIDE = 12f

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
