package pl.dakil.notes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.json.JsonNumber
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonParseException
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonString
import pl.dakil.notes.model.json.JsonWriter

class JsonRoundTripTest {

    @Test
    fun `object key order survives a parse-write cycle`() {
        val source = """{"zeta":1,"alpha":2,"middle":{"b":true,"a":null},"omega":[3,2,1]}"""
        val parsed = JsonReader.parseObject(source)
        assertEquals(source, JsonWriter.write(parsed, pretty = false))
    }

    @Test
    fun `editing one key leaves neighbouring keys in place`() {
        // The core forward-compatibility guarantee: unknown keys written by a future version must
        // not migrate to the end of the file every time this build touches the document.
        val parsed = JsonReader.parseObject("""{"a":1,"futureField":"keep","c":3}""")
        val edited = parsed.with("a", JsonNumber.of(9))
        assertEquals("""{"a":9,"futureField":"keep","c":3}""", JsonWriter.write(edited, pretty = false))
    }

    @Test
    fun `large integers are not mangled into scientific notation`() {
        // A Double round-trip would emit 1.7551E12 and silently corrupt every timestamp.
        val parsed = JsonReader.parseObject("""{"modified":1755190000000}""")
        assertEquals(1_755_190_000_000L, parsed.long("modified"))
        assertEquals("""{"modified":1755190000000}""", JsonWriter.write(parsed, pretty = false))
    }

    @Test
    fun `escapes round-trip`() {
        val value = "line\nbreak \"quoted\" tab\t back\\slash é中"
        val json = JsonObject.EMPTY.with("s", JsonString(value))
        val text = JsonWriter.write(json, pretty = false)
        assertEquals(value, JsonReader.parseObject(text).string("s"))
    }

    @Test
    fun `writing is deterministic`() {
        val source = """{"b":[1,2,3],"a":{"nested":{"deep":true}}}"""
        val first = JsonWriter.write(JsonReader.parseObject(source))
        val second = JsonWriter.write(JsonReader.parseObject(first))
        assertEquals(first, second)
    }

    @Test
    fun `typed accessors fall back instead of throwing on the wrong type`() {
        // A forward-compatible reader must survive a field whose type changed in a later schema.
        val json = JsonReader.parseObject("""{"n":"not a number","o":[1]}""")
        assertEquals(7, json.int("n", 7))
        assertNull(json.obj("o"))
        assertEquals("fallback", json.string("missing", "fallback"))
    }

    @Test
    fun `malformed input is rejected with an offset`() {
        val cases = listOf("{", """{"a":}""", """{"a":1,}""", "[1 2]", """{a:1}""", "01", "")
        for (case in cases) {
            val thrown = runCatching { JsonReader.parse(case) }.exceptionOrNull()
            assertTrue("expected a parse failure for: $case", thrown is JsonParseException)
        }
    }

    @Test
    fun `nesting is bounded`() {
        val deep = "[".repeat(200) + "]".repeat(200)
        val thrown = runCatching { JsonReader.parse(deep) }.exceptionOrNull()
        assertTrue(thrown is JsonParseException)
    }
}
