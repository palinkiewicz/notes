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
    /**
     * The part of the picture that is shown, as fractions of its own width and height — [UNCROPPED]
     * for all of it — stretched to fill [rect].
     *
     * A window onto the pixels, never a cut through them, for the reason [tint] is a filter: [bytes]
     * stay what was imported, so the crop can be widened again later. Fractions rather than pixels,
     * so the answer does not depend on how far a decode was sampled down.
     */
    val crop: Rect = UNCROPPED,
) : Block {

    val isCropped: Boolean get() = crop != UNCROPPED

    // By [src] rather than by the bytes: the name is derived from the content, and comparing a
    // photograph byte for byte on every equality check would be paid on every edit to the sheet.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageBlock) return false
        return id == other.id && z == other.z && rect == other.rect && transform == other.transform &&
            unknown == other.unknown && src == other.src && mimeType == other.mimeType && tint == other.tint &&
            crop == other.crop
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + z
        h = 31 * h + rect.hashCode()
        h = 31 * h + transform.hashCode()
        h = 31 * h + src.hashCode()
        h = 31 * h + (tint ?: 0)
        h = 31 * h + crop.hashCode()
        return h
    }

    companion object {
        /** The whole picture. */
        val UNCROPPED = Rect(0f, 0f, 1f, 1f)
    }
}

/**
 * Where a block that is taken whole stands: the top-left corner of its rect on the paper, and how
 * far it is turned about that corner, in radians.
 *
 * Read off the block's [Block.transform] whatever is in it — a page move composes a translation
 * onto it, a rotation is written as one about the corner — so every way a transform can have been
 * built reads back as the same three numbers. A block that has also been stretched or sheared has
 * more to it than an angle; [frame] is the whole of it.
 */
data class Placement(val left: Float, val top: Float, val angle: Float)

fun Block.placement(): Placement = Placement(
    left = transform.mapX(rect.left, rect.top),
    top = transform.mapY(rect.left, rect.top),
    angle = if (transform.isIdentity) 0f else kotlin.math.atan2(transform.b, transform.a),
)

/**
 * Where a block taken whole stands, in full: its rect's top-left corner on the paper, and the
 * linear map ([a], [b], [c], [d], laid out as in [Affine]) its rect is drawn through about that
 * corner.
 *
 * For a block that has only ever been moved and turned the map is a rotation and this says no more
 * than [Placement]; a stretch or a mirror is what makes it a general matrix.
 */
data class Frame(
    val left: Float, val top: Float,
    val a: Float, val b: Float, val c: Float, val d: Float,
) {
    /** The map as a whole: the rect's own top-left corner at the origin, carried to [left], [top]. */
    fun matrix(): Affine = Affine(a, b, c, d, left, top)

    /** Whether the map's two axes are still at right angles, so it is a turn and two stretches. */
    val isOrthogonal: Boolean
        get() = kotlin.math.abs(a * c + b * d) <=
            ORTHOGONAL_EPSILON * kotlin.math.hypot(a, b) * kotlin.math.hypot(c, d)
}

fun Block.frame(): Frame = Frame(
    left = transform.mapX(rect.left, rect.top),
    top = transform.mapY(rect.left, rect.top),
    a = transform.a, b = transform.b, c = transform.c, d = transform.d,
)

/**
 * The rect and transform that stand a [width] × [height] block at [left], [top], drawn through the
 * linear map [a]..[d] about that corner.
 *
 * A map that is no map at all is written as no transform, so a block nobody has turned or stretched
 * is byte-identical to one written before either existed — and one turned back upright returns to
 * that form, rather than carrying a matrix of float noise around.
 */
private fun framed(
    left: Float, top: Float, width: Float, height: Float,
    a: Float, b: Float, c: Float, d: Float,
): Pair<Rect, Affine> {
    val rect = Rect(left, top, left + width, top + height)
    val upright = kotlin.math.abs(a - 1f) < IDENTITY_EPSILON && kotlin.math.abs(b) < IDENTITY_EPSILON &&
        kotlin.math.abs(c) < IDENTITY_EPSILON && kotlin.math.abs(d - 1f) < IDENTITY_EPSILON
    if (upright) return rect to Affine.IDENTITY
    // About the corner: p ↦ corner + L · (p − corner).
    return rect to Affine(a, b, c, d, left - (a * left + c * top), top - (b * left + d * top))
}

/** Below this a map is the identity: about a hundredth of a degree of turn, or of stretch. */
private const val IDENTITY_EPSILON = 2e-4f

/** How near a right angle a map's axes must be to count as one: about a twentieth of a degree. */
private const val ORTHOGONAL_EPSILON = 1e-3f

