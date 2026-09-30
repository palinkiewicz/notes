package pl.dakil.notes.sync.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dakil.notes.model.json.JsonReader
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The OAuth client the user registered. There is no built-in one.
 *
 * `drive.file` is a *sensitive* scope: an unverified project carries a lifetime, unresettable
 * hundred-user cap, and verification takes weeks and wants a privacy policy, a homepage and a demo
 * video. Shipping one credential for everybody would mean the app stops working for user 101 and
 * can never be fixed. So the user brings their own, which is also what rclone does — it costs one
 * fiddly setup and then works forever, for everyone, with no gatekeeper.
 *
 * The client type must be **Desktop app**. Google removed both custom-scheme and loopback redirects
 * for the *Android* client type, but the loopback flow is explicitly still supported for Desktop —
 * and a Desktop client is not bound to a signing certificate, so the same credential works for a
 * Play build, an F-Droid build and a self-build alike.
 */
data class GoogleCredential(val clientId: String, val clientSecret: String) {
    val isUsable: Boolean get() = clientId.isNotBlank()
}

data class GoogleTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis. Refreshed a minute early, because a token that expires mid-upload is a failure. */
    val expiresAt: Long,
)

/**
 * Authorisation-code flow with PKCE, against a loopback redirect.
 *
 * No Google Play Services anywhere in it: the browser does the interactive part and a one-shot
 * socket on `127.0.0.1` catches the answer. That is the whole reason this works on a device with no
 * Google apps installed at all.
 */
object GoogleOAuth {

    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    /** A socket bound to a free loopback port, waiting for the browser to come back. */
    class LoopbackCatcher(private val io: CoroutineDispatcher = Dispatchers.IO) : Closeable {

        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        val redirectUri: String get() = "http://127.0.0.1:${socket.localPort}"

        /**
         * Blocks until the browser redirects, and answers it with a page saying so.
         *
         * Returns the authorisation code, or null if the user denied consent or the socket closed.
         */
        suspend fun awaitCode(): String? = withContext(io) {
            runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use null
                    // `GET /?code=…&scope=… HTTP/1.1` — only the query matters.
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val params = target.substringAfter('?', "").split('&')
                        .mapNotNull { pair ->
                            val i = pair.indexOf('=')
                            if (i <= 0) null
                            else java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                                java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                        }.toMap()

                    val body = if (params["code"] != null) DONE_PAGE else DENIED_PAGE
                    client.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                                "Connection: close\r\n\r\n" + body).toByteArray(Charsets.UTF_8)
                        )
                        flush()
                    }
                    params["code"]
                }
            }.getOrNull()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** The URL to open in a browser. */
    fun authorizationUrl(
        credential: GoogleCredential,
        pkce: PkceChallenge,
        redirectUri: String,
    ): String = buildString {
        append(AUTH_ENDPOINT)
        append("?client_id=").append(Http.encode(credential.clientId))
        append("&redirect_uri=").append(Http.encode(redirectUri))
        append("&response_type=code")
        append("&scope=").append(Http.encode(SCOPE))
        append("&code_challenge=").append(pkce.challenge)
        append("&code_challenge_method=").append(pkce.method)
        // Without both of these Google returns no refresh token on a repeat authorisation, and the
        // connection silently stops working an hour later.
        append("&access_type=offline")
        append("&prompt=consent")
    }

    suspend fun exchange(
        credential: GoogleCredential,
        pkce: PkceChallenge,
        redirectUri: String,
        code: String,
        now: Long = System.currentTimeMillis(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Result<GoogleTokens> = withContext(io) {
        runCatching {
            val response = Http.postForm(
                TOKEN_ENDPOINT,
                mapOf(
                    "client_id" to credential.clientId,
                    "client_secret" to credential.clientSecret,
                    "code" to code,
                    "code_verifier" to pkce.verifier,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to redirectUri,
                ),
            )
            if (!response.isSuccess) throw HttpException(response.code, response.text())
            parseTokens(response.text(), previousRefresh = "", now = now)
        }
    }

    suspend fun refresh(
        credential: GoogleCredential,
        refreshToken: String,
        now: Long = System.currentTimeMillis(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Result<GoogleTokens> = withContext(io) {
        runCatching {
            val response = Http.postForm(
                TOKEN_ENDPOINT,
                mapOf(
                    "client_id" to credential.clientId,
                    "client_secret" to credential.clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            )
            if (!response.isSuccess) throw HttpException(response.code, response.text())
            parseTokens(response.text(), previousRefresh = refreshToken, now = now)
        }
    }

    /** Parsed with the house JSON DOM; there is no serialisation library in this app. */
    fun parseTokens(json: String, previousRefresh: String, now: Long): GoogleTokens {
        val root = JsonReader.parseObject(json)
        val expiresIn = root.long("expires_in", 3600L)
        return GoogleTokens(
            accessToken = root.string("access_token"),
            // A refresh response does not repeat the refresh token; dropping it here would log the
            // user out an hour after their first successful refresh.
            refreshToken = root.string("refresh_token").ifEmpty { previousRefresh },
            expiresAt = now + (expiresIn - 60) * 1000L,
        )
    }

    private const val DONE_PAGE =
        "<!doctype html><meta charset=utf-8><title>Connected</title>" +
            "<body style=\"font-family:system-ui;text-align:center;padding:3rem\">" +
            "<h1>Connected</h1><p>You can close this tab and go back to the app.</p>"

    private const val DENIED_PAGE =
        "<!doctype html><meta charset=utf-8><title>Not connected</title>" +
            "<body style=\"font-family:system-ui;text-align:center;padding:3rem\">" +
            "<h1>Not connected</h1><p>No access was granted. You can close this tab.</p>"
}
