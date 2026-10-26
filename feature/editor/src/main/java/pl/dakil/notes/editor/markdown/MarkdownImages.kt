package pl.dakil.notes.editor.markdown

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Where an image's bytes come from, given the destination written in the Markdown.
 *
 * The document names an image and only whoever opened the document knows where that name points: a
 * `.md` note's `attachments/…` is relative to the folder the note is in. Everything else — a
 * `file://` path, an `http(s)` address, a `data:` URI left by an older build — means the same
 * wherever it is read, and [DefaultImageSource] answers it.
 */
fun interface ImageSource {
    suspend fun bytes(url: String): ByteArray?
}

/** The destinations that need no document to resolve. */
object DefaultImageSource : ImageSource {

    /** Beyond this a download is not an image anyone meant to put in a note. */
    private const val MAX_DOWNLOAD = 16 * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

    override suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val src = url.trim()
        runCatching {
            when {
                src.startsWith("data:") -> {
                    val payload = src.substringAfter(',', "")
                    if (src.substringBefore(',').endsWith(";base64")) {
                        java.util.Base64.getMimeDecoder().decode(payload)
                    } else {
                        null
                    }
                }

                src.startsWith("file://") -> java.io.File(src.removePrefix("file://")).readBytes()
                src.startsWith("/") -> java.io.File(src).readBytes()
                src.startsWith("http://") || src.startsWith("https://") -> download(src)
                else -> null
            }
        }.getOrNull()
    }

    private fun download(address: String): ByteArray? {
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_DOWNLOAD) return null
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Decoded images, shared by every view of every document.
 *
 * Bounded by the memory the bitmaps *take*, not by how many there are: one photo decoded without
 * sampling is tens of megabytes. Each bitmap is sampled down to the column that asked for it, and
 * keyed by that width, because a narrower column is asking a different question.
 */
internal object ImageLoader {

    private const val MAX_BYTES = 32 * 1024 * 1024

    private val bitmaps = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    /** Natural sizes, which are tiny and asked for far more often than the pixels. */
    private val sizes = LruCache<String, IntSize>(512)

    /** One read per distinct image at a time, however many views are waiting on it. */
    private val flights = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** A key that does not hold a several-megabyte `data:` URI alive as a map key. */
    private fun keyOf(url: String): String = if (url.length > 512) "${url.length}#${url.hashCode()}" else url

