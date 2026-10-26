package pl.dakil.notes.data

/**
 * Where a `.md` note's images live, and how a note says which of them it uses.
 *
 * A Markdown file has nowhere inside it to put a picture, so the picture goes beside it, in a folder
 * of that name shared by every note in the directory — the convention Obsidian, Typora and VS Code
 * already resolve, so a note keeps its images when it is opened anywhere else. Files are named by
 * their content (see [ImportedImage.fileName]); sharing the folder therefore costs nothing, and the
 * same picture used by two notes is stored once.
 */
object Attachments {

    /** The folder's name. Hidden from the library, which lists notes and the folders that hold them. */
    const val DIR = "attachments"

    /** `![alt](attachments/x.png)`, `![alt](<attachments/x.png>)` and `[id]: attachments/x.png`. */
    private val REFERENCE = Regex("""(?:\]\(\s*<?|^\s{0,3}\[[^\]]+\]:\s*<?)\Q$DIR\E/([^)\s>/]+)""", RegexOption.MULTILINE)

    /** The link a note writes for [fileName]. */
    fun linkTo(fileName: String): String = "$DIR/$fileName"

    /**
     * The file name [url] names inside the attachments folder, or null if it points elsewhere.
     *
     * Only a bare name directly inside the folder: a `..` or a nested path is not something this
     * app wrote, and following one would read whatever file it happened to reach.
     */
    fun fileNameOf(url: String): String? {
        val trimmed = url.trim().removePrefix("./")
        if (!trimmed.startsWith("$DIR/")) return null
        val name = trimmed.substring(DIR.length + 1)
        if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") return null
        return name
    }

    /** Every attachment [markdown] refers to, by file name. */
    fun referencedIn(markdown: String): Set<String> =
        REFERENCE.findAll(markdown).mapTo(LinkedHashSet()) { it.groupValues[1] }
}
