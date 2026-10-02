package pl.dakil.notes.editor.markdown

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred

/**
 * Markdown rendered for reading rather than for editing.
 *
 * A sheet can carry any number of text boxes and only one of them is ever being typed in, so the
 * rest are drawn from the same [MarkdownRenderPlan] without a text field behind them. That is not
 * only cheaper — a `TextFieldState` per box would put every box's undo history and input connection
 * on the heap at once — it is what keeps a tap on a box a tap on a *box*, to be routed by the tool
 * in the user's hand, rather than something a field has already swallowed to place a caret.
 *
 * The output is the same by construction: the same plan, the same [MarkdownStyles], the same
 * decorations drawn through the same painter. A box that gains focus changes which component is
 * mounted, and nothing about how the text looks.
 */
@Composable
fun MarkdownStaticText(
    markdown: String,
    styles: MarkdownStyles,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onToggleTask: ((sourceMark: Int, checked: Boolean) -> Unit)? = null,
    images: ImageRefsSupport? = null,
) {
    val palette = rememberMarkdownDecorationPalette()
    val colors = MaterialTheme.colorScheme
    val plan = remember(markdown) { MarkdownRenderer.plan(markdown) }
    val rendered = remember(plan, styles) { plan.toAnnotatedString(markdown, styles) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Line height unspecified for the same reason the editable field leaves it so: with it fixed, a
    // heading's larger glyphs are clipped by the body line height.
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = colors.onSurface,
        lineHeight = TextUnit.Unspecified,
    )

    Box(
        modifier.fillMaxWidth().drawBehind {
            val result = layout ?: return@drawBehind
            drawMarkdownDecorations(plan.decorations, result, palette)
        }
    ) {
        if (markdown.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Text(
            text = rendered,
            style = textStyle,
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layout = it },
        )
        val result = layout
        if (result != null) {
            for (decoration in plan.decorations) {
                if (decoration is MdTask && onToggleTask != null) {
                    StaticTaskCheckbox(decoration, result, onToggleTask)
                }
                if (decoration is MdImage) {
                    StaticImageBlock(decoration, result, images)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.StaticImageBlock(
    image: MdImage,
    layout: TextLayoutResult,
    images: ImageRefsSupport?,
) {
    // Decoded off the composition and held in state: a multi-megabyte payload decoded synchronously
    // here would stall the very frame that is drawing the paragraph above it. Until it lands the
    // block draws nothing - the def line it answers takes no room, so nothing below it jumps.
    var bitmap by remember(image.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image.url, layout.size.width) {
        bitmap = images?.resolveImage(image.url, layout.size.width)
            ?: ImageLoader.load(image.url, layout.size.width)
    }
    val decoded = bitmap ?: return
    val at = image.offset.coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(at)

    androidx.compose.foundation.Image(
        bitmap = decoded,
        contentDescription = image.alt,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .offset {
                val top = layout.getLineTop(line)
                IntOffset(0, top.roundToInt())
            },
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
    )
}

internal object ImageLoader {

    /**
     * What the cache holds when everything on screen is decoded, counted in the memory the entries
     * *take* rather than in how many there are. A photo decoded without sampling runs to tens of
     * megabytes; counting entries would admit hundreds of them and take the heap with it.
     */
    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache =
        object : android.util.LruCache<String, ImageBitmap>(MAX_BYTES) {
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.asAndroidBitmap().allocationByteCount
        }

    /** One decode per distinct source, however many blocks are waiting on it. */
    private val flights = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()

    /**
     * Decodes [urlOrBase64] to roughly [widthPx] wide, or to its natural size when [widthPx] is
     * not given.
     *
     * The decode runs off the main thread - a multi-megabyte payload takes the frame budget with
     * it - and is sampled down to the view that asked for it: showing a 4000-pixel photo in a
     * column the size of a paragraph is a full-resolution decode for a fraction of the pixels.
     * The result is cached per width, because a narrower view is asking a different question.
     */
    suspend fun load(urlOrBase64: String, widthPx: Int = 0): ImageBitmap? {
        val trimmed = urlOrBase64.trim()
        if (trimmed.isEmpty()) return null
        val key = "$widthPx/${trimmed.hashCode()}"
        cache.get(key)?.let { return it }

        val flight = synchronized(flights) { flights.getOrPut(trimmed) { CompletableDeferred() } }
        if (!flight.isCompleted) {
            val decoded = try {
                withContext(Dispatchers.Default) { decode(trimmed, widthPx) }
            } catch (_: Exception) {
                null
            }
            flight.complete(decoded)
            synchronized(flights) { flights.remove(trimmed) }
        }
        val bitmap = flight.await()
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(src: String, widthPx: Int): ImageBitmap? {
        return try {
            if (src.startsWith("file://") || src.startsWith("/")) {
                sampleDecodeFile(src.removePrefix("file://"), widthPx)
            } else {
                val bytes = if (src.contains(";base64,")) {
                    java.util.Base64.getDecoder().decode(src.substringAfter(";base64,").trim())
                } else if (src.startsWith("data:")) {
                    java.util.Base64.getDecoder().decode(src.substringAfter(",").trim())
                } else {
                    return null
                }
                sampleDecodeBytes(bytes, widthPx)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleDecodeBytes(bytes: ByteArray, widthPx: Int): ImageBitmap? {
        val bitmap = if (widthPx <= 0) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, widthPx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        return bitmap?.asImageBitmap()
    }

    private fun sampleDecodeFile(path: String, widthPx: Int): ImageBitmap? {
        val bitmap = if (widthPx <= 0) {
            BitmapFactory.decodeFile(path)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, widthPx)
            }
            BitmapFactory.decodeFile(path, options)
        }
        return bitmap?.asImageBitmap()
    }

    /** The largest power of two that keeps both sides of the decoded bitmap at or above [width]. */
    private fun sampleFor(sourceWidth: Int, sourceHeight: Int, width: Int): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= width &&
            sourceHeight / (sample * 2) >= width &&
            sample < 32
        ) {
            sample *= 2
        }
        return sample
    }
}

/**
 * The one control a box keeps while it is *not* being edited.
 *
 * Ticking something off a list is not editing it — it is the reason the list is on the page — and
 * making the user tap into the box, hunt for the caret and tap again would be a poor trade for the
 * consistency of having no controls out here at all.
 */
@Composable
private fun BoxScope.StaticTaskCheckbox(
    task: MdTask,
    layout: TextLayoutResult,
    onToggle: (sourceMark: Int, checked: Boolean) -> Unit,
) {
    val at = task.offset.coerceIn(0, layout.layoutInput.text.length)
    val line = layout.getLineForOffset(at)

    // No 48 dp minimum, for the same reason the editable one has none: it would reach a line above
    // and a line below and eat the taps meant for them.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Checkbox(
            checked = task.checked,
            onCheckedChange = { onToggle(task.sourceMark, task.checked) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val top = layout.getLineTop(line)
                    val height = layout.getLineBottom(line) - top
                    IntOffset(
                        x = (layout.getHorizontalPosition(at, true) - CheckboxPadding.toPx())
                            .roundToInt(),
                        y = (top + (height - CheckboxTarget.toPx()) / 2f).roundToInt(),
                    )
                }
                .size(CheckboxTarget),
        )
    }
}

private val CheckboxTarget = 24.dp
private val CheckboxPadding = 2.dp

/**
 * The plan, spelled out as text that can be handed to a `Text`.
 *
 * The same two passes `MarkdownOutputTransformation` makes against a text field's buffer, made here
 * against a string instead: hide the syntax back to front so each edit's offsets are still valid
 * when its turn comes, then hang the styles on what is left. The plan's style ranges are already in
 * post-edit coordinates, which is what lets the second pass be a straight loop.
 */
fun MarkdownRenderPlan.toAnnotatedString(source: String, styles: MarkdownStyles): AnnotatedString {
    val text = StringBuilder(source)
    for (edit in edits.asReversed()) {
        if (edit.start in 0..edit.end && edit.end <= text.length) {
            text.replace(edit.start, edit.end, edit.replacement)
        }
    }
    return AnnotatedString.Builder(text.toString()).apply {
        for (range in this@toAnnotatedString.styles) {
            if (range.end > range.start && range.end <= text.length) {
                addStyle(styles.spanFor(range.style, range.arg), range.start, range.end)
            }
        }
        for (range in this@toAnnotatedString.indents) {
            if (range.end > range.start && range.end <= text.length) {
                addStyle(styles.quoteIndent, range.start, range.end)
            }
        }
    }.toAnnotatedString()
}
