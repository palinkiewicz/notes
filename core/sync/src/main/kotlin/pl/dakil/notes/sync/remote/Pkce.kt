package pl.dakil.notes.sync.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A PKCE verifier and its challenge (RFC 7636).
 *
 * Required rather than optional here: this app is a public client with no usable secret, so the
 * authorisation code is the only thing standing between an intercepted redirect and someone else's
 * Drive. The challenge is what makes an intercepted code useless without the verifier.
 */
data class PkceChallenge(val verifier: String, val challenge: String) {

    val method: String get() = "S256"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): PkceChallenge {
            val bytes = ByteArray(64).also(random::nextBytes)
            val verifier = base64Url(bytes)
            return PkceChallenge(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** URL-safe, unpadded: the spec says so, and a `+` in a query string is a space. */
        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
