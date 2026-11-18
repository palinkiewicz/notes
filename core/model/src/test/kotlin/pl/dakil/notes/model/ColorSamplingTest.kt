package pl.dakil.notes.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSamplingTest {

    /** A 4×3 image whose pixel values spell out where they are: 10·y + x + 1. */
    private val width = 4
    private val height = 3
    private val image = IntArray(width * height) { i -> 10 * (i / width) + i % width + 1 }

    /** Behaves as `Bitmap.getPixels` does, including refusing a rectangle outside the image. */
    private val read = { out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int ->
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height) { "outside the bitmap" }
        for (row in 0 until h) {
            for (column in 0 until w) {
                out[offset + row * stride + column] = image[(y + row) * width + x + column]
            }
        }
    }

    private fun around(x: Int, y: Int, side: Int = 3): IntArray =
        IntArray(side * side).also { ColorSampling.readAround(it, side, x, y, width, height, read) }

    @Test
    fun `a highlighter keeps its transparency when it takes a colour from the page`() {
        val pixel = 0xFF2E7D32.toInt()
        val highlighter = 0x66FFEB3B
        assertEquals(0x662E7D32, ColorSampling.picked(pixel, highlighter))
    }

    @Test
    fun `a touch belongs to the pixel it is inside, not the nearest edge`() {
        assertEquals(2, ColorSampling.pixelAt(2.9f, 4))
    }

    @Test
    fun `a touch dragged off the image takes the pixel at its edge`() {
        assertEquals(0, ColorSampling.pixelAt(-5f, 4))
        assertEquals(3, ColorSampling.pixelAt(400f, 4))
    }

    @Test
    fun `the magnifier shows the neighbourhood with the sampled pixel in the middle`() {
        assertArrayEquals(
            intArrayOf(
                1, 2, 3,
                11, 12, 13,
                21, 22, 23,
            ),
            around(1, 1),
        )
    }

    @Test
    fun `at the top left corner the sampled pixel stays in the middle cell`() {
        // Shifting the contents to fill the square would put a different pixel under the crosshair.
        assertArrayEquals(
            intArrayOf(
                0, 0, 0,
                0, 1, 2,
                0, 11, 12,
            ),
            around(0, 0),
        )
    }

    @Test
    fun `at the bottom right corner the cells past the image stay empty`() {
        assertArrayEquals(
            intArrayOf(
                13, 14, 0,
                23, 24, 0,
                0, 0, 0,
            ),
            around(3, 2),
        )
    }

    @Test
    fun `cells left over from the previous position are cleared`() {
        val cells = around(1, 1)
        ColorSampling.readAround(cells, 3, 0, 0, width, height, read)
        assertEquals(0, cells[0])
    }

    @Test
    fun `the magnifier sits above the finger, centred on it`() {
        val (x, y) = ColorSampling.loupeOrigin(500f, 500f, 100f, 120f, 40f, 1000f, 1000f)
        assertEquals(450f, x)
        assertEquals(340f, y)
    }

    @Test
    fun `with no room above the finger the magnifier goes below it`() {
        val (_, y) = ColorSampling.loupeOrigin(500f, 100f, 100f, 120f, 40f, 1000f, 1000f)
        assertEquals(140f, y)
    }

    @Test
    fun `the magnifier stays inside the view at its sides`() {
        assertEquals(0f, ColorSampling.loupeOrigin(10f, 500f, 100f, 120f, 40f, 1000f, 1000f).first)
        assertEquals(900f, ColorSampling.loupeOrigin(990f, 500f, 100f, 120f, 40f, 1000f, 1000f).first)
    }

    @Test
    fun `a view too short for the magnifier either way still keeps it on screen`() {
        val (_, y) = ColorSampling.loupeOrigin(500f, 60f, 100f, 120f, 40f, 1000f, 150f)
        assertEquals(30f, y)
    }
}