/** The linear part of [this], applied after the linear map [a]..[d]. */
private inline fun <T> Affine.linearAfter(
    a: Float, b: Float, c: Float, d: Float,
    out: (Float, Float, Float, Float) -> T,
): T = out(
    this.a * a + this.c * b, this.b * a + this.d * b,
    this.a * c + this.c * d, this.b * c + this.d * d,
)

/** Whether [this] turns the page over, as a mirror does. */
private val Affine.isMirror: Boolean get() = a * d - b * c < 0f

/**
 * Whether [this] keeps shapes — a turn, a move, an even scale, or a mirror — as against a stretch or
 * a shear, which changes them.
 */
private val Affine.isSimilarity: Boolean
    get() {
        val scale = kotlin.math.sqrt(kotlin.math.abs(a * d - b * c))
        val tolerance = SIMILARITY_EPSILON * scale
        return if (isMirror) {
            kotlin.math.abs(a + d) <= tolerance && kotlin.math.abs(b - c) <= tolerance
        } else {
            kotlin.math.abs(a - d) <= tolerance && kotlin.math.abs(b + c) <= tolerance
        }
    }

private const val SIMILARITY_EPSILON = 1e-4f

/**
 * The same text box after a selection transform.
 *
 * Moved and turned with it, but never evenly scaled: a box's width is a measurement the reader chose
 * — it is what decides where the lines break — and the words keep their size, which is the size the
 * text was written at. That is the corner grips, which scale everything else evenly.
 *
 * A stretch is different: the edge grips exist to change a selection's proportions, and the box is
 * stretched with everything else, letters and all. The width the lines break at stays the same, so
 * the words wrap exactly as they did and come out wider or taller.
 *
 * A mirror is the one thing that cannot be done to words and leave them words. The box goes where
 * its mirror image would be — its centre carried by [matrix] — and is laid the mirrored way, but the
 * text in it still reads left to right.
 */
fun TextBlock.carriedBy(matrix: Affine): TextBlock {
    if (matrix.isIdentity) return this
    val at = frame()
    val w = rect.width
    val h = rect.height
    val (rect, transform) = when {
        !matrix.isSimilarity -> matrix.linearAfter(at.a, at.b, at.c, at.d) { a, b, c, d ->
            framed(matrix.mapX(at.left, at.top), matrix.mapY(at.left, at.top), w, h, a, b, c, d)
        }
        matrix.isMirror -> mirroredText(at, w, h, matrix)
        else -> {
            // The turn alone: the even scale is divided back out.
            val scale = kotlin.math.sqrt(matrix.a * matrix.d - matrix.b * matrix.c)
            val turn = Affine(matrix.a / scale, matrix.b / scale, matrix.c / scale, matrix.d / scale, 0f, 0f)
            turn.linearAfter(at.a, at.b, at.c, at.d) { a, b, c, d ->
                framed(matrix.mapX(at.left, at.top), matrix.mapY(at.left, at.top), w, h, a, b, c, d)
            }
        }
    }
    return copy(rect = rect, transform = transform)
}

/**
 * A box of [w] × [h] at [at], mirrored by [matrix] and kept readable.
 *
 * The mirror image of the box's map is `Q · L` for the mirror's unscaled part `Q`, which reads
 * backwards. Flipping it back along the box's own width, `Q · L · diag(−1, 1)`, or its height,
 * `Q · L · diag(1, −1)`, makes it read forwards again; the two differ by a half turn, and the one
 * kept is the one whose baseline is nearer the mirror image of the old one — so for both the page's
 * mirrors an upright box stays upright, whichever way the selection was flipped.
 */
private fun mirroredText(at: Frame, w: Float, h: Float, matrix: Affine): Pair<Rect, Affine> {
    val scale = kotlin.math.sqrt(-(matrix.a * matrix.d - matrix.b * matrix.c))
    val q = Affine(matrix.a / scale, matrix.b / scale, matrix.c / scale, matrix.d / scale, 0f, 0f)
    // Q · L, column by column.
    val (a, b, c, d) = q.linearAfter(at.a, at.b, at.c, at.d) { a, b, c, d -> listOf(a, b, c, d) }
    val wanted = -kotlin.math.atan2(at.b, at.a)
    val acrossWidth = kotlin.math.abs(normalisedAngle(kotlin.math.atan2(-b, -a) - wanted)) <=
        kotlin.math.abs(normalisedAngle(kotlin.math.atan2(b, a) - wanted))
    val (na, nb, nc, nd) = if (acrossWidth) listOf(-a, -b, c, d) else listOf(a, b, -c, -d)

    // The centre is the one point of the box whose mirror image is where the box has to be.
    val cx = at.left + (at.a * w + at.c * h) / 2f
    val cy = at.top + (at.b * w + at.d * h) / 2f
    val mx = matrix.mapX(cx, cy)
    val my = matrix.mapY(cx, cy)
    return framed(
        mx - (na * w + nc * h) / 2f, my - (nb * w + nd * h) / 2f, w, h, na, nb, nc, nd,
    )
}

