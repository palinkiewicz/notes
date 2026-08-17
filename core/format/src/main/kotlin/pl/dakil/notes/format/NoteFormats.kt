package pl.dakil.notes.format

/**
 * Constants for a note that is nothing but a Markdown file.
 *
 * There is no reader and no writer here, and that is the point: the whole format is "the bytes are
 * UTF-8 Markdown". Anything more — a manifest, an id, a revision counter — would be metadata this
 * app invented and every other editor would strip on the first save.
 */
object MarkdownNote {

    const val EXTENSION = "md"

    /** Registered as `text/markdown` in RFC 7763. */
    const val MIME_TYPE = "text/markdown"
}

/**
 * Which of the two note formats a file is.
 *
 * The file extension is the whole discriminator. That keeps the type out of the index, out of the
 * navigation state and out of `NoteMeta`: a `.md` file has nowhere to record what it is, so anything
 * that had to be *stored* would only ever be true for half the library.
 */
enum class NoteKind(val extension: String, val mimeType: String) {

    /** A `.daknote` container: Markdown, ink, and paper. */
    INK(DakNote.EXTENSION, DakNote.MIME_TYPE),

    /** A plain `.md` file, readable and writable by anything. */
    TEXT(MarkdownNote.EXTENSION, MarkdownNote.MIME_TYPE);

    companion object {

        /** The kind of [fileName], or null for a file this app does not own. */
        fun of(fileName: String): NoteKind? {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return null
            val extension = fileName.substring(dot + 1).lowercase()
            return entries.firstOrNull { it.extension == extension }
        }

        /**
         * The title to show for [fileName].
         *
         * A `.md` file has no manifest to carry a title, so the file name *is* the title. Ink notes
         * keep their own, but falling back to the same rule for them means a note created outside
         * the app still shows something sensible.
         */
        fun titleOf(fileName: String): String {
            val kind = of(fileName) ?: return fileName
            return fileName.dropLast(kind.extension.length + 1)
        }
    }
}
