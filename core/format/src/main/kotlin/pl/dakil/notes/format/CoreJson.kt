package pl.dakil.notes.format

import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.StrokeCore
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject

/**
 * Persists the inside colour of a layer's two-tone marker strokes.
 *
 * A sibling of [FillJson] and [ShapeJson], and for the same reason: the `.dsv` stroke stream has
 * fixed positional fields, so a second colour in it would desynchronise an older reader. Here it is
 * an optional key, absent when nothing in the layer is two-tone, so a note without marker ink is
 * written byte-for-byte as before. A build that has never heard of it draws the stroke as a thick
 * line in its outline colour, which keeps every word legible.
 *
 * Records are `{i, n, c, r}`: index, the point count it is expected to have (the guard against an
 * older build's erase shifting every later index — see [FillJson]), the inside colour as hex, and
 * the rim as a share of the width.
 */
internal object CoreJson {

    /** The `cores` array for a layer, or null when none of its strokes is two-tone. */
    fun write(strokes: List<Stroke>): JsonArray? {
        var out: MutableList<JsonObject>? = null
        for (i in strokes.indices) {
            val core = strokes[i].core ?: continue
            val list = out ?: ArrayList<JsonObject>(4).also { out = it }
            list += JsonObject.EMPTY
                .with(INDEX, i)
                .with(POINT_COUNT, strokes[i].pointCount)
                .with(COLOR, ColorCodec.toHex(core.color))
                .with(RIM, core.rim)
        }
        return out?.let { JsonArray(it) }
    }

    /** Returns [strokes] with every stroke the array names given its inside. */
    fun attach(strokes: List<Stroke>, cores: JsonArray?): List<Stroke> {
        if (cores == null || cores.size == 0 || strokes.isEmpty()) return strokes
        var result: MutableList<Stroke>? = null
        for (item in cores.items) {
            val json = item as? JsonObject ?: continue
            val index = json.int(INDEX, -1)
            if (index !in strokes.indices) continue
            val target = result?.get(index) ?: strokes[index]
            if (target.pointCount != json.int(POINT_COUNT, -1)) continue
            val color = ColorCodec.parse(json.string(COLOR, "")) ?: continue
            val rim = json.float(RIM, Float.NaN).takeIf { it.isFinite() }
                ?.coerceIn(StrokeCore.RIM_MIN, StrokeCore.RIM_MAX) ?: continue
            val list = result ?: ArrayList(strokes).also { result = it }
            list[index] = target.withCore(StrokeCore(color, rim))
        }
        return result ?: strokes
    }

    const val KEY = "cores"

    private const val INDEX = "i"
    private const val POINT_COUNT = "n"
    private const val COLOR = "c"
    private const val RIM = "r"
}
