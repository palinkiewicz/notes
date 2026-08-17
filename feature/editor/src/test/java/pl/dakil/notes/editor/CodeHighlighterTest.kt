package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.code.CodeHighlighter
import pl.dakil.notes.editor.markdown.code.CodeLanguages
import pl.dakil.notes.editor.markdown.code.CodeToken

/**
 * The code lexer.
 *
 * One lexer serves every language, so most of what could go wrong is in how it is *parameterised* —
 * a language whose comments start with `#` being scanned as if they started with `//` shows up here
 * rather than as a wrongly-tinted line on a phone.
 */
class CodeHighlighterTest {

    @Test
    fun `keywords are recognised per language`() {
        assertEquals(listOf("val" to CodeToken.KEYWORD), tokens("val x", "kotlin").filterKeywords())
        // `val` means nothing in Python, and colouring it would be a lie about the language.
        assertEquals(emptyList<Pair<String, CodeToken>>(), tokens("val x", "python").filterKeywords())
        assertEquals(listOf("def" to CodeToken.KEYWORD), tokens("def x", "python").filterKeywords())
    }

    @Test
    fun `comments follow the language's own convention`() {
        assertEquals(listOf("// gone"), tokens("a // gone", "kotlin").texts(CodeToken.COMMENT))
        assertEquals(listOf("# gone"), tokens("a # gone", "python").texts(CodeToken.COMMENT))
        assertEquals(listOf("-- gone"), tokens("a -- gone", "sql").texts(CodeToken.COMMENT))
        // A hash is not a comment in C, and `//` is not one in Python.
        assertEquals(emptyList<String>(), tokens("a # here", "c").texts(CodeToken.COMMENT))
    }

    @Test
    fun `block comments run across lines`() {
        assertEquals(listOf("/* one\ntwo */"), tokens("/* one\ntwo */ x", "c").texts(CodeToken.COMMENT))
    }

    @Test
    fun `an unterminated block comment stops at the end rather than looping`() {
        assertEquals(listOf("/* forever"), tokens("/* forever", "c").texts(CodeToken.COMMENT))
    }

    @Test
    fun `strings are found whole, escapes included`() {
        assertEquals(listOf("\"a \\\" b\""), tokens("x = \"a \\\" b\"", "c").texts(CodeToken.STRING))
    }

    @Test
    fun `an apostrophe in prose is not a string`() {
        // One stray apostrophe would otherwise paint everything after it, and a comment full of
        // ordinary English is exactly where that happens.
        assertEquals(emptyList<String>(), tokens("it's fine\nmore code", "c").texts(CodeToken.STRING))
    }

    @Test
    fun `a quote still being typed is left alone until it is closed`() {
        assertEquals(emptyList<String>(), tokens("x = \"half", "c").texts(CodeToken.STRING))
    }

    @Test
    fun `triple quotes run to the matching triple`() {
        assertEquals(
            listOf("\"\"\"one\ntwo\"\"\""),
            tokens("x = \"\"\"one\ntwo\"\"\"", "python").texts(CodeToken.STRING),
        )
    }

    @Test
    fun `numbers are found only when they stand alone`() {
        assertEquals(listOf("42"), tokens("n = 42", "c").texts(CodeToken.NUMBER))
        assertEquals(listOf("3.14"), tokens("n = 3.14", "c").texts(CodeToken.NUMBER))
        assertEquals(listOf("0xFF"), tokens("n = 0xFF", "c").texts(CodeToken.NUMBER))
        assertEquals(listOf("1e-9"), tokens("n = 1e-9", "c").texts(CodeToken.NUMBER))
        assertEquals(listOf("10L"), tokens("n = 10L", "java").texts(CodeToken.NUMBER))
        // Digits inside a name belong to the name.
        assertEquals(emptyList<String>(), tokens("utf8 = x", "c").texts(CodeToken.NUMBER))
        assertEquals(emptyList<String>(), tokens("x = \"port 8080\"", "c").texts(CodeToken.NUMBER))
    }

    @Test
    fun `a name followed by a bracket is a function`() {
        assertEquals(listOf("compute"), tokens("compute(x)", "c").texts(CodeToken.FUNCTION))
        assertEquals(listOf("compute"), tokens("compute (x)", "c").texts(CodeToken.FUNCTION))
        assertEquals(emptyList<String>(), tokens("compute = x", "c").texts(CodeToken.FUNCTION))
    }

    @Test
    fun `nothing inside a comment or a string is scanned as code`() {
        assertEquals(emptyList<String>(), tokens("// val compute(1)", "kotlin").texts(CodeToken.KEYWORD))
        assertEquals(emptyList<String>(), tokens("\"val compute(1)\"", "kotlin").texts(CodeToken.NUMBER))
    }

    // ---- The language table ---------------------------------------------------------------------

    @Test
    fun `the aliases people actually type resolve`() {
        for (alias in listOf("js", "ts", "py", "kt", "sh", "c++", "cs", "yml", "html", "golang")) {
            assertNotNull("no spec for '$alias'", CodeLanguages.of(alias))
        }
    }

    @Test
    fun `case and stray spaces do not matter`() {
        assertNotNull(CodeLanguages.of("  Python  "))
        assertNotNull(CodeLanguages.of("KOTLIN"))
    }

    @Test
    fun `an unknown or missing language has no spec`() {
        assertNull(CodeLanguages.of("klingon"))
        assertNull(CodeLanguages.of(""))
        assertNull(CodeLanguages.of("   "))
    }

    @Test
    fun `every offered language carries keywords and resolves to itself`() {
        // A language in the table with an empty keyword set would silently colour nothing.
        for (id in listOf("kotlin", "java", "python", "javascript", "typescript", "go", "rust",
                          "c", "cpp", "csharp", "ruby", "php", "swift", "sql", "bash")) {
            val spec = CodeLanguages.of(id)
            assertNotNull("no spec for '$id'", spec)
            assertTrue("'$id' has no keywords", spec!!.words.size > 10)
        }
    }

    @Test
    fun `typescript inherits what javascript knows`() {
        val ts = CodeLanguages.of("typescript")!!
        assertTrue("const" in ts.words)
        assertTrue("interface" in ts.words)
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun tokens(code: String, language: String): List<Pair<String, CodeToken>> {
        val spec = CodeLanguages.of(language) ?: error("no spec for $language")
        val out = ArrayList<Pair<String, CodeToken>>()
        CodeHighlighter.tokenize(code, spec, 0, code.length) { start, end, token ->
            out += code.substring(start, end) to token
        }
        return out
    }

    private fun List<Pair<String, CodeToken>>.texts(token: CodeToken) =
        filter { it.second == token }.map { it.first }

    private fun List<Pair<String, CodeToken>>.filterKeywords() =
        filter { it.second == CodeToken.KEYWORD }
}
