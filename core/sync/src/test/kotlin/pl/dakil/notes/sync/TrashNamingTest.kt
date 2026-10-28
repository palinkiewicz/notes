package pl.dakil.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TrashNamingTest {

    private val at = 1_793_220_912_345L // 2026-10-28T20:55:12.345Z
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a batch's folder says when and where from, and reads back exactly`() {
        val name = TrashNaming.entryName(at, "Work/Q3")
        assertEquals("20261028T205512.345Z Work%2FQ3", name)
        assertEquals(TrashNaming.Entry(at, "Work/Q3"), TrashNaming.parseEntry(name))
    }

    @Test
    fun `something deleted from the root has no path in its name`() {
        val name = TrashNaming.entryName(at, "")
        assertEquals(TrashNaming.Entry(at, ""), TrashNaming.parseEntry(name))
    }

    @Test
    fun `a folder name with a percent sign or spaces survives the round trip`() {
        val path = "100% done/Old notes"
        assertEquals(path, TrashNaming.parseEntry(TrashNaming.entryName(at, path))!!.originPath)
    }

    @Test
    fun `a folder in the trash this app did not make is not read as one of its batches`() {
        // So the purge leaves it alone rather than guessing at a date for it.
        assertNull(TrashNaming.parseEntry("Recipes"))
        assertNull(TrashNaming.parseEntry("20261028T205512Z Work"))
        assertNull(TrashNaming.parseEntry("20261028T205512.345Z bad%zz"))
    }

    @Test
    fun `the name is the same whatever calendar the device uses`() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("th-TH-u-ca-buddhist", "fa-IR", "tr-TR", "pl-PL")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("20261028T205512.345Z", TrashNaming.entryName(at, ""))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `an item is kept thirty days, and the last day still counts as one`() {
        assertEquals(30, TrashNaming.daysLeft(at, at))
        assertEquals(1, TrashNaming.daysLeft(at, at + 29 * day + 1))
        assertFalse(TrashNaming.isExpired(at, at + 30 * day - 1))
        assertTrue(TrashNaming.isExpired(at, at + 30 * day))
        assertEquals(0, TrashNaming.daysLeft(at, at + 31 * day))
    }

    @Test
    fun `sync carries the trash and still ignores every other hidden folder`() {
        assertFalse(SyncPlanner.isIgnored("${TrashNaming.DIR}/20261028T205512.345Z/Plan.daknote"))
        assertTrue(SyncPlanner.isIgnored(".stfolder/x.md"))
        assertTrue(SyncPlanner.isIgnored("Work/${TrashNaming.DIR}/Plan.daknote"))
        assertTrue(SyncPlanner.isIgnored("${TrashNaming.DIR}/.git/config"))
    }
}
