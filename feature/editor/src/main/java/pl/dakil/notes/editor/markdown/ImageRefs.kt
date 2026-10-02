package pl.dakil.notes.editor.markdown

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The stand-in for an image payload too large to keep beside the caret.
 *
 * An image picked from the device arrives as a reference definition several megabytes long, and
 * inside a text field every one of those bytes is paid for on every keystroke: the gap buffer
 * moves them, the output transformation re-reads them, the render plan re-scans them. None of that
 * has anything to do with the image — the plan hides a definition outright, and the only thing
 * that ever reads the payload back is the decoder.
 *
 * So an oversized payload is held *out* of the field. On the way in, [collapse] takes it aside,
 * leaving `image-ref:<id>` in the def line's place; on the way out, [expand] puts it back. The
 * file keeps the real Markdown — what is saved, synced, copied, or opened in another editor is
 * exactly the document the user wrote. Only the field, which is a view of it, carries the stand-in.
 *
 * The stand-in never leaks. Pasted Markdown is taken apart the same way before it reaches the
 * field, and the clipboard is handed back the real Markdown, so what the user copies out is what
 * they put in. A def line reading `image-ref:...` that no payload answers renders as the missing
 * image it is: only a copy taken before this existed, or one typed by hand, can carry one.
 *
 * Source mode shows the source of the *field*, not of the file — and the field carries the
 * stand-in. That is the one place the machinery is visible, and the file is one tap away.
 */
internal object ImageRefs {

    /** What a taken-aside payload leaves in the def line, resolved by whoever keeps the payloads. */
    const val TOKEN_PREFIX = "image-ref:"

    /**
     * The payload length above which a def line is taken aside.
     *
     * A few kilobytes of base64 — an icon, a small diagram — cost nothing to keep in the field and
     * stay exactly as they were written. The threshold exists so that a document which never needed
     * the machinery never gets it.
     */
    const val THRESHOLD = 4_096

