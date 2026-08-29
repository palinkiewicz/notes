package pl.dakil.notes.sync.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One HTTP response, with the body already drained so the connection can be released. */
data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
) {
    val isSuccess: Boolean get() = code in 200..299
    fun text(): String = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers[name.lowercase()]

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

class HttpException(val code: Int, message: String) : Exception(message)

/**
 * The whole networking layer, on `HttpURLConnection`.
 *
 * No OkHttp, no Ktor, no Retrofit. Two backends and an OAuth exchange use perhaps a twentieth of
 * what any of those offer, and the size budget will not pay for the rest — the same trade the JSON
 * parser and the Markdown engine already made. `HttpURLConnection` is in the framework, needs no
 * keep rules, and adds nothing to the APK.
 */
object Http {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ((OutputStream) -> Unit)? = null,
        contentLength: Long = -1,
        /** Streams the response instead of buffering it. The body arrives empty when this is set. */
        onBody: ((InputStream) -> Unit)? = null,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                // Streamed rather than buffered: a `.daknote` full of ink can be megabytes, and
                // the default behaviour is to hold the whole request in memory before sending it.
                if (contentLength >= 0) {
                    connection.setFixedLengthStreamingMode(contentLength)
                } else {
                    connection.setChunkedStreamingMode(0)
                }
                connection.outputStream.use { out -> body(out.buffered()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = when {
                stream == null -> ByteArray(0)
                onBody != null && code in 200..299 -> {
                    stream.buffered().use(onBody)
                    ByteArray(0)
                }
                else -> stream.buffered().use { it.readBytes() }
            }
            val headerMap = HashMap<String, String>()
            connection.headerFields.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) headerMap[key.lowercase()] = values.first()
            }
            return HttpResponse(code, bytes, headerMap)
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        request("GET", url, headers)

    /** `application/x-www-form-urlencoded`, which is what every OAuth token endpoint wants. */
    fun postForm(url: String, form: Map<String, String>): HttpResponse {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        return request(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = { out -> out.write(encoded); out.flush() },
            contentLength = encoded.size.toLong(),
        )
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Percent-encodes one path segment, which is not the same as encoding a query parameter. */
    fun encodePathSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") {
                append(c)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    fun drain(input: InputStream): ByteArray = ByteArrayOutputStream().use { out ->
        input.copyTo(out)
        out.toByteArray()
    }
}
