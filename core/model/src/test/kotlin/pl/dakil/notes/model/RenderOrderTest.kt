package pl.dakil.notes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.model.RenderLayer.FOUNTAIN_PEN
import pl.dakil.notes.model.RenderLayer.HIGHLIGHTER
import pl.dakil.notes.model.RenderLayer.IMAGES
import pl.dakil.notes.model.RenderLayer.MARKER
import pl.dakil.notes.model.RenderLayer.PEN
import pl.dakil.notes.model.RenderLayer.PENCIL
import pl.dakil.notes.model.RenderLayer.TEXT

class RenderOrderTest {

    private fun order(text: String) = RenderOrder.decode(text)

    @Test
    fun `by default pictures are under text, the highlighter under nothing but them, and pens mix on top`() {
        val order = RenderOrder.DEFAULT
        assertEquals(0, order.levelOf(IMAGES))
        assertEquals(1, order.levelOf(TEXT))
        assertEquals(2, order.levelOf(HIGHLIGHTER))
        // One level for all four, so a pen stroke over a pencil stroke is still over it.
        for (pen in listOf(PEN, FOUNTAIN_PEN, PENCIL, MARKER)) assertEquals(3, order.levelOf(pen))
    }

    @Test
    fun `every stroke finds its level through the tool that drew it`() {
        val order = RenderOrder.DEFAULT
        assertEquals(order.levelOf(HIGHLIGHTER), order.levelOf(ToolId.HIGHLIGHTER))
        assertEquals(order.levelOf(MARKER), order.levelOf(ToolId.MARKER))
        // A tool that leaves no ink cannot be asked about, but must not be able to crash a renderer.
        assertEquals(order.levelOf(PEN), order.levelOf(ToolId.LASSO))
    }

    @Test
    fun `raising a tool out of a shared level stands it alone just above`() {
        val raised = RenderOrder.DEFAULT.raise(PENCIL)
        assertEquals("images;text;highlighter;marker,fountainPen,pen;pencil", raised.encode())
    }

    @Test
    fun `lowering a tool out of a shared level stands it alone just below`() {
        val lowered = RenderOrder.DEFAULT.lower(PEN)
        assertEquals("images;text;highlighter;pen;marker,pencil,fountainPen", lowered.encode())
    }

    @Test
    fun `a lone tool passes a neighbouring tool in two steps, joining it on the way`() {
        // Each press has to do one visible thing; passing in one step would leave no way to group.
        val joined = RenderOrder.DEFAULT.raise(HIGHLIGHTER)
        assertEquals("images;text;highlighter,marker,pencil,fountainPen,pen", joined.encode())
        val passed = joined.raise(HIGHLIGHTER)
        assertEquals("images;text;marker,pencil,fountainPen,pen;highlighter", passed.encode())
    }

    @Test
    fun `a tool joins a level at the end it came in from, so the list does not reshuffle`() {
        val split = order("images;text;highlighter;marker,fountainPen;pen;pencil")
        assertEquals("images;text;highlighter;marker,fountainPen;pen,pencil", split.lower(PENCIL).encode())
        assertEquals("images;text;highlighter;marker,fountainPen;pen,pencil", split.raise(PEN).encode())
    }

    @Test
    fun `text and pictures never join anything but swap past a whole level`() {
        val raised = RenderOrder.DEFAULT.raise(TEXT)
        assertEquals("images;highlighter;text;marker,pencil,fountainPen,pen", raised.encode())
        val overPens = raised.raise(TEXT)
        assertEquals("images;highlighter;marker,pencil,fountainPen,pen;text", overPens.encode())
        // And ink beside them swaps rather than joins too.
        assertEquals("images;highlighter;text;marker,pencil,fountainPen,pen", RenderOrder.DEFAULT.lower(HIGHLIGHTER).encode())
    }

    @Test
    fun `the ends of the stack are where the arrows stop`() {
        assertFalse(RenderOrder.DEFAULT.canLower(IMAGES))
        assertTrue(RenderOrder.DEFAULT.canRaise(IMAGES))
        val penOnTop = RenderOrder.DEFAULT.raise(PEN)
        assertFalse(penOnTop.canRaise(PEN))
        // Still inside the group at the top, a pen can always step out of it.
        assertTrue(RenderOrder.DEFAULT.canRaise(FOUNTAIN_PEN))
    }

    @Test
    fun `a level emptied by a move is gone rather than left as a blank`() {
        val split = order("images;text;highlighter;pen;pencil,marker,fountainPen")
        val joined = split.raise(PEN)
        assertEquals(4, joined.levels.size)
    }

    @Test
    fun `the encoding reads back to the same order`() {
        val arranged = RenderOrder.DEFAULT.raise(PENCIL).raise(TEXT).lower(MARKER)
        assertEquals(arranged, RenderOrder.decode(arranged.encode()))
    }

    @Test
    fun `nothing saved, or nothing readable, is the default`() {
        assertEquals(RenderOrder.DEFAULT, RenderOrder.decode(null))
        assertEquals(RenderOrder.DEFAULT, RenderOrder.decode(""))
        assertEquals(RenderOrder.DEFAULT, RenderOrder.decode("crayon;;,,"))
    }

    @Test
    fun `unknown keys and repeats are dropped`() {
        val read = order("images;crayon;text;pen,pen;highlighter;marker,pencil,fountainPen")
        assertEquals("images;text;pen;highlighter;marker,pencil,fountainPen", read.encode())
    }

    @Test
    fun `a layer the saved order does not mention lands where the default puts it, not on top`() {
        // What a layer added in a later build looks like to the order a user saved before it existed.
        assertEquals(
            "images;text;highlighter;pencil,fountainPen,pen,marker",
            order("images;text;highlighter;pencil,fountainPen,pen").encode(),
        )
        assertEquals(
            "images;text;highlighter;pen;marker,pencil,fountainPen",
            order("images;highlighter;pen;marker,pencil,fountainPen").encode(),
        )
    }

    @Test
    fun `text or pictures saved inside a shared level are taken back out of it`() {
        // Not something this build writes, but a hand-edited or future preference must not leave a
        // renderer holding a level it cannot paint.
        val read = order("images;highlighter;text,pen,pencil;marker,fountainPen")
        assertEquals("images;highlighter;text;pen,pencil;marker,fountainPen", read.encode())
    }
}
