package pl.dakil.notes.model.json

class JsonParseException(message: String, val offset: Int) :
    Exception("$message (at offset $offset)")

/**
 * A recursive-descent JSON parser producing an order-preserving [JsonValue] tree.
 *
 * Strict about syntax (no trailing commas, no comments, no unquoted keys) but permissive about
 * *content*: any structure it doesn't recognise is still represented faithfully, which is what
 * makes forward compatibility work.
 */
object JsonReader {

    fun parse(text: String): JsonValue {
        val p = Cursor(text)
        p.skipWhitespace()
        val value = p.readValue(depth = 0)
        p.skipWhitespace()
        if (!p.atEnd) p.fail("Trailing content after top-level value")
        return value
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonParseException("Expected a JSON object", 0)

    private const val MAX_DEPTH = 64

    private class Cursor(private val s: String) {
        var i = 0
        val atEnd: Boolean get() = i >= s.length

        fun fail(message: String): Nothing = throw JsonParseException(message, i)

        fun skipWhitespace() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("Nesting too deep")
            if (atEnd) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonString(readString())
                't' -> { expect("true"); JsonBool(true) }
                'f' -> { expect("false"); JsonBool(false) }
                'n' -> { expect("null"); JsonNull }
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun expect(literal: String) {
            if (!s.startsWith(literal, i)) fail("Expected '$literal'")
            i += literal.length
        }

        private fun readObject(depth: Int): JsonObject {
            i++ // '{'
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd && s[i] == '}') { i++; return JsonObject.adopt(map) }
            while (true) {
                skipWhitespace()
                if (atEnd || s[i] != '"') fail("Expected a quoted object key")
                val key = readString()
                skipWhitespace()
                if (atEnd || s[i] != ':') fail("Expected ':' after object key")
                i++
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd) fail("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject.adopt(map) }
                    else -> fail("Expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(depth: Int): JsonArray {
            i++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd && s[i] == ']') { i++; return JsonArray(items) }
            while (true) {
                skipWhitespace()
                items.add(readValue(depth + 1))
                skipWhitespace()
                if (atEnd) fail("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(items) }
                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val start = i
            // Fast path: scan for a closing quote with no escapes, avoiding a StringBuilder entirely.
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    val out = s.substring(start, i)
                    i++
                    return out
                }
                if (c == '\\') break
                i++
            }
            if (i >= s.length) fail("Unterminated string")

            val sb = StringBuilder().append(s, start, i)
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (atEnd) fail("Unterminated escape sequence")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("Truncated \\u escape")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid \\u escape '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("Invalid escape '\\$e'")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            fail("Unterminated string")
        }

        private fun readNumber(): JsonNumber {
            val start = i
            if (!atEnd && s[i] == '-') i++
            val intStart = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == intStart) fail("Number has no integer part")
            // Reject leading zeros. Literals are round-tripped verbatim, so accepting `01` here
            // would mean writing it back out and handing other tools an invalid document.
            if (i - intStart > 1 && s[intStart] == '0') fail("Number has a leading zero")
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val literal = s.substring(start, i)
            if (literal.isEmpty() || literal == "-") fail("Malformed number")
            // Validate once here so downstream accessors can assume the literal is well-formed.
            literal.toDoubleOrNull() ?: fail("Malformed number '$literal'")
            return JsonNumber(literal)
        }
    }
}
