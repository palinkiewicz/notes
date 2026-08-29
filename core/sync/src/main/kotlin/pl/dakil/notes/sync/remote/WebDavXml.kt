package pl.dakil.notes.sync.remote

import java.net.URI

/** One `<response>` of a `multistatus` body, reduced to what the engine uses. */
data class DavResource(
    val href: String,
    val isCollection: Boolean,
    val etag: String,
    val contentLength: Long,
    val lastModified: Long,
)

/**
 * A scanner for `multistatus` bodies, rather than an XML parser.
 *
 * `XmlPullParser` would do this in fewer lines, but it lives in `android.*` and would drag this
 * whole module — planner, state, backends — back into an android-library and out of plain JUnit.
 * Four properties off a well-known response shape is the same trade the JSON parser and the
 * frontmatter reader already made: read what is understood, ignore the rest, never guess.
 */
object WebDavXml {

    fun parseMultiStatus(xml: String): List<DavResource> {
        val out = ArrayList<DavResource>()
        var cursor = 0
        while (true) {
            val start = openTagStart(xml, "response", cursor) ?: break
            val closeStart = closeTagStart(xml, "response", start) ?: break
            val block = xml.substring(start, closeStart)
            cursor = closeStart + 1
            val href = text(block, "href")?.trim() ?: continue
            out += DavResource(
                href = href,
                // Namespaced as `<D:collection/>` or `<collection/>`; either way the tag appears
                // only for a directory, so its presence is the whole test.
                isCollection = openTagStart(block, "collection", 0) != null,
                etag = normaliseEtag(text(block, "getetag")),
                contentLength = text(block, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L,
                lastModified = text(block, "getlastmodified")?.let(::parseHttpDate) ?: 0L,
            )
        }
        return out
    }

    /**
     * `"abc"` and `W/"abc"` both mean the same tag.
     *
     * Servers differ on which they send, and the same server can change its mind between a
     * `PROPFIND` and a `PUT` response. Comparing the raw string would then read as "changed
     * remotely" on every pass and re-download the whole library forever.
     */
    fun normaliseEtag(raw: String?): String =
        raw?.trim()?.removePrefix("W/")?.removePrefix("w/")?.trim()?.trim('"').orEmpty()

    /** Index of the `<` opening `tag`, ignoring namespace prefixes. Closing tags do not count. */
    private fun openTagStart(xml: String, tag: String, from: Int): Int? {
        var i = from
        while (i < xml.length) {
            val open = xml.indexOf('<', i)
            if (open < 0) return null
            if (open + 1 < xml.length && xml[open + 1] != '/' && xml[open + 1] != '?' && xml[open + 1] != '!') {
                var end = open + 1
                while (end < xml.length && xml[end] != '>' && xml[end] != ' ' && xml[end] != '/' &&
                    xml[end] != '\n' && xml[end] != '\r' && xml[end] != '\t'
                ) {
                    end++
                }
                val local = xml.substring(open + 1, end).substringAfter(':')
                if (local.equals(tag, ignoreCase = true)) return open
            }
            i = open + 1
        }
        return null
    }

    /** Index of the `<` of `</tag>`, ignoring namespace prefixes. */
    private fun closeTagStart(xml: String, tag: String, from: Int): Int? {
        var i = from
        while (i < xml.length) {
            val open = xml.indexOf("</", i)
            if (open < 0) return null
            val close = xml.indexOf('>', open)
            if (close < 0) return null
            val local = xml.substring(open + 2, close).trim().substringAfter(':')
            if (local.equals(tag, ignoreCase = true)) return open
            i = close + 1
        }
        return null
    }

    private fun text(xml: String, tag: String): String? {
        val open = openTagStart(xml, tag, 0) ?: return null
        val contentStart = xml.indexOf('>', open)
        if (contentStart < 0) return null
        // `<getetag/>` carries no text at all, and must not be read as the rest of the document.
        if (xml[contentStart - 1] == '/') return null
        val closeStart = closeTagStart(xml, tag, contentStart) ?: return null
        if (closeStart <= contentStart) return null
        return unescape(xml.substring(contentStart + 1, closeStart))
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    /**
     * `Wed, 28 Aug 2026 14:12:33 GMT` — RFC 1123, parsed by hand.
     *
     * `SimpleDateFormat` would need a `Locale.ROOT` it is easy to forget, and on a Thai-calendar
     * device the forgotten one produces a year 543 out. Splitting the fixed grammar avoids the
     * question entirely; anything unrecognised becomes 0, which the planner treats as "no opinion".
     */
    fun parseHttpDate(raw: String): Long {
        val parts = raw.trim().removeSuffix("GMT").trim().split(' ', ',').filter { it.isNotEmpty() }
        if (parts.size < 5) return 0L
        val day = parts[1].toIntOrNull() ?: return 0L
        val month = MONTHS.indexOf(parts[2].lowercase()).takeIf { it >= 0 } ?: return 0L
        val year = parts[3].toIntOrNull() ?: return 0L
        val time = parts[4].split(':')
        if (time.size < 3) return 0L
        val calendar = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.ROOT,
        )
        calendar.clear()
        calendar.set(year, month, day, time[0].toIntOrNull() ?: 0, time[1].toIntOrNull() ?: 0, time[2].toIntOrNull() ?: 0)
        return calendar.timeInMillis
    }

    /** The path part of an href, which may arrive absolute or relative depending on the server. */
    fun pathOf(href: String): String = runCatching { URI(href).path }.getOrNull() ?: href
}
