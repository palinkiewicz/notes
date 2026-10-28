package pl.dakil.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.dakil.notes.model.ImageBlock
import pl.dakil.notes.ui.sheet.decodePrintedPicture
import java.io.ByteArrayOutputStream

/**
 * What a photograph becomes inside an exported PDF — a question only the platform's own PDF backend
 * can answer, so it is asked on a device.
 *
 * A phone camera's HDR photograph carries a gain map beside its pixels, and the platform draws such
 * a bitmap through a shader a PDF cannot hold. The backend fell back to painting the whole page as
 * one 72 dpi bitmap: at "Original" quality, every camera photo on the page came out blurred while a
 * screenshot beside it stayed sharp.
 */
@RunWith(AndroidJUnit4::class)
class PrintedPictureTest {

    /** An Ultra HDR JPEG, encoded by the platform the way a camera would save one. */
    private fun hdrPhotograph(width: Int, height: Int): ByteArray {
        val pixels = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels.eraseColor(Color.rgb(200, 120, 40))
        val map = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888)
        map.eraseColor(Color.GRAY)
        pixels.gainmap = Gainmap(map)
        return ByteArrayOutputStream().also { pixels.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** A one-page PDF with [picture] drawn small on it, as the PDF's own text. */
    private fun pdfOf(picture: ImageBitmap): String {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawBitmap(
            picture.asAndroidBitmap(),
            null,
            android.graphics.Rect(100, 100, 300, 250),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        document.finishPage(page)
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun assumeGainmaps(bytes: ByteArray) {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assumeTrue(
            "this device does not decode gain maps",
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).hasGainmap(),
        )
    }

    @Test
    fun anHdrPhotographIsEmbeddedAtItsOwnResolution() {
        val bytes = hdrPhotograph(1600, 1200)
        assumeGainmaps(bytes)

        val picture = decodePrintedPicture(bytes, ImageBlock.UNCROPPED, 0)!!

        assertFalse(picture.asAndroidBitmap().hasGainmap())
        assertTrue(pdfOf(picture).contains("/Width 1600"))
    }
}
