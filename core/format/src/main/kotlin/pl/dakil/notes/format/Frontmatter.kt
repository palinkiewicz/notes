package pl.dakil.notes.format

/**
 * The YAML frontmatter block at the top of a `.md` note, split into what this app understands and
 * what it merely carries.
 *
 * @param present whether the file actually had a block. A file without one is not the same as a
 *   file with an empty one: writing tags to the first has to create the delimiters, and writing
 *   none to the second has to remove them again rather than leave a pair of bare rules behind.
 * @param tags the `tags:` key, the only key this app reads.
 * @param remainder every other frontmatter line, verbatim and in order. Preserved for the same
 *   reason unknown JSON keys and unknown ZIP entries are: a `.md` note is a file other editors own
 *   too, and one that came in with an Obsidian `aliases:` key has to go back out with it.
 * @param body everything after the closing delimiter, less the one blank line that conventionally
 *   separates the two. [FrontmatterCodec.render] puts that line back, so a note this app has
 *   written once is byte-identical every time it is written again.
 */
data class Frontmatter(
    val present: Boolean,
    val tags: List<String>,
    val remainder: List<String>,
    val body: String,
)

/**
 * Reads and writes the frontmatter block of a Markdown note.
 *
 * Tags need somewhere to live for a `.md` note, which — unlike a `.daknote` — has no manifest to
 * put them in. Frontmatter is what the rest of the Markdown world already uses for exactly this, so
 * a note tagged here stays tagged in Obsidian and vice versa.
 *
 * It is deliberately **not** a general YAML parser: it reads one key and copies the rest through
 * untouched, which is all that can be done safely without shipping a YAML library the size budget
 * will not pay for. Anything it cannot make sense of is left as body text rather than guessed at.
 */
object FrontmatterCodec {

    private const val DELIMITER = "---"

    /** Splits [markdown] into its frontmatter and its body. Never throws; a malformed block is body. */
    fun parse(markdown: String): Frontmatter {
        var cursor = markdown.indexOf('\n')
        if (cursor < 0) return absent(markdown)
        if (markdown.substring(0, cursor).trimEnd() != DELIMITER) return absent(markdown)
        cursor += 1

        val block = ArrayList<String>()
        var closed = false
        while (cursor <= markdown.length) {
            val end = markdown.indexOf('\n', cursor)
            val line = markdown.substring(cursor, if (end < 0) markdown.length else end)
            cursor = if (end < 0) markdown.length + 1 else end + 1
            val trimmed = line.trimEnd()
            // `...` closes a YAML document too, and some tools emit it.
            if (trimmed == DELIMITER || trimmed == "...") {
                closed = true
                break
            }
            block += line
        }
        // An unterminated block is not a block. Treating it as one would swallow the whole note.
        if (!closed) return absent(markdown)

        val (tags, remainder) = splitTags(block)
        val raw = if (cursor > markdown.length) "" else markdown.substring(cursor)
        // Exactly one newline, not all of them: a note that deliberately opens on a blank line
        // keeps it.
        return Frontmatter(present = true, tags = tags, remainder = remainder, body = raw.removePrefix("\n"))
    }

    private fun absent(markdown: String) =
        Frontmatter(present = false, tags = emptyList(), remainder = emptyList(), body = markdown)

    /** Returns [markdown] with its tags replaced by [tags], creating or removing the block as needed. */
    fun withTags(markdown: String, tags: List<String>): String {
        val current = parse(markdown)
        return render(tags, current.remainder, current.body)
    }

    /**
     * Puts a block back on top of a body.
     *
     * The editor holds only the body — the frontmatter is metadata, not something anyone wants a
     * caret in — so this is what turns the two halves back into the file that gets written.
     *
     * One blank line always separates the block from the text, which is what every Markdown tool
     * emits and what [parse] takes back off. A file that arrived without one gains it the first
     * time it is saved and then never changes again; the alternative is carrying the original
     * spacing around forever to avoid a one-character difference nobody can see.
     */
    fun render(tags: List<String>, remainder: List<String>, body: String): String {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // Nothing left to hold: no delimiters rather than an empty block.
        if (clean.isEmpty() && remainder.isEmpty()) return body
        val block = buildList {
            if (clean.isNotEmpty()) add("tags: [" + clean.joinToString(", ") { scalar(it) } + "]")
            addAll(remainder)
        }
        return buildString {
            append(DELIMITER).append('\n')
            for (line in block) append(line).append('\n')
            append(DELIMITER).append('\n')
            if (body.isNotEmpty()) append('\n').append(body)
        }
    }

    // ---- Tags ------------------------------------------------------------------------------------

    /** Pulls the `tags:` key out of [block], leaving every other line alone. */
    private fun splitTags(block: List<String>): Pair<List<String>, List<String>> {
        val remainder = ArrayList<String>(block.size)
        val tags = ArrayList<String>()
        var i = 0
        var seen = false
        while (i < block.size) {
            val line = block[i]
            val key = line.substringBefore(':').trim()
            if (seen || !line.contains(':') || key != "tags") {
                remainder += line
                i++
                continue
            }
            seen = true
            val value = line.substringAfter(':').trim()
            if (value.isNotEmpty()) {
                tags += parseFlow(value)
                i++
            } else {
                // Block form: `tags:` on its own line, then `  - work` beneath it.
                i++
                while (i < block.size && block[i].trimStart().startsWith("- ")) {
                    tags += unquote(block[i].trimStart().removePrefix("- ").trim())
                    i++
                }
            }
        }
        return tags.filter { it.isNotEmpty() }.distinct() to remainder
    }

    /** Reads `[a, "b, c"]` — or a bare `a, b` — into its elements, respecting quotes. */
    private fun parseFlow(value: String): List<String> {
        val inner = value.removeSurrounding("[", "]")
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote = ' '
        for (c in inner) {
            when {
                quote != ' ' -> if (c == quote) quote = ' ' else current.append(c)
                c == '"' || c == '\'' -> quote = c
                c == ',' -> {
                    out += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        out += current.toString().trim()
        return out.map { unquote(it) }.filter { it.isNotEmpty() }
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.first() == '"' && value.last() == '"' ->
            value.substring(1, value.length - 1)

        value.length >= 2 && value.first() == '\'' && value.last() == '\'' ->
            value.substring(1, value.length - 1)

        else -> value
    }

    /** Quotes a tag only when leaving it bare would change what it means. */
    private fun scalar(tag: String): String =
        if (tag.none { it in ",[]{}\"'#:" }) tag
        else "\"" + tag.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
