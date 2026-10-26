package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageSelectionTest {

    @Test
    fun `an empty field exports every page`() {
        assertEquals(listOf(0, 1, 2), PageSelection.parse("  ", pageCount = 3))
    }

    @Test
    fun `the numbers typed are the ones printed on the pages, not indices`() {
        assertEquals(listOf(0, 1, 4), PageSelection.parse("1, 2, 5", pageCount = 5))
    }

    @Test
    fun `ranges, repeats and any order come out as one ascending run`() {
        assertEquals(listOf(0, 2, 3, 4, 6), PageSelection.parse("7 3-5;1, 4", pageCount = 8))
    }

    @Test
    fun `a range written with spaces round the dash still reads`() {
        assertEquals(listOf(1, 2), PageSelection.parse("2 - 3", pageCount = 3))
    }

    @Test
    fun `a page that does not exist is refused rather than quietly dropped`() {
        // Silently exporting fewer pages than were asked for is the one outcome nobody notices
        // until the printout is in their hand.
        assertNull(PageSelection.parse("1, 9", pageCount = 3))
        assertNull(PageSelection.parse("0", pageCount = 3))
        assertNull(PageSelection.parse("3-1", pageCount = 3))
    }

    @Test
    fun `anything that is not a list of pages is refused`() {
        assertNull(PageSelection.parse("one", pageCount = 3))
        assertNull(PageSelection.parse("1-", pageCount = 3))
        assertNull(PageSelection.parse(",", pageCount = 3))
    }
}
