package pl.dakil.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `the last term is prefix-matched so results narrow while typing`() {
        // Bare rather than quoted: `token*` is FTS4's prefix syntax and `"token"*` is FTS5's, and
        // FTS4 silently matches nothing for the second. Getting this wrong meant "eigen" never
        // found a note about eigenvalues.
        assertEquals("eigen*", ftsMatchExpression("eigen"))
    }

    @Test
    fun `earlier terms are whole words`() {
        assertEquals("\"linear\" alg*", ftsMatchExpression("linear alg"))
    }

    @Test
    fun `characters FTS would read as operators are stripped`() {
        // A hyphen is `NOT` to FTS, and an unbalanced quote is a syntax error. Either would turn a
        // search into an exception rather than a result.
        assertEquals("wellknown*", ftsMatchExpression("well-known\""))
    }

    @Test
    fun `a term that is an FTS keyword stays a word`() {
        // Somebody will search for "or" eventually, and a bare OR is an operator. Quoting costs the
        // prefix match on that one term, which is the lesser loss.
        assertEquals("\"linear\" \"OR\"", ftsMatchExpression("linear OR"))
    }

    @Test
    fun `a query of nothing but punctuation matches nothing rather than erroring`() {
        assertNull(ftsMatchExpression("--- ***"))
    }

    @Test
    fun `blank input asks for no query at all`() {
        assertNull(ftsMatchExpression("   "))
    }

    @Test
    fun `underscores survive because they are part of a word to the tokenizer`() {
        assertEquals("snake_case*", ftsMatchExpression("snake_case"))
    }
}
