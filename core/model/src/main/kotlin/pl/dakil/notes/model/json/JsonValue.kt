package pl.dakil.notes.model.json

/**
 * A minimal, order-preserving JSON value tree.
 *
 * Why hand-rolled rather than `org.json` (which ships free in the Android framework):
 * `org.json.JSONObject` is backed by a `HashMap`, so it reorders keys on every save. `.daknote`
 * files are meant to survive git- and Syncthing-style sync, where a reordered file is a spurious
 * conflict on every write. This tree is `LinkedHashMap`-backed, so an unknown key written by a
 * future version of the app comes back out in its original position, byte for byte.
 *
 * Numbers are held as their verbatim source literal for the same reason: parsing `1755100000000`
 * into a `Double` and printing it back would emit `1.7551E12`.
 */
sealed interface JsonValue

data object JsonNull : JsonValue

@JvmInline
value class JsonBool(val value: Boolean) : JsonValue

/** Holds the literal exactly as it appeared, so unknown numbers round-trip unchanged. */
@JvmInline
value class JsonNumber(val literal: String) : JsonValue {
    fun toDouble(): Double = literal.toDouble()
    fun toFloat(): Float = literal.toFloat()
    fun toInt(): Int = literal.toDouble().toInt()
    fun toLong(): Long = literal.toDouble().toLong()

    companion object {
        fun of(v: Int): JsonNumber = JsonNumber(v.toString())
        fun of(v: Long): JsonNumber = JsonNumber(v.toString())

        /** Formats without a trailing `.0` for integral values, keeping files tidy and stable. */
        fun of(v: Float): JsonNumber = JsonNumber(
            if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        )
    }
}

@JvmInline
value class JsonString(val value: String) : JsonValue

@JvmInline
value class JsonArray(val items: List<JsonValue>) : JsonValue {
    val size: Int get() = items.size
    operator fun get(i: Int): JsonValue = items[i]

    companion object {
        val EMPTY = JsonArray(emptyList())
        fun ofStrings(values: List<String>): JsonArray = JsonArray(values.map { JsonString(it) })
        fun ofFloats(values: List<Float>): JsonArray = JsonArray(values.map { JsonNumber.of(it) })
    }
}

/**
 * An insertion-ordered JSON object. Immutable: [with] and [without] return copies that keep the
 * position of any key they replace, so editing a known field never reshuffles unknown neighbours.
 */
class JsonObject private constructor(
    private val entries: LinkedHashMap<String, JsonValue>,
) : JsonValue {

    val keys: Set<String> get() = entries.keys
    val size: Int get() = entries.size

    operator fun get(key: String): JsonValue? = entries[key]
    operator fun contains(key: String): Boolean = entries.containsKey(key)

    fun with(key: String, value: JsonValue): JsonObject {
        val copy = LinkedHashMap(entries)
        copy[key] = value // LinkedHashMap keeps the original position on replacement.
        return JsonObject(copy)
    }

    fun with(key: String, value: String): JsonObject = with(key, JsonString(value))
    fun with(key: String, value: Int): JsonObject = with(key, JsonNumber.of(value))
    fun with(key: String, value: Long): JsonObject = with(key, JsonNumber.of(value))
    fun with(key: String, value: Float): JsonObject = with(key, JsonNumber.of(value))
    fun with(key: String, value: Boolean): JsonObject = with(key, JsonBool(value))

    fun without(key: String): JsonObject {
        if (key !in entries) return this
        val copy = LinkedHashMap(entries)
        copy.remove(key)
        return JsonObject(copy)
    }

    /** Adds every entry of [other] that this object does not already define, preserving order. */
    fun withDefaults(other: JsonObject): JsonObject {
        val copy = LinkedHashMap(entries)
        for ((k, v) in other.entries) copy.putIfAbsent(k, v)
        return JsonObject(copy)
    }

    fun forEach(action: (String, JsonValue) -> Unit) = entries.forEach { (k, v) -> action(k, v) }

    // ---- Typed accessors. All tolerate a missing or wrongly typed key by returning the default,
    // ---- because a forward-compatible reader must never crash on an unexpected document.

    fun string(key: String, default: String = ""): String =
        (entries[key] as? JsonString)?.value ?: default

    fun int(key: String, default: Int = 0): Int =
        (entries[key] as? JsonNumber)?.toInt() ?: default

    fun long(key: String, default: Long = 0L): Long =
        (entries[key] as? JsonNumber)?.toLong() ?: default

    fun float(key: String, default: Float = 0f): Float =
        (entries[key] as? JsonNumber)?.toFloat() ?: default

    fun bool(key: String, default: Boolean = false): Boolean =
        (entries[key] as? JsonBool)?.value ?: default

    fun obj(key: String): JsonObject? = entries[key] as? JsonObject

    fun array(key: String): JsonArray? = entries[key] as? JsonArray

    fun stringList(key: String): List<String> =
        array(key)?.items?.mapNotNull { (it as? JsonString)?.value } ?: emptyList()

    fun floatList(key: String): List<Float> =
        array(key)?.items?.mapNotNull { (it as? JsonNumber)?.toFloat() } ?: emptyList()

    override fun equals(other: Any?): Boolean = other is JsonObject && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = JsonWriter.write(this, pretty = false)

    companion object {
        val EMPTY = JsonObject(LinkedHashMap())

        fun of(vararg pairs: Pair<String, JsonValue>): JsonObject {
            val map = LinkedHashMap<String, JsonValue>(pairs.size)
            for ((k, v) in pairs) map[k] = v
            return JsonObject(map)
        }

        /** Takes ownership of [map]; callers must not retain it. Used by the parser. */
        fun adopt(map: LinkedHashMap<String, JsonValue>): JsonObject = JsonObject(map)
    }
}
