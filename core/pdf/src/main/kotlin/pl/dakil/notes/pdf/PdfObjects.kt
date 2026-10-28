package pl.dakil.notes.pdf

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A PDF object, as the file states it.
 *
 * Only what copying a page needs: no font, image or colour-space semantics, because nothing here
 * draws anything. A page is carried from one file into another as objects, and the point of doing it
 * that way is that every byte of its fonts and pictures arrives as it left.
 */
sealed interface PdfObject

data object PdfNull : PdfObject

data class PdfBool(val value: Boolean) : PdfObject

/**
 * A number, held as the text it was written as.
 *
 * So a copied page writes `0.1` back as `0.1`, not as whatever a float makes of it, and an integer
 * that happens to exceed an `Int` is still passed through intact.
 */
class PdfNumber(val text: String) : PdfObject {
    fun toDouble(): Double = text.toDoubleOrNull() ?: 0.0
    fun toFloat(): Float = toDouble().toFloat()
    fun toInt(): Int = text.toIntOrNull() ?: toDouble().toInt()
    fun toLong(): Long = text.toLongOrNull() ?: toDouble().toLong()

    override fun equals(other: Any?): Boolean = other is PdfNumber && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text

    companion object {
        fun of(value: Int): PdfNumber = PdfNumber(value.toString())
        fun of(value: Long): PdfNumber = PdfNumber(value.toString())
        fun of(value: Double): PdfNumber = PdfNumber(formatNumber(value))
        fun of(value: Float): PdfNumber = of(value.exact())
    }
}

/**
 * A real number as PDF syntax can hold it.
 *
 * Never `Float.toString`, which writes `1.0E-5` — not a PDF number at all — and never
 * `String.format`, which writes `1,5` on a Polish device. Five decimals is a hundred-thousandth of a
 * point, far below anything a printer can resolve.
 */
fun formatNumber(value: Double): String {
    if (!value.isFinite()) return "0"
    val text = BigDecimal(value).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}

/**
 * The double a float was written as, not the one it is: `841.89f` widens to `841.8900146…`, which
 * is the same float but would be printed as a different page size.
 */
fun Float.exact(): Double = if (isFinite()) toString().toDouble() else 0.0

/** A name, without its slash and with `#xx` escapes already decoded. */
data class PdfName(val value: String) : PdfObject

/** A string's bytes, which are not necessarily text, and whether it was written in hex. */
class PdfString(val bytes: ByteArray, val hex: Boolean = false) : PdfObject {
    override fun equals(other: Any?): Boolean = other is PdfString && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

data class PdfArray(val items: List<PdfObject>) : PdfObject {
    operator fun get(i: Int): PdfObject = items[i]
    val size: Int get() = items.size
}

/** A dictionary, in the order the file wrote it, so a copy reads the way the original did. */
class PdfDict(val entries: Map<String, PdfObject>) : PdfObject {
    operator fun get(key: String): PdfObject? = entries[key]
    operator fun contains(key: String): Boolean = key in entries

    fun with(key: String, value: PdfObject): PdfDict = PdfDict(LinkedHashMap(entries).also { it[key] = value })
    fun without(key: String): PdfDict =
        if (key !in entries) this else PdfDict(LinkedHashMap(entries).also { it.remove(key) })

    fun name(key: String): String? = (entries[key] as? PdfName)?.value

    override fun equals(other: Any?): Boolean = other is PdfDict && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()

    companion object {
        val EMPTY = PdfDict(emptyMap())
        fun of(vararg pairs: Pair<String, PdfObject>): PdfDict = PdfDict(linkedMapOf(*pairs))
    }
}

/** An indirect reference: object [num], generation [gen]. */
data class PdfRef(val num: Int, val gen: Int = 0) : PdfObject

/**
 * A stream: its dictionary and its bytes, still encoded as the file has them.
 *
 * The bytes are fetched on demand rather than held, because the streams in a scanned PDF are its
 * page images, and a copy wants each of them once, in passing, not all of them at once.
 */
class PdfStream(val dict: PdfDict, private val load: () -> ByteArray) : PdfObject {
    constructor(dict: PdfDict, bytes: ByteArray) : this(dict, { bytes })

    /** The encoded bytes. */
    fun raw(): ByteArray = load()
}
