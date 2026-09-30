package pl.dakil.notes.sync

import pl.dakil.notes.sync.remote.WebDavXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class WebDavXmlTest {

    private val nextcloud = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/remote.php/dav/files/dakil/Notes/</d:href>
            <d:propstat><d:prop>
              <d:resourcetype><d:collection/></d:resourcetype>
              <d:getlastmodified>Wed, 28 Aug 2026 14:12:33 GMT</d:getlastmodified>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/dakil/Notes/Groceries.md</d:href>
            <d:propstat><d:prop>
              <d:resourcetype/>
              <d:getetag>&quot;abc123&quot;</d:getetag>
              <d:getcontentlength>42</d:getcontentlength>
              <d:getlastmodified>Wed, 28 Aug 2026 14:12:33 GMT</d:getlastmodified>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `a multistatus response yields one entry per resource with its etag`() {
        val resources = WebDavXml.parseMultiStatus(nextcloud)

        assertEquals(2, resources.size)
        val file = resources[1]
        assertEquals("abc123", file.etag)
        assertEquals(42L, file.contentLength)
    }

    @Test
    fun `a collection is reported as a directory and a file is not`() {
        val resources = WebDavXml.parseMultiStatus(nextcloud)

        assertTrue(resources[0].isCollection)
        assertFalse(resources[1].isCollection)
    }

    @Test
    fun `a weak etag is reduced to the tag itself, so it compares against what was stored`() {
        val xml = """<multistatus><response><href>/a.md</href>
            <getetag>W/"weak-tag"</getetag></response></multistatus>"""

        assertEquals("weak-tag", WebDavXml.parseMultiStatus(xml).single().etag)
    }

    @Test
    fun `a server that namespaces its tags differently is still understood`() {
        // Apache uses `D:`, Nextcloud uses `d:`, some servers use none at all. Refusing any one of
        // them would mean refusing that server entirely.
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/a.md</D:href>
            <D:getcontentlength>7</D:getcontentlength></D:response></D:multistatus>"""

        assertEquals(7L, WebDavXml.parseMultiStatus(xml).single().contentLength)
    }

    @Test
    fun `an http date is read as an instant, on a device whose calendar is not gregorian`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))

            // 2026-08-28T14:12:33Z. A forgotten Locale.ROOT here puts the year 543 out and makes
            // every remote file look newer than every local one, forever.
            assertEquals(1_787_926_353_000L, WebDavXml.parseHttpDate("Wed, 28 Aug 2026 14:12:33 GMT"))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `a date the server wrote in some other shape is no opinion rather than a wrong one`() {
        assertEquals(0L, WebDavXml.parseHttpDate("yesterday-ish"))
    }

    @Test
    fun `an href is reduced to its path whether the server sent it absolute or relative`() {
        assertEquals("/dav/Notes/A.md", WebDavXml.pathOf("https://cloud.example.com/dav/Notes/A.md"))
        assertEquals("/dav/Notes/A.md", WebDavXml.pathOf("/dav/Notes/A.md"))
    }
}
