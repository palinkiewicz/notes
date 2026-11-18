package pl.dakil.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.dakil.notes.editor.markdown.MarkdownDocument
import pl.dakil.notes.editor.markdown.MarkdownStaticText
import pl.dakil.notes.editor.markdown.PendingStyles
import pl.dakil.notes.editor.markdown.rememberMarkdownDocumentState
import pl.dakil.notes.editor.markdown.rememberMarkdownStyles
import pl.dakil.notes.model.AppColorTheme
import pl.dakil.notes.model.BlendId
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.DarkThemeOption
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.Stroke
import pl.dakil.notes.model.TextBlock
import pl.dakil.notes.model.ToolId
import pl.dakil.notes.ui.sheet.InkNotePreview
import pl.dakil.notes.ui.theme.NoteContentTheme
import pl.dakil.notes.ui.theme.NotesTheme
import java.io.File

/**
 * Pins what a note looks like, pixel for pixel, so a change to the app's chrome — its theme, its
 * fonts, its component library — cannot quietly change the notes themselves.
 *
 * The note surfaces read `MaterialTheme` directly: heading sizes come from the type scale, code
 * colours from the surface's luminance, decorations from the container roles. Anything that touches
 * the theme can therefore reach into a note, and only a picture of the note shows whether it did.
 *
 * Two modes, picked with the `snapshot` instrumentation argument:
 * - `record` writes every picture to `files/snapshots/record/` in the test app's external storage.
 * - `verify` (the default) compares against the pictures in `files/snapshots/golden/`, which are a
 *   previous recording pushed back with adb, and fails on the first pixel that differs. The pictures
 *   it took are left in `files/snapshots/actual/` for a look.
 *
 * Run it with `am instrument` rather than `connectedAndroidTest`: the latter uninstalls the test app
 * afterwards, and the recordings with it.
 *
 * The goldens are a property of one device — its density, its fonts — so they are recorded on the
 * device that verifies and are not checked in.
 */
