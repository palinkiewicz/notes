package pl.dakil.notes.sync

import pl.dakil.notes.sync.remote.GoogleCredential
import pl.dakil.notes.sync.remote.GoogleOAuth
import pl.dakil.notes.sync.remote.PkceChallenge
import pl.dakil.notes.sync.remote.parseRfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

class DriveAndOAuthTest {

    private val credential = GoogleCredential("1234.apps.googleusercontent.com", "secret")

    @Test
    fun `a pkce challenge is the url-safe unpadded sha-256 of its verifier`() {
        val pkce = PkceChallenge.generate()

        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, pkce.challenge)
        assertEquals("S256", pkce.method)
    }

    @Test
    fun `a pkce challenge carries no padding or url-unsafe characters`() {
        repeat(20) {
            val pkce = PkceChallenge.generate()
            // A `+` in a query string is a space, and `=` ends a parameter. Either one turns the
            // exchange into an opaque `invalid_grant`.
            assertFalse(pkce.challenge, pkce.challenge.any { it == '+' || it == '/' || it == '=' })
            assertFalse(pkce.verifier, pkce.verifier.any { it == '+' || it == '/' || it == '=' })
        }
    }

    @Test
    fun `two verifiers are not the same`() {
        assertTrue(PkceChallenge.generate().verifier != PkceChallenge.generate().verifier)
    }

    @Test
    fun `the authorization url asks for offline access and the narrow drive scope`() {
        val pkce = PkceChallenge(verifier = "v", challenge = "c")

        val url = GoogleOAuth.authorizationUrl(credential, pkce, "http://127.0.0.1:41234")

        assertTrue(url, url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url, url.contains("code_challenge=c"))
        assertTrue(url, url.contains("code_challenge_method=S256"))
        // Without both of these Google withholds the refresh token on a repeat authorisation and
        // the connection quietly dies an hour later.
        assertTrue(url, url.contains("access_type=offline"))
        assertTrue(url, url.contains("prompt=consent"))
        // The narrow scope: only files this app created, which is its own folder.
        assertTrue(url, url.contains(java.net.URLEncoder.encode(GoogleOAuth.SCOPE, "UTF-8")))
        assertFalse(url, url.contains("auth%2Fdrive&"))
    }

    @Test
    fun `the redirect uri is a loopback address, which is what a desktop client is allowed`() {
        GoogleOAuth.LoopbackCatcher().use { catcher ->
            // Google removed custom-scheme *and* loopback redirects for the Android client type,
            // but kept loopback for Desktop clients — and a Desktop client is not bound to a
            // signing certificate, so one credential covers Play, F-Droid and self-builds alike.
            assertTrue(catcher.redirectUri, catcher.redirectUri.startsWith("http://127.0.0.1:"))
            assertTrue(catcher.redirectUri.substringAfterLast(':').toInt() > 0)
        }
    }

    @Test
    fun `a refresh response that omits the refresh token keeps the one already held`() {
        val json = """{"access_token":"new-access","expires_in":3599,"token_type":"Bearer"}"""

        val tokens = GoogleOAuth.parseTokens(json, previousRefresh = "the-refresh", now = 1_000_000L)

        // Google does not repeat it. Dropping it here logs the user out an hour after their first
        // successful refresh, with no way to tell why.
        assertEquals("the-refresh", tokens.refreshToken)
        assertEquals("new-access", tokens.accessToken)
    }

    @Test
    fun `a token is treated as expiring a minute early, so it cannot lapse mid-upload`() {
        val json = """{"access_token":"a","expires_in":3600}"""

        val tokens = GoogleOAuth.parseTokens(json, previousRefresh = "", now = 0L)

        assertEquals(3540_000L, tokens.expiresAt)
    }

    @Test
    fun `an empty client id is not usable, so the screen can ask for one instead of failing later`() {
        assertFalse(GoogleCredential("", "").isUsable)
        assertTrue(credential.isUsable)
    }

    @Test
    fun `a drive timestamp is read as an instant on a device whose calendar is not gregorian`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))

            assertEquals(1_787_926_353_000L, parseRfc3339("2026-08-28T14:12:33.000Z"))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `a drive timestamp this app cannot read is no opinion rather than a wrong one`() {
        assertEquals(0L, parseRfc3339("whenever"))
        assertEquals(0L, parseRfc3339(""))
    }
}
