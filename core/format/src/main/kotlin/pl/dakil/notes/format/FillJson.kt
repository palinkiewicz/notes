package pl.dakil.notes.format

import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonObject

/**
 * Persists which strokes in a layer are filled in as well as drawn round.
 *
 * A sibling of [ShapeJson], and for the same reasons — read that file for the long form. In short:
 * the `.dsv` stroke stream has fixed positional fields and no length prefix, so an extra flag in it
 * desynchronises an older reader silently and would need a `minReaderVersion` bump to be safe. The
 * block descriptor carries unknown keys through untouched, so this is an optional key with an
 * absent default: a note with nothing filled is written exactly as it was before fills existed, and
 * a build that has never heard of them draws every stroke as the outline it already is.
 *
 * Records are `{i, n}` — the stroke's index in the layer and the point count it is expected to
 * have. The count is the same guard [ShapeJson] uses: strokes have no identity beyond list
 * position, so an older build erasing one shifts every later index, and without the check the fill
 * would silently land on a different stroke.
 *
 * No colour is stored, because a fill has none of its own: it is the stroke's own colour, so
 * recolouring moves the outline and the interior together. See `Stroke.filled`.
 */
internal object FillJson {

    /** The `fills` array for a layer, or null when none of its strokes is filled. */
    fun write(strokes: List<Stroke>): JsonArray? {
        var out: MutableList<JsonObject>? = null
        for (i in strokes.indices) {
            if (!strokes[i].filled) continue
            val list = out ?: ArrayList<JsonObject>(4).also { out = it }
            list += JsonObject.EMPTY.with(INDEX, i).with(POINT_COUNT, strokes[i].pointCount)
        }
        return out?.let { JsonArray(it) }
    }

    /** Returns [strokes] with every stroke the array names marked filled. */
    fun attach(strokes: List<Stroke>, fills: JsonArray?): List<Stroke> {
        if (fills == null || fills.size == 0 || strokes.isEmpty()) return strokes
        var result: MutableList<Stroke>? = null
        for (item in fills.items) {
            val json = item as? JsonObject ?: continue
            val index = json.int(INDEX, -1)
            if (index !in strokes.indices) continue
            val target = result?.get(index) ?: strokes[index]
            if (target.pointCount != json.int(POINT_COUNT, -1)) continue
            val list = result ?: ArrayList(strokes).also { result = it }
            list[index] = target.withFill(true)
        }
        return result ?: strokes
    }

    const val KEY = "fills"

    private const val INDEX = "i"
    private const val POINT_COUNT = "n"
}