    /** A base64 data URI for an image, the only kind the decoder can show. */
    private val DATA_URI = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+""")

    /** The same payload where an inline `![alt](...)` would carry it. */

    // Escaped brackets inside the character class are load-bearing, not decoration: Android
    // reads a regex with ICU, which takes an unescaped `[` there as the start of a nested set
    // and swallows the group's closing paren — the pattern ends with an unclosed group and the
    // class fails to initialise. JVM `Pattern` accepts the bare form, so unit tests and builds
    // pass either way; the app only crashes at runtime.

    private val INLINE_DATA_IMAGE = Regex("""(!?)\[([^\[\]]*)]\((${DATA_URI.pattern})\)""")

    /**
     * An inline image whose URL is an unanswered stand-in, the shape [collapse] leaves behind.
     *
     * The bracketed label is re-scanned for symmetry with [INLINE_DATA_IMAGE] — a def line and an
     * inline token never match the same line, so the two readers stay comparable.
     */
    private val INLINE_TOKEN = Regex("""(!?)\[([^\[\]]*)]\((${Regex.escape(TOKEN_PREFIX)}[^)\s]*)\)""")

    /**
     * Takes every oversized payload out of the document, leaving the [register]ed token in its place.
     *
     * Two shapes are taken. A def line — the shape every insert produces — is rewritten to the
     * token; an inline `![alt](data:...)`, which a paste or a foreign editor can leave behind, has
     * only its payload swapped for the token, so [expand] restores the line byte-for-byte and the
     * file keeps the inline shape its author wrote. Only lines outside fences are read: a code
     * sample that happens to look like a definition is the sample's content, not the document's
     * metadata, and rewriting it would change the code the user wrote. Everything else passes
     * through untouched, so a note with no oversized image comes out byte-for-byte what went in.
     */
    fun collapse(markdown: String, register: (token: String, payload: String) -> Unit): String {
        val rewrites = ArrayList<Triple<Int, Int, String>>()
        val minted = mutableSetOf<String>()
        val byPayload = HashMap<String, String>()
        fun tokenFor(payload: String): String {
            byPayload[payload]?.let { return it }
            val id = freshId(markdown, minted)
            byPayload[payload] = id
            register(TOKEN_PREFIX + id, payload)
            return id
        }
        forEachOutsideFence(markdown) { start, line ->
            val def = MarkdownParser.REFERENCE_DEF.matchEntire(line)
            if (def != null) {
                val value = def.groupValues[2]
                if (value.length < THRESHOLD || !value.startsWith("data:image/")) return@forEachOutsideFence
                val range = def.groups[2]!!.range
                rewrites += Triple(
                    start + range.first,
                    start + range.last + 1,
                    TOKEN_PREFIX + def.groupValues[1],
                )
                register(TOKEN_PREFIX + def.groupValues[1], value)
                return@forEachOutsideFence
            }
            for (match in INLINE_DATA_IMAGE.findAll(line)) {
                val payload = match.groupValues[3]
                if (payload.length < THRESHOLD) continue
                val range = match.groups[3]!!.range
                rewrites += Triple(
                    start + range.first,
                    start + range.last + 1,
                    TOKEN_PREFIX + tokenFor(payload),
                )
            }
        }
        return rewritten(markdown, rewrites)
    }

    /**
     * Takes oversized payloads out of Markdown arriving on the clipboard, which may carry them
     * where no insert ever did.
     *
     * Three shapes are handled. A def line, as [collapse] does. An inline `![alt](data:...)`, which
     * is rewritten to the reference form with its definition appended at the foot of the fragment —
     * a reference definition is scoped to the whole document, so the image the renderer shows is
     * the same. And a bare payload pasted on its own, which is an image the user means to have,
     * not a few megabytes of prose to type beside: it becomes one.
     */
    fun collapseForPaste(markdown: String, register: (token: String, payload: String) -> Unit): String {
        val bare = DATA_URI.matchEntire(markdown.trim())
        if (bare != null) {
            val id = freshId(markdown, mutableSetOf())
            register(TOKEN_PREFIX + id, bare.value)
            val tag = "![${MarkdownActions.nextDefaultImageLabel(markdown)}][$id]"
            return withDefAppended(tag, "[$id]: ${TOKEN_PREFIX}$id")
        }

        val minted = mutableSetOf<String>()
        val rewrites = ArrayList<Triple<Int, Int, String>>()
        val defs = ArrayList<String>()
        forEachOutsideFence(markdown) { start, line ->
            val def = MarkdownParser.REFERENCE_DEF.matchEntire(line)
            if (def != null) {
                val value = def.groupValues[2]
                if (value.length >= THRESHOLD && value.startsWith("data:image/")) {
                    val range = def.groups[2]!!.range
                    rewrites += Triple(start + range.first, start + range.last + 1, TOKEN_PREFIX + def.groupValues[1])
                    register(TOKEN_PREFIX + def.groupValues[1], value)
                }
                return@forEachOutsideFence
            }
            for (match in INLINE_DATA_IMAGE.findAll(line)) {
                val payload = match.groupValues[3]
                if (payload.length < THRESHOLD) continue
                val id = freshId(markdown, minted)
                val label = match.groupValues[2].ifBlank { MarkdownActions.nextDefaultImageLabel(markdown) }
                register(TOKEN_PREFIX + id, payload)
                rewrites += Triple(start + match.range.first, start + match.range.last + 1, "${match.groupValues[1]}[$label][$id]")
                defs += "[$id]: ${TOKEN_PREFIX}$id"
            }
        }
        if (rewrites.isEmpty() && defs.isEmpty()) return markdown
        var out = rewritten(markdown, rewrites)
        for (def in defs) out = withDefAppended(out, def)
        return out
    }

    /**
     * Puts the payloads back, replacing each answered stand-in with what it stands for.
     *
     * Both shapes [collapse] leaves are restored: the token in a def line, and the token as an
     * inline URL. An unanswered one stays as it is: rewriting it to nothing would silently lose an
     * image that some other session might still be able to resolve. Inside fences, too, the source
     * is left alone for the same reason [collapse] gives.
     */
    fun expand(markdown: String, payloadFor: (token: String) -> String?): String {
        if (!markdown.contains(TOKEN_PREFIX)) return markdown
        val rewrites = ArrayList<Triple<Int, Int, String>>()
        forEachOutsideFence(markdown) { start, line ->
            val def = MarkdownParser.REFERENCE_DEF.matchEntire(line)
            if (def != null) {
                val value = def.groupValues[2]
                if (!value.startsWith(TOKEN_PREFIX)) return@forEachOutsideFence
                val payload = payloadFor(value) ?: return@forEachOutsideFence
                val range = def.groups[2]!!.range
                rewrites += Triple(start + range.first, start + range.last + 1, payload)
                return@forEachOutsideFence
            }
            for (match in INLINE_TOKEN.findAll(line)) {
                val url = match.groupValues[3]
                val payload = payloadFor(url) ?: continue
                val range = match.groups[3]!!.range
                rewrites += Triple(start + range.first, start + range.last + 1, payload)
            }
        }
        return rewritten(markdown, rewrites)
    }

    /**
     * A data image big enough to be worth taking aside, split into its MIME type and its payload.
     *
     * Null for anything else — a short `data:` URI stays exactly where the user wrote it.
     */
    fun parseDataUri(uri: String): Pair<String, String>? {
        val match = DATA_URI.matchEntire(uri.trim()) ?: return null
        if (match.value.length < THRESHOLD) return null
        return match.value.substringAfter("data:").substringBefore(";base64,") to
            match.value.substringAfter(";base64,")
    }

    /** An id no def in [existing] and none minted since is already using. */
    private fun freshId(existing: String, minted: MutableSet<String>): String {
        while (true) {
            val id = MarkdownActions.generateImageRefId(existing)
            if (minted.add(id)) return id
        }
    }

    /**
     * The def line joined to the document the way an insert joins it: after a blank line, so the
     * definition can never be read as belonging to the paragraph above it.
     */
    private fun withDefAppended(text: String, def: String): String = when {
        text.endsWith("\n\n") -> "$text$def"
        text.endsWith("\n") -> "$text\n$def"
        else -> if (text.isEmpty()) def else "$text\n\n$def"
    }

    private fun rewritten(markdown: String, rewrites: List<Triple<Int, Int, String>>): String {
        if (rewrites.isEmpty()) return markdown
        val out = StringBuilder(markdown)
        // Back to front, so each range is still where it was when its turn comes.
        for ((start, end, token) in rewrites.asReversed()) out.replace(start, end, token)
        return out.toString()
    }

    /**
     * Walks every line of [markdown] that is not inside a fenced code block, handing [action] its
     * start offset and its text.
     *
     * The fence is the renderer's own: the same `MarkdownParser.FENCE` that opens and closes one
     * there, so what is hidden from the plan is hidden from this too. A fence closes on the next
     * fence line, wherever it is, which is how the renderer counts it.
     */
    private inline fun forEachOutsideFence(markdown: String, action: (start: Int, line: String) -> Unit) {
        var offset = 0
        var inFence = false
        while (offset <= markdown.length) {
            val newline = markdown.indexOf('\n', offset)
            val end = if (newline < 0) markdown.length else newline
            val line = markdown.substring(offset, end)
            if (inFence) {
                if (MarkdownParser.FENCE.matchEntire(line) != null) inFence = false
            } else if (MarkdownParser.FENCE.matchEntire(line) != null) {
                inFence = true
            } else {
                action(offset, line)
            }
            if (newline < 0) break
            offset = newline + 1
        }
    }
}

/**
 * What a caller that knows where its images live hands the editor.
 *
 * The field is a view of the document, and the view carries tokens where the document carries
 * oversized payloads; [ImageRefs] is the machinery both sides of that boundary agree on. A view
 * model implements this and hands it to [MarkdownEditor] and [MarkdownStaticText], which resolve
 * tokens to bitmaps without ever putting a megabyte beside the caret.
 */
interface ImageRefsSupport {

    /**
     * Decodes a def line's value - token or not - into a bitmap roughly [widthPx] wide.
     *
     * Suspending rather than blocking, because the decode of a real photograph takes longer than a
     * frame: this is called from composition's after-effects, and the answer arrives one frame
     * later, where the block waits for it. Null means the image cannot be shown; the block draws
     * nothing, which is how an unresolvable token renders - the missing image it is.
     */
    suspend fun resolveImage(url: String, widthPx: Int): ImageBitmap?

    /**
     * Puts the payloads back: the clipboard and anything that writes the document get the real
     * Markdown, not the field's view of it.
     */
    fun expand(markdown: String): String

    /**
     * Takes oversized payloads in, wherever Markdown arrives from outside the field - a paste
     * above all. See [ImageRefs.collapseForPaste].
     */
    fun collapseForPaste(markdown: String): String
}