@RunWith(AndroidJUnit4::class)
class NoteRenderingSnapshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val recording = InstrumentationRegistry.getArguments().getString("snapshot") == "record"
    private val root = File(instrumentation.targetContext.getExternalFilesDir(null), "snapshots")

    private data class Look(val theme: AppColorTheme, val dark: DarkThemeOption, val pureBlack: Boolean) {
        val label = "${theme.key}-${dark.key}${if (pureBlack) "-black" else ""}"
    }

    private enum class Fixture { FORMATTED, SOURCE, STATIC, INK_PREVIEW }

    private val looks = listOf(AppColorTheme.DAKILS_NOTES, AppColorTheme.OCEAN, AppColorTheme.DYNAMIC)
        .flatMap { theme ->
            listOf(
                Look(theme, DarkThemeOption.LIGHT, false),
                Look(theme, DarkThemeOption.DARK, false),
                Look(theme, DarkThemeOption.DARK, true),
            )
        }

    @Test
    fun notes_render_exactly_as_they_did() {
        var look by mutableStateOf(looks.first())
        var fixture by mutableStateOf(Fixture.FORMATTED)

        compose.setContent {
            NotesTheme(look.theme, look.dark, look.pureBlack) {
                // Keyed so every fixture starts from a fresh composition: a lazy list's scroll or a
                // remembered layout carried over from the previous picture would make the result
                // depend on the order the pictures were taken in.
                key(look, fixture) {
                    NoteSurface {
                        when (fixture) {
                            Fixture.FORMATTED -> Document(sourceMode = false)
                            Fixture.SOURCE -> Document(sourceMode = true)
                            Fixture.STATIC -> Static()
                            Fixture.INK_PREVIEW -> InkPreview()
                        }
                    }
                }
            }
        }

        val mismatches = mutableListOf<String>()
        for (l in looks) {
            for (f in Fixture.entries) {
                look = l
                fixture = f
                compose.waitForIdle()
                val picture = compose.onRoot().captureToImage().asAndroidBitmap()
                val name = "${f.name.lowercase(java.util.Locale.ROOT)}-${l.label}.png"
                if (recording) {
                    save(picture, File(root, "record/$name"))
                } else {
                    save(picture, File(root, "actual/$name"))
                    val golden = BitmapFactory.decodeFile(File(root, "golden/$name").path)
                    val difference = compare(golden, picture)
                    if (difference != null) mismatches += "$name: $difference"
                }
            }
        }
        assertTrue(mismatches.joinToString("\n", prefix = "Notes render differently:\n"), mismatches.isEmpty())
    }

    /**
     * Where a note sits on screen, standing in for the screens that host one: inside the app theme,
     * in [NoteContentTheme] exactly as the editors and the library wrap their note content.
     *
     * The background is read inside it, from the settled scheme, which at rest is the colour the
     * screens paint behind a note.
     */
    @Composable
    private fun NoteSurface(content: @Composable () -> Unit) {
        NoteContentTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
        }
    }

    @Composable
    private fun Document(sourceMode: Boolean) {
        val state = remember { TextFieldState(MARKDOWN) }
        MarkdownDocument(
            state = state,
            sourceMode = sourceMode,
            documentState = rememberMarkdownDocumentState(),
            pending = remember { PendingStyles() },
            modifier = Modifier.fillMaxSize(),
            attributes = true,
        )
    }

    @Composable
    private fun Static() {
        MarkdownStaticText(
            markdown = MARKDOWN,
            styles = rememberMarkdownStyles(attributes = true),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }

    @Composable
    private fun InkPreview() {
        val sheet = remember { inkSheet() }
        InkNotePreview(sheet, Modifier.fillMaxWidth().aspectRatio(sheet.format.width / sheet.format.height))
    }

    private fun inkSheet(): Sheet {
        val strokes = listOf(ToolId.PEN, ToolId.FOUNTAIN_PEN, ToolId.PENCIL, ToolId.HIGHLIGHTER, ToolId.MARKER)
            .mapIndexed { i, tool ->
                val y = 420f + i * 40f
                Stroke(
                    tool,
                    if (tool == ToolId.HIGHLIGHTER) 0x80FFD600.toInt() else 0xFF1E3A8A.toInt(),
                    if (tool == ToolId.MARKER || tool == ToolId.HIGHLIGHTER) 10f else 2.5f,
                    if (tool == ToolId.HIGHLIGHTER) BlendId.MULTIPLY else BlendId.NORMAL,
                    floatArrayOf(60f, 180f, 300f, 420f, 540f),
                    floatArrayOf(y, y - 14f, y + 10f, y - 8f, y),
                )
            }
        return Sheet(
            blocks = listOf(
                TextBlock(
                    id = BlockId("t"),
                    z = 1,
                    rect = Rect(50f, 60f, 545f, 380f),
                    markdown = "# A heading\n\nSome **bold**, *italic* and `code` text.\n\n- [x] done\n- [ ] to do\n\n> a quote",
                ),
                InkBlock(id = BlockId("ink"), z = 0, rect = Rect.ZERO, strokes = strokes).withRecomputedBounds(),
            ),
        )
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Null when the two are the same picture; otherwise how many pixels differ, and where. */
    private fun compare(golden: Bitmap?, actual: Bitmap): String? {
        if (golden == null) return "no golden"
        if (golden.width != actual.width || golden.height != actual.height) {
            return "size ${actual.width}x${actual.height}, golden ${golden.width}x${golden.height}"
        }
        val w = actual.width
        val h = actual.height
        val a = IntArray(w * h).also { actual.getPixels(it, 0, w, 0, 0, w, h) }
        val g = IntArray(w * h).also { golden.getPixels(it, 0, w, 0, 0, w, h) }
        var count = 0
        var left = w; var top = h; var right = -1; var bottom = -1
        for (i in a.indices) {
            if (a[i] != g[i]) {
                count++
                val x = i % w; val y = i / w
                if (x < left) left = x; if (x > right) right = x
                if (y < top) top = y; if (y > bottom) bottom = y
            }
        }
        return if (count == 0) null else "$count pixels in [$left,$top..$right,$bottom]"
    }

    private companion object {
        /** Every construct the renderer styles from the theme, in one note. */
        val MARKDOWN = """
            # Heading one
            ## Heading two
            ### Heading three
            #### Heading four
            ##### Heading five
            ###### Heading six

            Plain text with **bold**, *italic*, ~~struck~~, `inline code` and a [link](https://example.com).
            Maths inline ${'$'}e^{i\pi}+1=0${'$'} too.

            > A quote that runs long enough to wrap onto a second line on a phone screen.

            - [x] A finished task
            - [ ] An open task
            1. First
            2. Second

            ```kotlin
            fun greet(name: String): String = "Hi, ${'$'}name" // comment
            ```

            | Column | Other |
            |--------|-------|
            | a      | b     |

            ---

            ![A picture](missing.png)
        """.trimIndent()
    }
}
