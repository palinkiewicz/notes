package pl.dakil.notes.format

import pl.dakil.notes.model.ShapeSpec
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonNumber
import pl.dakil.notes.model.json.JsonObject

/**
 * Persists what an auto-snapped stroke *is*, alongside the ink that draws it.
 *
 * ### Why this lives in `sheet.json` and not in the stroke stream
 *
 * The obvious home would be a field on each stroke in the `.dsv` binary. That format has no room
 * for one: its records are fixed positional fields with no length prefix and file-global flags, so
 * a reader that does not expect an extra byte does not skip it — it reads it as the next
 * coordinate and desynchronises from there. Worse, the failure is silent: `StrokeCodec.readLenient`
 * turns the resulting parse error into an empty stroke list, so an older build would open the note
 * showing a blank ink layer and write that blankness back on the next save. Making that safe means
 * raising `minReaderVersion`, which locks every note containing a single shape out of older builds
 * entirely.
 *
 * A block descriptor, by contrast, already carries unknown keys through untouched — the mechanism
 * `sheet.json`'s `pages` key was added with. So this is an optional key with an absent default,
 * needing no version bump, and a build that has never heard of it renders the shape correctly as
 * the ordinary stroke it already is.
 *
 * ### Why each record names its stroke's point count
 *
 * Strokes have no identity beyond their position in the layer's list, so a record has to say which
 * index it describes. If an older build then erases a stroke from that layer, every later index
 * shifts and the records would silently describe the wrong strokes. [POINT_COUNT] is the check
 * against that: a record whose stroke is not the length it expected is dropped, and the shape
 * degrades to plain ink rather than lying about a stroke it has never seen.
 */
internal object ShapeJson {

    /** The `shapes` array for a layer, or null when none of its strokes is a recognised shape. */
    fun write(strokes: List<Stroke>): JsonArray? {
        var out: MutableList<JsonObject>? = null
        for (i in strokes.indices) {
            val spec = strokes[i].shape ?: continue
            val list = out ?: ArrayList<JsonObject>(4).also { out = it }
            list += describe(spec)
                .with(INDEX, i)
                .with(POINT_COUNT, strokes[i].pointCount)
        }
        return out?.let { JsonArray(it) }
    }

    /** Returns [strokes] with any shape the array describes attached to the stroke it names. */
    fun attach(strokes: List<Stroke>, shapes: JsonArray?): List<Stroke> {
        if (shapes == null || shapes.size == 0 || strokes.isEmpty()) return strokes
        var result: MutableList<Stroke>? = null
        for (item in shapes.items) {
            val json = item as? JsonObject ?: continue
            val index = json.int(INDEX, -1)
            if (index !in strokes.indices) continue
            val target = result?.get(index) ?: strokes[index]
            if (target.pointCount != json.int(POINT_COUNT, -1)) continue
            val spec = parse(json) ?: continue
            val list = result ?: ArrayList(strokes).also { result = it }
            list[index] = target.withShape(spec)
        }
        return result ?: strokes
    }

    private fun describe(spec: ShapeSpec): JsonObject = when (spec) {
        is ShapeSpec.Line -> JsonObject.of(KIND to kind(LINE))
            .with("x0", spec.x0).with("y0", spec.y0)
            .with("x1", spec.x1).with("y1", spec.y1)

        is ShapeSpec.Poly -> JsonObject.of(KIND to kind(POLY))
            .with("pts", interleave(spec))

        is ShapeSpec.Rect -> JsonObject.of(KIND to kind(RECT))
            .with("cx", spec.cx).with("cy", spec.cy)
            .with("hw", spec.hw).with("hh", spec.hh)
            .with("rot", spec.rot).with(EQUAL, spec.equilateral)

        is ShapeSpec.Ngon -> JsonObject.of(KIND to kind(NGON))
            .with("cx", spec.cx).with("cy", spec.cy)
            .with("r", spec.r).with("rot", spec.rot)
            .with("sides", spec.sides)

        is ShapeSpec.Ellipse -> JsonObject.of(KIND to kind(ELLIPSE))
            .with("cx", spec.cx).with("cy", spec.cy)
            .with("rx", spec.rx).with("ry", spec.ry)
            .with("rot", spec.rot).with(EQUAL, spec.equilateral)
    }

    /** Null for anything this build does not recognise, the way an unknown tool ordinal degrades. */
    private fun parse(json: JsonObject): ShapeSpec? = when (json.string(KIND)) {
        LINE -> ShapeSpec.Line(json.float("x0"), json.float("y0"), json.float("x1"), json.float("y1"))

        POLY -> {
            val pts = json.floatList("pts")
            if (pts.size < 6 || pts.size % 2 != 0) null else {
                val n = pts.size / 2
                ShapeSpec.Poly(FloatArray(n) { pts[it * 2] }, FloatArray(n) { pts[it * 2 + 1] })
            }
        }

        RECT -> ShapeSpec.Rect(
            json.float("cx"), json.float("cy"), json.float("hw"), json.float("hh"),
            json.float("rot"), json.bool(EQUAL),
        )

        NGON -> json.int("sides").takeIf { it >= 3 }?.let {
            ShapeSpec.Ngon(json.float("cx"), json.float("cy"), json.float("r"), json.float("rot"), it)
        }

        ELLIPSE -> ShapeSpec.Ellipse(
            json.float("cx"), json.float("cy"), json.float("rx"), json.float("ry"),
            json.float("rot"), json.bool(EQUAL),
        )

        else -> null
    }

    private fun interleave(poly: ShapeSpec.Poly): JsonArray {
        val out = ArrayList<pl.dakil.notes.model.json.JsonValue>(poly.vertexCount * 2)
        for (i in 0 until poly.vertexCount) {
            out += JsonNumber.of(poly.xs[i])
            out += JsonNumber.of(poly.ys[i])
        }
        return JsonArray(out)
    }

    private fun kind(name: String) = pl.dakil.notes.model.json.JsonString(name)

    const val KEY = "shapes"

    private const val KIND = "kind"
    private const val INDEX = "i"
    private const val POINT_COUNT = "n"
    private const val EQUAL = "eq"

    private const val LINE = "line"
    private const val POLY = "poly"
    private const val RECT = "rect"
    private const val NGON = "ngon"
    private const val ELLIPSE = "ellipse"
}
