package pl.dakil.notes.editor.markdown

/**
 * Finds the images an older build embedded as base64, so they can be moved out of the text.
 *
 * One release wrote a picked image into the Markdown itself — `[img-1]: data:image/png;base64,…`
 * at the foot of the note. That is several megabytes of text in the field, the index and every
 * save, so a note opened with any is rewritten to point at a file instead: [find] says what is
 * there, the caller stores each payload, and [replace] swaps each URI for the link it was stored
 * under. Two passes because storing is I/O and this is not.
 *
 * Lines inside a fenced block are left alone: a code sample that happens to contain a data URI is
 * the sample's content, not an image.
 */
object DataUriImages {

    private val DATA_URI = Regex("""data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)""")

    /** One embedded image: the whole URI as written, and its MIME type. */
    data class Found(val uri: String, val mimeType: String) {
        /** The decoded bytes, or null if the base64 is damaged. */
        fun bytes(): ByteArray? = runCatching {
            java.util.Base64.getDecoder().decode(uri.substringAfter(";base64,"))
        }.getOrNull()
    }

    /** Every distinct embedded image outside a fence, in the order they first appear. */
    fun find(markdown: String): List<Found> {
        if (!markdown.contains("data:image/")) return emptyList()
        val seen = LinkedHashMap<String, Found>()
        forEachOutsideFence(markdown) { _, line ->
            for (match in DATA_URI.findAll(line)) {
                seen.getOrPut(match.value) { Found(match.value, match.groupValues[1]) }
            }
        }
        return seen.values.toList()
    }

    /** [markdown] with every URI in [links] replaced by its link, outside fences only. */
    fun replace(markdown: String, links: Map<String, String>): String {
        if (links.isEmpty()) return markdown
        val out = StringBuilder(markdown.length)
        var copied = 0
        forEachOutsideFence(markdown) { start, line ->
            for (match in DATA_URI.findAll(line)) {
                val link = links[match.value] ?: continue
                out.append(markdown, copied, start + match.range.first).append(link)
                copied = start + match.range.last + 1
            }
        }
        out.append(markdown, copied, markdown.length)
        return out.toString()
    }

    /**
     * [markdown] with every embedded image taken out: the reference definitions carrying one, the
     * `![…][id]` tags pointing at those, and inline `![…](data:…)` images. What is left is the
     * prose, for a sheet's text box whose pictures have become blocks of their own.
     */
    fun strip(markdown: String): String {
        if (!markdown.contains("data:image/")) return markdown
        val embeddedIds = HashSet<String>()
        forEachOutsideFence(markdown) { _, line ->
            val def = MarkdownParser.REFERENCE_DEF.matchEntire(line) ?: return@forEachOutsideFence
            if (DATA_URI.matches(def.groupValues[2].trim())) {
                embeddedIds += def.groupValues[1].trim().lowercase(java.util.Locale.ROOT)
            }
        }
        val kept = ArrayList<String>()
        var inFence = false
        for (line in markdown.split('\n')) {
            if (MarkdownParser.FENCE.matchEntire(line) != null) inFence = !inFence
            if (inFence) {
                kept += line
                continue
            }
            val def = MarkdownParser.REFERENCE_DEF.matchEntire(line)
            if (def != null && DATA_URI.matches(def.groupValues[2].trim())) continue
            val cleaned = line
                .replace(INLINE_EMBEDDED, "")
                .replace(REFERENCED) { match ->
                    if (match.groupValues[1].trim().lowercase(java.util.Locale.ROOT) in embeddedIds) "" else match.value
                }
            // A line that held nothing but the image goes with it, rather than leaving a gap.
            if (cleaned.isBlank() && line.isNotBlank()) continue
            kept += cleaned
        }
        return kept.joinToString("\n").trim('\n')
    }

    // Escaped brackets inside the classes are load-bearing: Android reads a regex with ICU, which
    // takes an unescaped `[` there as a nested set. See [MarkdownParser.BLOCK_IMAGE].
    private val INLINE_EMBEDDED = Regex("""!\[[^\[\]]*\]\(\s*data:image/[^)]*\)""")
    private val REFERENCED = Regex("""!\[[^\[\]]*\]\[([^\[\]]+)\]""")

    private inline fun forEachOutsideFence(markdown: String, action: (start: Int, line: String) -> Unit) {
        var offset = 0
        var inFence = false
        while (offset <= markdown.length) {
            val newline = markdown.indexOf('\n', offset)
            val end = if (newline < 0) markdown.length else newline
            val line = markdown.substring(offset, end)
            if (MarkdownParser.FENCE.matchEntire(line) != null) {
                inFence = !inFence
            } else if (!inFence) {
                action(offset, line)
            }
            if (newline < 0) break
            offset = newline + 1
        }
    }
}
