package pl.dakil.notes.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone

class ArchiveTest {

    private fun entry(path: String, body: String) =
        ArchiveEntry(path) { ByteArrayInputStream(body.toByteArray()) }

    private fun zipOf(vararg entries: ArchiveEntry): ByteArray =
        ByteArrayOutputStream().also { LibraryArchive.write(entries.toList(), it) }.toByteArray()

    @Test
    fun `an archive holds every file with its folder path`() {
        val bytes = zipOf(entry("Work/Q3/Standup.md", "# Standup"), entry("Groceries.md", "milk"))

        val found = LinkedHashMap<String, String>()
        LibraryArchive.read(ByteArrayInputStream(bytes)) { path, input ->
            found[path] = input.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals(mapOf("Groceries.md" to "milk", "Work/Q3/Standup.md" to "# Standup"), found)
    }

    @Test
    fun `two archives of an unchanged library are byte-identical`() {
        val first = zipOf(entry("B.md", "b"), entry("A.md", "a"))
        // Deliberately handed in the other order: a backup folder that is itself synced must not
        // re-upload the same library every night because the listing came back shuffled.
        val second = zipOf(entry("A.md", "a"), entry("B.md", "b"))

        assertArrayEquals(first, second)
    }

    @Test
    fun `the newest archives are kept and the oldest are pruned`() {
        val names = listOf(
            "daknote-20260101-120000.zip", "daknote-20260301-120000.zip",
            "daknote-20260201-120000.zip", "daknote-20260401-120000.zip",
        ).mapNotNull(Retention::parse)

        val pruned = Retention.prune(names, keep = 2)

        assertEquals(
            listOf("daknote-20260201-120000.zip", "daknote-20260101-120000.zip"),
            pruned.map { it.fileName },
        )
    }

    @Test
    fun `pruning goes by the timestamp in the name, not by the order the folder listed them`() {
        // SAF providers give no ordering guarantee whatsoever, so trusting the listing order would
        // eventually delete the newest backup and keep the oldest.
        val shuffled = listOf(
            "daknote-20260401-120000.zip", "daknote-20260101-120000.zip",
        ).mapNotNull(Retention::parse)

        assertEquals(listOf("daknote-20260101-120000.zip"), Retention.prune(shuffled, keep = 1).map { it.fileName })
    }

    @Test
    fun `keeping none prunes nothing, so a misconfigured count cannot empty the folder`() {
        val names = listOf("daknote-20260101-120000.zip").mapNotNull(Retention::parse)

        assertEquals(emptyList<ArchiveName>(), Retention.prune(names, keep = 0))
    }

    @Test
    fun `a file the app did not name is not an archive and is never pruned`() {
        assertNull(Retention.parse("holiday-photos.zip"))
        assertNull(Retention.parse("daknote-backup.zip"))
    }

    @Test
    fun `an archive name round-trips through its own parser on a Buddhist-calendar device`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))

            val at = 1_787_926_353_000L
            val name = Retention.nameFor(at)

            assertEquals("daknote-20260828-141233.zip", name)
            assertEquals(at, Retention.parse(name)!!.takenAt)
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }
}