    suspend fun size(url: String, source: ImageSource): IntSize? {
        val key = keyOf(url)
        sizes.get(key)?.let { return it }
        val bytes = source.bytes(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return IntSize(bounds.outWidth, bounds.outHeight).also { sizes.put(key, it) }
    }

    suspend fun bitmap(url: String, widthPx: Int, source: ImageSource): ImageBitmap? {
        val key = "$widthPx|${keyOf(url)}"
        bitmaps.get(key)?.let { return it }
        var owner = false
        val flight = synchronized(flights) {
            flights[key] ?: CompletableDeferred<ImageBitmap?>().also {
                flights[key] = it
                owner = true
            }
        }
        if (owner) {
            // Finished whatever happens to the caller. The first view to ask is often recomposed
            // away mid-decode — learning the size is what recomposes it — and a cancelled owner
            // used to complete the flight with nothing, leaving every view waiting on it a tile.
            withContext(NonCancellable) {
                val decoded = try {
                    val bytes = source.bytes(url)
                    if (bytes == null) null else withContext(Dispatchers.Default) { decode(bytes, widthPx) }
                } catch (_: Exception) {
                    null
                }
                if (decoded != null) bitmaps.put(key, decoded)
                synchronized(flights) { flights.remove(key) }
                flight.complete(decoded)
            }
        }
        return flight.await()
    }

    /** Decodes [bytes] sampled down to roughly [widthPx] wide, or whole when no width is given. */
    fun decode(bytes: ByteArray, widthPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = if (widthPx > 0) sampleFor(bounds.outWidth, widthPx) else 1
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    /** The largest power of two that keeps the decoded width at or above [width]. */
    private fun sampleFor(sourceWidth: Int, width: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= width && sample < 64) sample *= 2
        return sample
    }
}

/**
 * How tall each block image is, for the layout that has to make room for it.
 *
 * The text field cannot hold a picture, only text — so a block image is a line of text made exactly
 * as tall as the picture, with the picture laid over it. That line height is the one piece of the
 * picture the *layout* has to know, and it is known only once the image's natural size has been
 * read. Until then the line is a placeholder height, and when the size arrives [version] moves:
 * both the output transformation and the static renderer read it, so the paragraph is laid out
 * again with the room it really needs.
 */
@Stable
class MarkdownImages internal constructor(
    val source: ImageSource,
    private val density: Density,
) {
    private val sizes = mutableStateMapOf<String, IntSize>()
    private val failed = mutableStateMapOf<String, Boolean>()

    private var column by mutableIntStateOf(0)

    /**
     * The width of the column images are laid out in, in pixels. Zero until it is measured.
     *
     * A new width moves [version] too: turning the phone changes how tall every picture is.
     */
    var columnPx: Int
        get() = column
        set(value) {
            if (value == column) return
            column = value
            version++
        }

    /**
     * Moves whenever a size is learned or a read fails.
     *
     * A text field does not promise to run its output transformation again because snapshot state
     * read inside it changed, and when it did not, a newly inserted picture kept the placeholder's
     * height until something else — the keyboard, a rotation — happened to lay the field out. So
     * the transformation is rebuilt on this instead, which the field is obliged to notice.
     */
    var version by mutableIntStateOf(0)
        private set

    /** The natural size of [url], or null while it is not yet known. */
    fun sizeOf(url: String): IntSize? = sizes[url]

    fun hasFailed(url: String): Boolean = failed[url] == true

    /** Reads [url]'s natural size, once. */
    suspend fun measure(url: String) {
        if (url in sizes || url in failed) return
        val size = ImageLoader.size(url, source)
        if (size == null) failed[url] = true else sizes[url] = size
        version++
    }

    private fun column(): Int = column.takeIf { it > 0 } ?: with(density) { DefaultColumn.roundToPx() }

    /**
     * How large [url] is drawn, in pixels: its natural size read as if taken on a 2× screen, no
     * wider than the column, and no taller than [MaxHeightRatio] columns.
     *
     * Both limits scale the picture as a whole. Capping only the height of a tall one — a phone
     * screenshot is 20:9 — left it narrower than the box kept for it, letterboxed against the left
     * edge with its right-hand corners square.
     */
    fun displaySize(url: String): IntSize {
        val column = column()
        val natural = sizes[url]
        return when {
            failed[url] == true -> IntSize(column, with(density) { FailedHeight.roundToPx() })
            natural == null || natural.width <= 0 -> IntSize(column, with(density) { PendingHeight.roundToPx() })
            else -> {
                var width = (natural.width * density.density / 2f).coerceAtMost(column.toFloat())
                var height = width * natural.height / natural.width
                val tallest = column * MaxHeightRatio
                if (height > tallest) {
                    width *= tallest / height
                    height = tallest
                }
                IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
            }
        }
    }

    /** The paragraph style that makes [url]'s line as tall as the picture. */
    fun paragraphFor(url: String): ParagraphStyle {
        val px = displaySize(url).height + with(density) { (ImageGap * 2).roundToPx() }
        // sp to px is `fontScale × density`, so a font-scale setting does not shrink the room.
        val sp = px / (density.density * density.fontScale)
        return ParagraphStyle(
            lineHeight = sp.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    }

    companion object {
        /** What an image is laid out against before the column has been measured. */
        val DefaultColumn = 320.dp

        /** The room kept for an image whose size is still being read. */
        val PendingHeight = 160.dp

        /** The room an image that cannot be shown takes: one line to say what it was. */
        val FailedHeight = 40.dp

        /**
         * Clear space above and below each picture, inside its own line. Small, because two
         * pictures on consecutive lines are meant to read as a pair, not as two paragraphs.
         */
        val ImageGap = 4.dp

        /** The tallest a picture is drawn, in widths of the column. */
        const val MaxHeightRatio = 1.5f
    }
}

/** One [MarkdownImages] per view, resolving through [source]. */
@Composable
fun rememberMarkdownImages(source: ImageSource? = null): MarkdownImages {
    val density = LocalDensity.current
    return remember(source ?: DefaultImageSource, density) {
        MarkdownImages(source ?: DefaultImageSource, density)
    }
}

/**
 * A block image, drawn over the line the plan made tall for it.
 *
 * Positioned from the laid-out line rather than from any size of its own, so it sits exactly in the
 * room the text has kept — which is the only reason nothing overlaps it. [scroll] is how far the
 * text has been scrolled under its container, zero for text that does not scroll.
 */
@Composable
fun BoxScope.MarkdownImage(
    image: MdImage,
    layout: TextLayoutResult,
    images: MarkdownImages,
    scroll: Int = 0,
) {
    val url = image.url
    val size = images.displaySize(url)
    val known = images.sizeOf(url) != null
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    // One effect, in order: the size first — the layout is waiting on it — and then the pixels at
    // the width that size settled on. Two effects keyed separately left the decode never starting
    // when the size arrived without changing the width, which is why a new picture stayed a tile.
    LaunchedEffect(url, size.width, known) {
        images.measure(url)
        if (images.sizeOf(url) != null) bitmap = ImageLoader.bitmap(url, images.displaySize(url).width, images.source)
    }

    val at = image.offset.coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(at)
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)
    val density = LocalDensity.current
    // Centred in the room the line kept, and sized from the picture rather than from the line: for
    // the frame or two before the layout catches up with a newly learned size, the line is still
    // the placeholder's height, and a picture sized to it would be drawn the wrong shape.
    val y = top + ((bottom - top) - size.height) / 2f
    val shape = RoundedCornerShape(8.dp)
    val box = with(density) {
        Modifier
            .offset { IntOffset(0, y.roundToInt() - scroll) }
            // Unbounded, because the box this sits in is the field's *viewport*: a plain `size`
            // is clamped to it, so a picture taller than what is on screen — any tall one with the
            // keyboard up, or with the phone turned — was squeezed to fit, out of its aspect, in
            // the room the text still kept for it at full size.
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(size.width.toDp(), size.height.toDp())
            .clip(shape)
    }

    val decoded = bitmap
    when {
        decoded != null -> Image(
            bitmap = decoded,
            contentDescription = image.alt.ifBlank { null },
            contentScale = ContentScale.FillBounds,
            modifier = box,
        )

        // Said in words rather than with a broken-picture icon: the alt text is what the author
        // wrote to stand in for the image, and it is the one thing that says which image is gone.
        images.hasFailed(url) -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = image.alt.ifBlank { url.substringAfterLast('/').take(80) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        else -> Box(box.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
