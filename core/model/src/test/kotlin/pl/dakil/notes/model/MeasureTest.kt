package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class MeasureTest {

    private lateinit var original: Locale

    /**
     * Pinned to a decimal-point locale so the assertions can quote literal strings. The separator
     * itself is locale-dependent by design; what is being tested here is everything around it.
     */
    @Before
    fun pinLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @org.junit.After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `an inch is seventy-two points in every direction`() {
        assertEquals(1f, MeasurementUnit.INCH.fromPoints(72f), 1e-4f)
        assertEquals(72f, MeasurementUnit.INCH.toPoints(1f), 1e-4f)
        assertEquals(2.54f, MeasurementUnit.CENTIMETRE.fromPoints(72f), 1e-4f)
        assertEquals(25.4f, MeasurementUnit.MILLIMETRE.fromPoints(72f), 1e-4f)
    }

    @Test
    fun `A4 measures the 210 by 297 millimetres it is supposed to`() {
        val mm = MeasurementUnit.MILLIMETRE
        assertEquals(210f, mm.fromPoints(PageSize.Kind.A4.width), 0.5f)
        assertEquals(297f, mm.fromPoints(PageSize.Kind.A4.height), 0.5f)
    }

    /**
     * The nominal size is what belongs on the card. Points are whole numbers, so a centimetre asked
     * for two decimals reports A4 as 20,99 cm — the storage rounding, not the paper.
     */
    @Test
    fun `paper sizes read back as their nominal centimetres`() {
        val cm = MeasurementUnit.CENTIMETRE
        assertEquals("21", cm.format(PageSize.Kind.A4.width, withSuffix = false))
        assertEquals("29.7 cm", cm.format(PageSize.Kind.A4.height))
        assertEquals("14.8", cm.format(PageSize.Kind.A5.width, withSuffix = false))
        assertEquals("25 cm", cm.format(PageSize.Kind.B5.height))
    }

    @Test
    fun `Letter measures exactly eight and a half by eleven inches`() {
        val inch = MeasurementUnit.INCH
        assertEquals("8.5", inch.format(PageSize.Kind.LETTER.width, withSuffix = false))
        assertEquals("11 in", inch.format(PageSize.Kind.LETTER.height))
    }

    /**
     * The reason snapping exists: without it a slider leaves 19.843 pt sitting behind a readout
     * that says "0.7 cm", and reopening the dialog would show a number nobody typed.
     */
    @Test
    fun `snapping a value makes it survive a display round-trip`() {
        for (unit in MeasurementUnit.entries) {
            for (raw in listOf(0f, 19.843f, 56f, 123.456f, 282.9f)) {
                val snapped = unit.snapPoints(raw)
                val reparsed = unit.parse(unit.format(snapped, withSuffix = false))!!
                assertTrue(
                    "$unit lost $snapped on the way through '${unit.format(snapped)}'",
                    kotlin.math.abs(snapped - reparsed) < 0.01f,
                )
            }
        }
    }

    @Test
    fun `formatting drops trailing zeros so a round number reads as one`() {
        assertEquals("2 cm", MeasurementUnit.CENTIMETRE.format(MeasurementUnit.CENTIMETRE.toPoints(2f)))
        assertEquals("1.5 cm", MeasurementUnit.CENTIMETRE.format(MeasurementUnit.CENTIMETRE.toPoints(1.5f)))
        // The default 56 pt margin is 1.98 cm; a user reads it as the 2 cm it was chosen to be.
        assertEquals("2 cm", MeasurementUnit.CENTIMETRE.format(56f))
        assertEquals("7 mm", MeasurementUnit.MILLIMETRE.format(MeasurementUnit.MILLIMETRE.toPoints(7f)))
        assertEquals("0", MeasurementUnit.INCH.format(0f, withSuffix = false))
    }

    /**
     * The device keyboard's decimal key and the user's habit do not always agree with the locale,
     * and rejecting the "wrong" separator is never the helpful answer.
     */
    @Test
    fun `parsing accepts either decimal separator and rejects anything else`() {
        val cm = MeasurementUnit.CENTIMETRE
        assertEquals(cm.toPoints(2.5f), cm.parse("2.5")!!, 1e-3f)
        assertEquals(cm.toPoints(2.5f), cm.parse("2,5")!!, 1e-3f)
        assertEquals(cm.toPoints(2.5f), cm.parse("  2,5 ")!!, 1e-3f)
        assertNull(cm.parse(""))
        assertNull(cm.parse("   "))
        assertNull(cm.parse("abc"))
        assertNull(cm.parse("2,5cm"))
    }

    @Test
    fun `a fresh note's margins read back as all four linked`() {
        assertEquals(MarginLink.ALL, PageMargins.DEFAULT.link())
        assertEquals(MarginLink.ALL, PageMargins.NONE.link())
    }

    @Test
    fun `equal pairs read back as axis-linked and a mismatch as independent`() {
        assertEquals(MarginLink.AXES, PageMargins(left = 40f, top = 56f, right = 40f, bottom = 56f).link())
        assertEquals(MarginLink.EACH, PageMargins(left = 40f, top = 56f, right = 50f, bottom = 56f).link())
        assertEquals(MarginLink.EACH, PageMargins(left = 40f, top = 56f, right = 40f, bottom = 60f).link())
    }

    @Test
    fun `the summary collapses to one number when every side agrees`() {
        val cm = MeasurementUnit.CENTIMETRE
        val two = cm.toPoints(2f)
        assertEquals("2 cm", PageMargins(two, two, two, two).summary(cm))
    }

    @Test
    fun `the summary is vertical then horizontal when the axes differ`() {
        val cm = MeasurementUnit.CENTIMETRE
        val margins = PageMargins(left = cm.toPoints(1.5f), top = cm.toPoints(2f),
            right = cm.toPoints(1.5f), bottom = cm.toPoints(2f))
        assertEquals("2 1.5 cm", margins.summary(cm))
    }

    @Test
    fun `the summary is top right bottom left when nothing matches`() {
        val cm = MeasurementUnit.CENTIMETRE
        val margins = PageMargins(
            left = cm.toPoints(4f), top = cm.toPoints(1f),
            right = cm.toPoints(2f), bottom = cm.toPoints(3f),
        )
        assertEquals("1 2 3 4 cm", margins.summary(cm))
    }

    /**
     * The picker offers the sizes in declaration order, so the declaration order *is* the design.
     */
    @Test
    fun `each paper family is offered smallest first and stays contiguous`() {
        val kinds = PageSize.Kind.entries
        val groups = kinds.map { it.group }
        assertEquals(groups.distinct(), groups.fold(emptyList<PageSize.SizeGroup>()) { acc, g ->
            if (acc.lastOrNull() == g) acc else acc + g
        })

        for (group in PageSize.SizeGroup.entries) {
            val areas = kinds.filter { it.group == group }.map { it.width * it.height }
            assertEquals("$group is out of order", areas.sorted(), areas)
        }
    }

    /**
     * Reordering or extending [PageSize.Kind] must never disturb a saved note, which is only safe
     * while the name is the thing that is stored.
     */
    @Test
    fun `every paper size has a distinct name to serialize under`() {
        val names = PageSize.Kind.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
