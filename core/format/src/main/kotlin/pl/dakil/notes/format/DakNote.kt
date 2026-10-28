package pl.dakil.notes.format

import pl.dakil.notes.model.Note
import pl.dakil.notes.model.NoteId
import pl.dakil.notes.model.NoteMeta
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.Rect
import pl.dakil.notes.model.Sheet
import pl.dakil.notes.model.BlockId
import pl.dakil.notes.model.InkBlock
import pl.dakil.notes.model.MediaData
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PdfSourcePage
import pl.dakil.notes.model.withPdfPagesInserted
import pl.dakil.notes.model.ViewMode
import java.util.UUID

class DakNoteFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Constants and factory helpers for the `.daknote` container.
 *
 * The container is a ZIP. `java.util.zip` is already in the framework so it costs nothing, it
 * streams (which matters over the Storage Access Framework), and — most importantly — it gives
 * unknown future content somewhere to live untouched: an entry this build has never heard of is
 * copied straight through on save.
 */
object DakNote {

    const val EXTENSION = "daknote"
    const val MIME_TYPE = "application/vnd.dakil.daknote"

    /**
     * The schema this build writes.
     *
     * v2 replaced v1's list of pages with a single continuous sheet: Markdown as the base flow, ink
     * over it, and pagination derived arithmetically from the paper height. v3 removed the flow —
     * all of a sheet's text is now positioned [pl.dakil.notes.model.TextBlock]s, and `content.md`
     * is derived from them rather than being where they live. Older files are migrated on read.
     *
     * The bump is not cosmetic. A v2 build renders `content.md` as the flow and does not render
     * text blocks at all, so it would show a migrated note's words in the wrong place and, on save,
     * write them out *twice* — once as the flow it thinks it is editing and once as the boxes it
     * faithfully preserved. Declaring v3 sends that build down the read-only path instead.
     */
    const val FORMAT_VERSION = 3

    /**
     * The highest `minReaderVersion` this build can safely open for writing.
     *
     * A file declaring more than this opens read-only. That rule is what makes preserving unknown
     * content safe rather than reckless: an older build can round-trip additions it doesn't
     * understand, but the moment a future version declares a *structural* change it can no longer
     * be edited blindly.
     */
    const val READER_VERSION = 3

    const val ENTRY_MIMETYPE = "mimetype"
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SHEET = "sheet.json"

    /**
     * The whole note's text, as plain Markdown any tool can read.
     *
     * Derived since v3: the boxes are where the text lives, and this is written out from them in
     * reading order so that a user can still recover their words with nothing but an unzip tool.
     * Read back only when a file has no boxes at all, which is what makes the v2 migration a fixed
     * point rather than a way to duplicate a note's text on every save.
     */
    const val ENTRY_MARKDOWN = "content.md"

    const val DIR_INK = "ink/"
    const val DIR_TEXT = "text/"
    const val DIR_ASSETS = "assets/"

    /** Pictures, one entry per distinct image, named by content. See [mediaEntry]. */
    const val DIR_MEDIA = "media/"

    /** v1 only, still read for migration. */
    const val DIR_PAGES = "pages/"

    fun inkEntry(block: BlockId): String = "$DIR_INK${block.raw}.dsv"
    fun textEntry(block: BlockId): String = "$DIR_TEXT${block.raw}.md"

    /** The entry a picture named [fileName] — content hash and extension — is stored under. */
    fun mediaEntry(fileName: String): String = "$DIR_MEDIA$fileName"

    /** The entry a PDF whose content hash is [sha] is stored under. */
    fun pdfEntry(sha: String): String = "$DIR_MEDIA$sha.pdf"

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * A note made from a PDF: one page of the sheet per page of the PDF, on paper the size of its
     * first page.
     *
     * White and plain whatever the user's default paper is. The PDF brings its own page, and a grid
     * or a tint around a letterboxed page would only show in the margins it was fitted into.
     */
    fun newPdfNote(
        title: String,
        src: String,
        data: MediaData,
        pages: List<PdfSourcePage>,
        view: ViewMode = ViewMode.PAGED,
        now: Long = System.currentTimeMillis(),
    ): Note {
        require(pages.isNotEmpty()) { "A PDF note needs at least one page" }
        val first = pages.first()
        val blank = newNote(
            title = title,
            size = PageSize.Custom(first.width.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT), first.height.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT)),
            background = PageBackground(color = PDF_PAPER, pattern = PagePattern.NONE),
            margins = PageMargins.DEFAULT,
            view = view,
            now = now,
        )
        // Page one is the blank note's own; the PDF's pages go in ahead of it and it is given back.
        val sheet = blank.sheet.withPdfPagesInserted(0, src, data, pages)
        return blank.copy(sheet = sheet.copy(pages = pages.size))
    }

    /** White, not the default warm paper: a PDF page is drawn on white, and its letterbox should match. */
    private const val PDF_PAPER = 0xFFFFFFFF.toInt()

    /** An inch to a little over three metres: the range a [PageSize.Custom] is sane in. */
    private const val MIN_PAGE_PT = 72f
    private const val MAX_PAGE_PT = 9000f

    /** A blank note: one sheet with one empty ink layer on it, and nothing written yet. */
    fun newNote(
        title: String = "",
        size: PageSize = PageSize.A4,
        background: PageBackground = PageBackground.DEFAULT,
        margins: PageMargins = PageMargins.DEFAULT,
        view: ViewMode = ViewMode.PAGED,
        now: Long = System.currentTimeMillis(),
    ): Note {
        val format = PageFormat(size = size, background = background, margins = margins)
        return Note(
            meta = NoteMeta(
                id = NoteId(newId()),
                title = title,
                created = now,
                modified = now,
                revision = 1,
                view = view,
            ),
            sheet = Sheet(
                format = format,
                markdown = "",
                blocks = listOf(
                    InkBlock(
                        id = BlockId("b0"),
                        z = 0,
                        rect = Rect(0f, 0f, format.width, format.height),
                        name = "Layer 1",
                    ),
                ),
                // Nothing to cache: the boxes state their own extent, and the field only exists
                // to carry a v2 note's page count across the migration. See [Sheet.contentHeight].
                contentHeight = 0f,
            ),
        )
    }
}

/** `#AARRGGBB`, chosen over a raw integer so a human can read and edit a page's colours. */
internal fun colorToHex(argb: Int): String = pl.dakil.notes.model.ColorCodec.toHex(argb)

internal fun hexToColor(text: String, default: Int): Int =
    pl.dakil.notes.model.ColorCodec.parse(text) ?: default
