package pl.dakil.notes.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/**
 * An image ready to be put into a note: its bytes, and the name it will be stored under.
 *
 * [fileName] is derived from the content, not from the note or the picker. That is what lets one
 * picture inserted twice be stored once, and what keeps a note's rename from having anything to do
 * with its images.
 */
class ImportedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val sha: String,
) {
    val fileName: String get() = "$sha.$extension"
}

/**
 * Reads an image the user picked into something a note can keep.
 *
 * A photo straight off a phone camera is often 4000 pixels across and several megabytes, and a note
 * shows it a column wide. So an image larger than [MAX_EDGE] is scaled down once, here, rather than
 * carried at full size into every save, every sync and every backup. Anything already small enough
 * keeps its exact bytes — a PNG diagram stays a lossless PNG, a GIF stays a GIF.
 *
 * Re-encoded too: a JPEG whose EXIF says "rotate me", because what draws it later reads pixels and
 * not tags, and would show it lying on its side; and anything that is not PNG, JPEG, WebP or GIF —
 * HEIC above all — because the note has to open in other Markdown tools as well as this one.
 */
object ImageImport {

    /** The longest side an imported image is kept at. Enough for a full-width tablet column. */
    const val MAX_EDGE = 2560

    private const val JPEG_QUALITY = 90

    /** What a picker can hand over before it is not an image anyone meant to put in a note. */
    private const val MAX_INPUT = 64 * 1024 * 1024

    private val KEPT = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /** Reads [uri] through [resolver]. Blocking: call it off the main thread. */
    fun read(resolver: ContentResolver, uri: Uri): ImportedImage {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_INPUT) throw StoreException("The image is too large")
            }
            out.toByteArray()
        } ?: throw StoreException("Could not read the image")
        return normalise(bytes, resolver.getType(uri))
    }

    /**
     * Brings [bytes] within the limits above, re-encoding only when one of them requires it.
     *
     * [mimeHint] is what the provider claimed; the bytes themselves are trusted over it where the two
     * disagree, because a file manager's guess is made from the name.
     */
    fun normalise(bytes: ByteArray, mimeHint: String?): ImportedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mime = (bounds.outMimeType ?: mimeHint)?.lowercase(Locale.ROOT)
        val rotated = mime == "image/jpeg" && isRotated(bytes)
        val tooLarge = maxOf(bounds.outWidth, bounds.outHeight) > MAX_EDGE
        val known = mime in KEPT && bounds.outWidth > 0

        if (known && !rotated && !tooLarge) {
            return describe(bytes, mime!!, bounds.outWidth, bounds.outHeight)
        }
        return reencode(bytes)
    }

    /** Takes [bytes] exactly as they are — for images already in a note, which are not ours to recompress. */
    fun keep(bytes: ByteArray, mimeHint: String?): ImportedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mime = (bounds.outMimeType ?: mimeHint ?: "image/png").lowercase(Locale.ROOT)
        return describe(bytes, mime, bounds.outWidth, bounds.outHeight)
    }

    private fun reencode(bytes: ByteArray): ImportedImage {
        // ImageDecoder rather than BitmapFactory: it applies the EXIF orientation itself and reads
        // HEIF, and it is always there at minSdk 29.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val long = maxOf(width, height)
            if (long > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / long
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val alpha = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        bitmap.compress(
            if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            JPEG_QUALITY,
            out,
        )
        val encoded = out.toByteArray()
        val result = describe(encoded, if (alpha) "image/png" else "image/jpeg", bitmap.width, bitmap.height)
        bitmap.recycle()
        return result
    }

    private fun isRotated(bytes: ByteArray): Boolean = runCatching {
        val orientation = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED
    }.getOrDefault(false)

    private fun describe(bytes: ByteArray, mime: String, width: Int, height: Int) = ImportedImage(
        bytes = bytes,
        mimeType = mime,
        extension = extensionFor(mime),
        width = width,
        height = height,
        sha = shaOf(bytes),
    )

    /** The first 16 hex digits of the SHA-256: 64 bits, far past any chance of two images colliding. */
    fun shaOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            hex.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return hex.toString()
    }

    fun extensionFor(mime: String): String = when (mime.lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        else -> "png"
    }

    /** The MIME type a file called [name] should be recorded with. */
    fun mimeForName(name: String): String = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }

    private const val HEX = "0123456789abcdef"
}
