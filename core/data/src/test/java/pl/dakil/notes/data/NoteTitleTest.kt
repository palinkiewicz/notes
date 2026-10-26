package pl.dakil.notes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTitleTest {

    @Test
    fun `a note in a picked folder is titled by its file name, not its whole path`() {
        val ref = StoreRef(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments" +
                "/document/primary%3ADocuments%2Fzz-scratch%2FScratch.md",
        )
        assertEquals("Scratch", ref.noteTitle())
    }

    @Test
    fun `a name with escapes, pluses and non-ASCII letters decodes to what the user typed`() {
        val ref = StoreRef("content://x/document/primary%3ANotes%2FC%2B%2B%20i%20%C5%BC%C3%B3%C5%82w.daknote")
        assertEquals("C++ i żółw", ref.noteTitle())
    }

    @Test
    fun `a plain file path is unchanged`() {
        assertEquals("Plan 100%", StoreRef("/data/notes/Plan 100%.md").noteTitle())
    }
}