private fun normalisedAngle(angle: Float): Float {
    val turn = (2 * Math.PI).toFloat()
    var a = angle % turn
    if (a > Math.PI) a -= turn
    if (a < -Math.PI) a += turn
    return a
}

/**
 * The same picture after a selection transform: exactly where [matrix] puts every pixel of it.
 *
 * Moved, turned, scaled, stretched and mirrored alike, so a picture in a selection goes wherever
 * the ink round it goes. How far each side is stretched is kept in the [rect] — so a picture that
 * was only ever scaled stays a plain rectangle in the file, and the editor decodes it at the size it
 * is actually shown — and what is left in the transform is the turn, and any shear or mirror, at
 * unit length.
 */
fun ImageBlock.placedBy(matrix: Affine): ImageBlock {
    if (matrix.isIdentity) return this
    val at = frame()
    val (rect, transform) = matrix.linearAfter(at.a, at.b, at.c, at.d) { a, b, c, d ->
        val across = kotlin.math.hypot(a, b).takeIf { it > 0f && it.isFinite() } ?: 1f
        val down = kotlin.math.hypot(c, d).takeIf { it > 0f && it.isFinite() } ?: 1f
        // Clamped as lengths on the page, so a picture cannot be scaled away to nothing.
        val width = (rect.width * across).coerceAtLeast(MIN_IMAGE_SIDE)
        val height = (rect.height * down).coerceAtLeast(MIN_IMAGE_SIDE)
        framed(
            matrix.mapX(at.left, at.top), matrix.mapY(at.left, at.top), width, height,
            a / across, b / across, c / down, d / down,
        )
    }
    return copy(rect = rect, transform = transform)
}

/** The smallest a picture can be scaled to, in points: small enough to tuck away, big enough to grab. */
const val MIN_IMAGE_SIDE = 12f

/**
 * Where the whole picture would be, cut-away parts and all, in the same unturned space as [rect].
 *
 * Read off the crop: the part shown fills [rect], so the whole is that much larger, and starts that
 * far before it.
 */
fun ImageBlock.uncroppedRect(): Rect {
    val fullW = rect.width / crop.width
    val fullH = rect.height / crop.height
    val left = rect.left - crop.left * fullW
    val top = rect.top - crop.top * fullH
    return Rect(left, top, left + fullW, top + fullH)
}

/**
 * The same picture showing [crop] of itself, with every pixel still shown where it was on the page.
 *
 * So a crop takes away from the edges and moves nothing: the rect shrinks or grows round the part
 * shown, and the corner it is turned about moves with it. Held to [MIN_IMAGE_SIDE] on the page, the
 * rule a scale is held to, so a crop cannot leave a picture too small to take hold of.
 */
fun ImageBlock.croppedTo(crop: Rect): ImageBlock {
    val full = uncroppedRect()
    val minU = (MIN_IMAGE_SIDE / full.width).coerceAtMost(1f)
    val minV = (MIN_IMAGE_SIDE / full.height).coerceAtMost(1f)
    val wanted = ImageCrop.clamped(crop, minU, minV)
    if (wanted == this.crop) return this

    val left = full.left + wanted.left * full.width
    val top = full.top + wanted.top * full.height
    val at = frame()
    // The new corner's offset from the old one, along the picture's own axes, carried onto the page.
    val u = left - rect.left
    val v = top - rect.top
    val (rect, transform) = framed(
        at.left + at.a * u + at.c * v, at.top + at.b * u + at.d * v,
        wanted.width * full.width, wanted.height * full.height,
        at.a, at.b, at.c, at.d,
    )
    return copy(rect = rect, transform = transform, crop = wanted)
}

/**
 * How far a move of ([dx], [dy]) on the page goes across and down the whole picture, as fractions
 * of it, written into [out].
 *
 * Along the picture's own axes, so a grip on a turned or mirrored picture drags the edge it is on.
 */
fun ImageBlock.pictureDelta(dx: Float, dy: Float, out: FloatArray) {
    val full = uncroppedRect()
    val det = transform.a * transform.d - transform.b * transform.c
    if (det == 0f || full.width <= 0f || full.height <= 0f) {
        out[0] = 0f
        out[1] = 0f
        return
    }
    out[0] = (transform.d * dx - transform.c * dy) / det / full.width
    out[1] = (-transform.b * dx + transform.a * dy) / det / full.height
}

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
