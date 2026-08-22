package pl.dakil.notes.data

import java.util.Locale

/**
 * Turns what the user typed into an FTS4 `MATCH` expression.
 *
 * A free function rather than a method on [NoteIndex] so it can be tested without a database — the
 * syntax is fiddly enough that it was wrong for a long time without anyone noticing, and the bug
 * was invisible from the outside: searches simply found less than they should have.
 *
 * Two rules do all the work:
 *
 * - **Every term is stripped to letters, digits and underscores.** FTS treats `-`, `*`, `"`, `(`,
 *   `)`, `:` and `^` as operators, so a user typing a hyphenated word or an unbalanced quote would
 *   otherwise get a syntax error instead of results.
 * - **The last term gets a bare `*`**, which is what makes results narrow as the user types. It has
 *   to be bare: `token*` is FTS4's prefix syntax, while `"token"*` is FTS5's, and FTS4 quietly
 *   matches nothing at all for the second. This index is FTS4 — FTS5 is not on every device this
 *   app supports — so a search for "eigen" found a note containing "Eigenvalues" only if the user
 *   typed the whole word.
 */
internal fun ftsMatchExpression(query: String): String? {
    val terms = query.split(WHITESPACE)
        .map { term -> term.filter { it.isLetterOrDigit() || it == '_' } }
        .filter { it.isNotEmpty() }
    if (terms.isEmpty()) return null
    return terms.mapIndexed { i, term ->
        when {
            // A bare `AND`, `OR`, `NOT` or `NEAR` is an operator to FTS, and one of those is a word
            // somebody will eventually search for. Quoting makes it a term again — at the cost of
            // the prefix match, which is the lesser loss.
            term.uppercase(Locale.ROOT) in FTS_KEYWORDS -> "\"$term\""
            i == terms.lastIndex -> "$term*"
            else -> "\"$term\""
        }
    }.joinToString(" ")
}

private val WHITESPACE = Regex("\\s+")

private val FTS_KEYWORDS = setOf("AND", "OR", "NOT", "NEAR")
