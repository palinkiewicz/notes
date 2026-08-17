package pl.dakil.notes.editor.markdown.code

/** What a run of characters inside a code block is. */
enum class CodeToken { KEYWORD, STRING, NUMBER, COMMENT, FUNCTION }

/**
 * Everything the highlighter needs to know about one language.
 *
 * The whole point of this type is that there is **one** lexer, not fifty. Languages differ in which
 * words are reserved and in how they spell a comment or a string; the machinery for finding those
 * things is identical, so it is written once and parameterised here. A per-language grammar would be
 * both far larger and far slower, and this app cannot spend a megabyte on syntax colouring.
 */
class LanguageSpec(
    private val keywords: String,
    val lineComment: String?,
    val blockOpen: String?,
    val blockClose: String?,
    /** Characters that open a string literal. */
    val quotes: String,
    /** Whether a tripled quote runs until the matching triple, as in Python or Kotlin. */
    val tripleQuoted: Boolean,
    /**
     * Whether the language's own words may be written in any case.
     *
     * SQL is the reason this exists: its keywords are conventionally shouted — `SELECT`, `FROM` —
     * and the keyword lists are all lowercase, so without this every word in a perfectly ordinary
     * query goes uncoloured.
     */
    val caseInsensitive: Boolean = false,
) {
    /**
     * Built on first use and then held.
     *
     * The keyword lists are `String` constants, so a language nobody opens costs nothing beyond the
     * bytes on disk: the constant is never materialised, and this set is never built.
     */
    val words: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        keywords.split(' ').filterTo(HashSet()) { it.isNotEmpty() }
    }

    /**
     * Whether [word] is one of the language's own.
     *
     * The case-folded lookup is second and only for the languages that need it, so the usual case
     * costs one hash and no allocation.
     */
    fun knows(word: String): Boolean =
        word in words || (caseInsensitive && word.lowercase() in words)
}

/**
 * A single-pass lexer good enough to colour a note.
 *
 * Not a parser: it never builds a tree, never backtracks, and allocates nothing per token — spans go
 * straight to the caller. It runs on every keystroke inside a visible code block, so the budget is a
 * few microseconds, and the cost of being approximate is a mis-tinted word rather than a wrong
 * document. Regular expressions are avoided throughout for the same reason.
 */
object CodeHighlighter {

    /**
     * Scans `code[from, to)` and reports each coloured run through [emit].
     *
     * Offsets passed to [emit] are indices into [code], so the caller can map them wherever it
     * needs to.
     */
    inline fun tokenize(
        code: String,
        spec: LanguageSpec,
        from: Int,
        to: Int,
        emit: (start: Int, end: Int, token: CodeToken) -> Unit,
    ) {
        var i = from
        while (i < to) {
            val c = code[i]

            // Comments first: nothing inside one is code.
            if (spec.lineComment != null && code.startsWith(spec.lineComment, i)) {
                val end = code.indexOf('\n', i).let { if (it < 0 || it > to) to else it }
                emit(i, end, CodeToken.COMMENT)
                i = end
                continue
            }
            if (spec.blockOpen != null && spec.blockClose != null && code.startsWith(spec.blockOpen, i)) {
                val closed = code.indexOf(spec.blockClose, i + spec.blockOpen.length)
                val end = if (closed < 0 || closed + spec.blockClose.length > to) {
                    to
                } else {
                    closed + spec.blockClose.length
                }
                emit(i, end, CodeToken.COMMENT)
                i = end
                continue
            }

            if (spec.quotes.indexOf(c) >= 0) {
                val end = scanString(code, i, to, c, spec.tripleQuoted)
                if (end > i) {
                    emit(i, end, CodeToken.STRING)
                    i = end
                    continue
                }
                // Unterminated: an apostrophe in `it's` or a quote still being typed. Colouring
                // from here to the end of the block on the strength of one character would be
                // worse than colouring nothing.
                i++
                continue
            }

            if (isIdentStart(c)) {
                var j = i + 1
                while (j < to && isIdentPart(code[j])) j++
                val word = code.substring(i, j)
                when {
                    spec.knows(word) -> emit(i, j, CodeToken.KEYWORD)
                    // A name with a bracket after it is being called or declared. Crude, but it is
                    // true across every C-shaped language and most others, and it is the single
                    // cheapest signal that a word is a function.
                    isCallSite(code, j, to) -> emit(i, j, CodeToken.FUNCTION)
                }
                i = j
                continue
            }

            // Digits reach here only when no identifier swallowed them, so `utf8` stays one word
            // while `utf8 + 1` colours the 1.
            if (c in '0'..'9') {
                val end = scanNumber(code, i, to)
                emit(i, end, CodeToken.NUMBER)
                i = end
                continue
            }

            i++
        }
    }

    /** Returns the offset just past the closing quote, or -1 when the literal is never closed. */
    fun scanString(code: String, start: Int, to: Int, quote: Char, tripleQuoted: Boolean): Int {
        val triple = tripleQuoted &&
            start + 2 < to &&
            code[start + 1] == quote &&
            code[start + 2] == quote
        val width = if (triple) 3 else 1
        var i = start + width
        while (i < to) {
            val c = code[i]
            when {
                c == '\\' -> i += 2
                // Only a triple-quoted literal may span lines, so a line break ends the search.
                c == '\n' && !triple -> return -1
                c == quote && triple && i + 2 < to && code[i + 1] == quote && code[i + 2] == quote ->
                    return i + 3
                c == quote && !triple -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    /** Returns the offset just past a numeric literal starting at [start]. */
    fun scanNumber(code: String, start: Int, to: Int): Int {
        var i = start
        if (code[i] == '0' && i + 1 < to && (code[i + 1] == 'x' || code[i + 1] == 'X' ||
                code[i + 1] == 'b' || code[i + 1] == 'B' || code[i + 1] == 'o' || code[i + 1] == 'O')
        ) {
            i += 2
            while (i < to && (isHexDigit(code[i]) || code[i] == '_')) i++
        } else {
            while (i < to && (code[i] in '0'..'9' || code[i] == '_')) i++
            if (i < to && code[i] == '.' && i + 1 < to && code[i + 1] in '0'..'9') {
                i++
                while (i < to && (code[i] in '0'..'9' || code[i] == '_')) i++
            }
            if (i < to && (code[i] == 'e' || code[i] == 'E')) {
                var j = i + 1
                if (j < to && (code[j] == '+' || code[j] == '-')) j++
                if (j < to && code[j] in '0'..'9') {
                    i = j
                    while (i < to && code[i] in '0'..'9') i++
                }
            }
        }
        // Type suffixes: 10L, 1.5f, 3u, 100n.
        while (i < to && (code[i] == 'L' || code[i] == 'l' || code[i] == 'f' || code[i] == 'F' ||
                code[i] == 'u' || code[i] == 'U' || code[i] == 'd' || code[i] == 'D' || code[i] == 'n')
        ) {
            i++
        }
        return i
    }

    /** Whether the name ending at [at] is followed by an opening bracket. */
    fun isCallSite(code: String, at: Int, to: Int): Boolean {
        var i = at
        while (i < to && (code[i] == ' ' || code[i] == '\t')) i++
        return i < to && code[i] == '('
    }

    fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$' || c == '@'

    fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
