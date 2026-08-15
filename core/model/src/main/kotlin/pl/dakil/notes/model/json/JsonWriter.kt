package pl.dakil.notes.model.json

/**
 * Serialises a [JsonValue] tree. Output is deterministic: the same tree always produces the same
 * bytes, which is what keeps `.daknote` files diff-stable under file-based sync.
 */
object JsonWriter {

    fun write(value: JsonValue, pretty: Boolean = true): String {
        val sb = StringBuilder(256)
        writeValue(sb, value, pretty, 0)
        if (pretty) sb.append('\n')
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: JsonValue, pretty: Boolean, indent: Int) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> sb.append(value.literal)
            is JsonString -> writeString(sb, value.value)
            is JsonArray -> writeArray(sb, value, pretty, indent)
            is JsonObject -> writeObject(sb, value, pretty, indent)
        }
    }

    private fun writeArray(sb: StringBuilder, value: JsonArray, pretty: Boolean, indent: Int) {
        if (value.items.isEmpty()) { sb.append("[]"); return }
        // Arrays of scalars (rects, transforms, tags) stay on one line — they are read as a unit.
        val scalarOnly = value.items.all { it !is JsonObject && it !is JsonArray }
        if (!pretty || scalarOnly) {
            sb.append('[')
            value.items.forEachIndexed { i, item ->
                if (i > 0) sb.append(if (pretty) ", " else ",")
                writeValue(sb, item, pretty = false, indent = 0)
            }
            sb.append(']')
            return
        }
        sb.append("[\n")
        value.items.forEachIndexed { i, item ->
            if (i > 0) sb.append(",\n")
            indent(sb, indent + 1)
            writeValue(sb, item, pretty, indent + 1)
        }
        sb.append('\n')
        indent(sb, indent)
        sb.append(']')
    }

    private fun writeObject(sb: StringBuilder, value: JsonObject, pretty: Boolean, indent: Int) {
        if (value.size == 0) { sb.append("{}"); return }
        if (!pretty) {
            sb.append('{')
            var first = true
            value.forEach { k, v ->
                if (!first) sb.append(',')
                first = false
                writeString(sb, k)
                sb.append(':')
                writeValue(sb, v, pretty = false, indent = 0)
            }
            sb.append('}')
            return
        }
        sb.append("{\n")
        var first = true
        value.forEach { k, v ->
            if (!first) sb.append(",\n")
            first = false
            indent(sb, indent + 1)
            writeString(sb, k)
            sb.append(": ")
            writeValue(sb, v, pretty = true, indent = indent + 1)
        }
        sb.append('\n')
        indent(sb, indent)
        sb.append('}')
    }

    private fun indent(sb: StringBuilder, level: Int) {
        repeat(level) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> {
                    sb.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
