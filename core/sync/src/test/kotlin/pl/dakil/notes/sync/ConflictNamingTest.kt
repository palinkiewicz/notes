package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ConflictNamingTest {

    // 2026-08-28 14:12:33 UTC
    private val at = 1_787_926_353_000L

    @Test
    fun `a conflict copy names the note, the second it was made and the device that made it`() {
        assertEquals(
            "Standup.sync-conflict-20260828-141233-K7QF2.daknote",
            ConflictNaming.nameFor("Standup.daknote", at, "K7QF2"),
        )
    }

    @Test
    fun `a conflict copy keeps the extension, so it still opens as the kind of note it is`() {
        val name = ConflictNaming.nameFor("Groceries.md", at, "K7QF2")

        // The suffix goes in the stem rather than at the end: a file called `.md.sync-conflict-…`
        // is not a Markdown file to anything that looks at extensions, this app included.
        assertTrue(name, name.endsWith(".md"))
    }

    @Test
    fun `a name with no extension is still given a stamp`() {
        assertEquals(
            "LICENSE.sync-conflict-20260828-141233-K7QF2",
            ConflictNaming.nameFor("LICENSE", at, "K7QF2"),
        )
    }

    @Test
    fun `a conflict copy is recognised as one when it is read back`() {
        val name = ConflictNaming.nameFor("Standup.daknote", at, "K7QF2")

        // Load-bearing: a rename matcher works by content hash, and a conflict copy shares its
        // bytes with the note it came from. Without this it would pair the two and move the
        // original on top of the copy.
        assertTrue(ConflictNaming.isConflictCopy(name))
    }

    @Test
    fun `an ordinary note is not mistaken for a conflict copy`() {
        assertFalse(ConflictNaming.isConflictCopy("Standup.daknote"))
        assertFalse(ConflictNaming.isConflictCopy("My sync-conflict notes.md"))
        assertFalse(ConflictNaming.isConflictCopy("Standup.sync-conflict-nonsense.md"))
    }

    @Test
    fun `a second conflict on the same note one second later is a different file`() {
        val first = ConflictNaming.nameFor("Standup.daknote", at, "K7QF2")
        val second = ConflictNaming.nameFor("Standup.daknote", at + 1_000L, "K7QF2")

        // Seconds rather than minutes in the stamp, so a burst of conflicts does not collapse into
        // one name and overwrite the very versions it was meant to preserve.
        assertNotEquals(first, second)
    }

    @Test
    fun `two devices conflicting at the same instant do not produce the same name`() {
        assertNotEquals(
            ConflictNaming.nameFor("Standup.daknote", at, "K7QF2"),
            ConflictNaming.nameFor("Standup.daknote", at, "B3M1X"),
        )
    }

    @Test
    fun `a conflict name is the same on a Turkish device in a Buddhist calendar locale`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            // Two traps at once: a Turkish locale folds `I` to a dotless `ı`, and a `th-TH-u-ca-buddhist`
            // calendar would stamp the year 2569. Either produces a name this app cannot read back.
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))

            assertEquals(
                "Standup.sync-conflict-20260828-141233-K7QF2.daknote",
                ConflictNaming.nameFor("Standup.daknote", at, "K7QF2"),
            )
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }
}
