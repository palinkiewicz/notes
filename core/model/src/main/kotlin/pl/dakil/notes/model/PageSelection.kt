package pl.dakil.notes.model

/**
 * Reads the pages a user typed into an export — `1, 2, 5-7` — as 0-based page indices.
 *
 * Numbers are the 1-based ones printed on the pages, because that is what the user is reading
 * off. Commas, semicolons and spaces all separate, a range is two numbers and a dash, and blank
 * means every page.
 */
object PageSelection {

    /**
     * The selected pages, ascending and without repeats; null when [text] names a page that does
     * not exist or is not a list of pages at all. Null rather than a best guess: exporting pages
     * other than the ones asked for is worse than refusing, and the field can say why.
     */
    fun parse(text: String, pageCount: Int): List<Int>? {
        if (text.isBlank()) return List(pageCount) { it }
        val pages = sortedSetOf<Int>()
        // Spaces round a dash belong to the range, not between two entries.
        val ranges = text.replace(DASH_SPACING, "-")
        for (part in ranges.split(',', ';', ' ').map(String::trim).filter(String::isNotEmpty)) {
            val dash = part.indexOf('-')
            val (first, last) = if (dash < 0) {
                val page = part.toIntOrNull() ?: return null
                page to page
            } else {
                val from = part.substring(0, dash).trim().toIntOrNull() ?: return null
                val to = part.substring(dash + 1).trim().toIntOrNull() ?: return null
                from to to
            }
            if (first < 1 || last > pageCount || first > last) return null
            for (page in first..last) pages += page - 1
        }
        return pages.toList().takeIf { it.isNotEmpty() }
    }

    /**
     * The inverse of [parse]: 0-based [pages] written the way a user would type them, runs as ranges
     * — `1-3, 5`. Every one of [pageCount] pages is the blank that [parse] reads as all of them.
     */
    fun format(pages: Collection<Int>, pageCount: Int): String {
        val sorted = pages.filter { it in 0 until pageCount }.toSortedSet().toList()
        if (sorted.size == pageCount) return ""
        val parts = ArrayList<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j == i) "${sorted[i] + 1}" else "${sorted[i] + 1}-${sorted[j] + 1}"
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    private val DASH_SPACING = Regex("""\s*-\s*""")
}
