package tools.supernote.scan

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

internal object SignInProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    private const val AUTH_SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private const val APP_NAME = "SuperScanner"

    data class Pending(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirect: String,
        val clientId: String?,
        val priorSubject: String?,
        val createdAt: Long,
    )

    data class Credentials(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val scope: String,
        val expiresAt: Long,
        val earliestRefreshAt: Long,
        val subject: String,
        val email: String?,
        val clientId: String,
    )

    fun newHostId(): String = "urn:uuid:${UUID.randomUUID()}"

    fun createPending(clientId: String?, priorSubject: String?, hostId: String, port: Int, now: Long = System.currentTimeMillis()): Pair<Pending, Uri> {
        require(port in 1..65535)
        val pending = Pending(randomToken(), randomToken(), randomToken(), "http://127.0.0.1:$port/auth/callback", clientId, priorSubject, now)
        require(hostId.matches(Regex("urn:uuid:[a-fA-F0-9-]{36}")))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray(Charsets.US_ASCII)))
        val uri = Uri.parse(AUTHORIZE).buildUpon().apply {
            appendQueryParameter("client_id", pending.clientId ?: "dynamic_agent_client")
            if (pending.clientId == null) appendQueryParameter("agent_name_hint", APP_NAME)
            appendQueryParameter("ext_agent_host_id", hostId)
            appendQueryParameter("response_type", "code")
            appendQueryParameter("redirect_uri", pending.redirect)
            appendQueryParameter("scope", AUTH_SCOPE)
            appendQueryParameter("resource", RESOURCE)
            appendQueryParameter("state", pending.state)
            appendQueryParameter("nonce", pending.nonce)
            appendQueryParameter("code_challenge_method", "S256")
            appendQueryParameter("code_challenge", challenge)
        }.build()
        return pending to uri
    }

    fun exchangeForm(pending: Pending, callback: Map<String, String>, now: Long = System.currentTimeMillis()): Map<String, String> {
        require(now >= pending.createdAt && now - pending.createdAt <= TEN_MINUTES) { "Sign-in expired. Start a fresh attempt." }
        require(callback["state"] == pending.state) { "Sign-in return could not be verified." }
        require(callback["error"] == null) { "Sign-in was declined. Start again when ready." }
        val clientId = callback["client_id"] ?: pending.clientId
        require(clientId != null && clientId.matches(Regex("oaiapp_[A-Za-z0-9_-]+"))) { "ChatGPT registration was incomplete." }
        require(pending.clientId == null || pending.clientId == clientId) { "Sign-in returned a different registration." }
        val code = callback["code"]
        require(!code.isNullOrBlank() && code.length <= 8192) { "Sign-in return was incomplete." }
        return linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to pending.verifier,
            "redirect_uri" to pending.redirect,
            "resource" to RESOURCE,
        )
    }

    fun parseCredentials(body: JSONObject, clientId: String, jwks: JSONObject, nonce: String, priorSubject: String?, now: Long = System.currentTimeMillis()): Credentials {
        // JSONObject.optString() stringifies JSONObject.NULL as "null" on Android.
        // Credential fields must be actual JSON strings so null cannot become a token.
        val access = body.opt("access_token") as? String ?: ""
        val refresh = body.opt("refresh_token") as? String ?: ""
        val idToken = body.opt("id_token") as? String ?: ""
        val scope = body.opt("scope") as? String ?: ""
        val tokenType = body.opt("token_type") as? String ?: ""
        val expiresValue = body.opt("expires_in")
        val expiresSeconds = (expiresValue as? Number)?.let { value -> value.toLong().takeIf { value.toDouble() == it.toDouble() } } ?: -1L
        require(tokenType.equals("Bearer", ignoreCase = true) && access.isNotBlank() && refresh.isNotBlank() && idToken.isNotBlank()) {
            "ChatGPT did not return a renewable sign-in session."
        }
        require(scope.split(Regex("\\s+")).contains(REQUIRED_SCOPE)) { "ChatGPT plan use was not authorized for this account." }
        require(expiresSeconds in 1..31_536_000 && now + expiresSeconds * 1000 > now) { "ChatGPT returned an invalid token lifetime." }
        val identity = verifyIdToken(idToken, jwks, clientId, nonce, priorSubject, now)
        val earliest = parseEarliest(body.opt("earliest_refresh_at"), 0L)
        return Credentials(access, refresh, idToken, scope, now + expiresSeconds * 1000, earliest, identity.first, identity.second, clientId)
    }

    fun verifyIdToken(token: String, jwks: JSONObject, clientId: String, nonce: String?, priorSubject: String?, now: Long = System.currentTimeMillis()): Pair<String, String?> {
        val pieces = token.split('.')
        require(pieces.size == 3) { "ChatGPT identity token is malformed." }
        val header = JSONObject(String(decode(pieces[0]), Charsets.UTF_8))
        val claims = JSONObject(String(decode(pieces[1]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "ChatGPT identity token used an unsupported signature." }
        val keys = jwks.optJSONArray("keys") ?: throw IllegalArgumentException("OpenAI signing keys were unavailable.")
        val kid = header.optString("kid")
        val matches = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .filter { it.optString("kid") == kid && it.optString("kty") == "RSA" && (it.optString("alg").isEmpty() || it.optString("alg") == "RS256") && (it.optString("use").isEmpty() || it.optString("use") == "sig") }
        require(matches.size == 1) { "ChatGPT identity signing key was not recognized." }
        val jwk = matches.single()
        val modulus = java.math.BigInteger(1, decode(jwk.getString("n")))
        val exponent = java.math.BigInteger(1, decode(jwk.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode(pieces[2]))) { "ChatGPT identity signature did not verify." }

        require(claims.optString("iss") == ISSUER) { "ChatGPT identity issuer did not verify." }
        val audience = claims.opt("aud")
        val audiences = when (audience) {
            is String -> listOf(audience)
            is org.json.JSONArray -> (0 until audience.length()).mapNotNull { audience.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        require(clientId in audiences && (audiences.size <= 1 || claims.optString("azp") == clientId)) { "ChatGPT identity audience did not verify." }
        val exp = (claims.opt("exp") as? Number)?.toLong() ?: 0L
        require(claims.opt("exp") is Number && (claims.opt("exp") as Number).toDouble() == exp.toDouble()) { "ChatGPT identity expiration was malformed." }
        require(exp > now / 1000) { "ChatGPT identity token has expired." }
        val nbf = claims.opt("nbf")
        if (nbf != null) require(nbf is Number && nbf.toLong() <= now / 1000 + 30) { "ChatGPT identity token is not yet valid." }
        if (nonce != null) require(claims.optString("nonce") == nonce) { "ChatGPT sign-in nonce did not verify." }
        val subject = claims.optString("sub")
        require(subject.isNotBlank() && (priorSubject == null || subject == priorSubject)) { "ChatGPT account identity changed during sign-in." }
        return subject to claims.optString("email").takeIf(String::isNotBlank)
    }

    fun refreshForm(session: Credentials): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token", "client_id" to session.clientId,
        "refresh_token" to session.refreshToken, "resource" to RESOURCE,
    )

    fun parseRefresh(body: JSONObject, previous: Credentials, now: Long = System.currentTimeMillis()): Credentials {
        val access = body.opt("access_token") as? String ?: ""
        val refresh = body.opt("refresh_token") as? String ?: ""
        val expires = (body.opt("expires_in") as? Number)?.let { value -> value.toLong().takeIf { value.toDouble() == it.toDouble() } } ?: -1L
        val scopeValue = body.opt("scope")
        val scope = if (scopeValue == null) previous.scope else scopeValue as? String ?: ""
        val tokenType = body.opt("token_type") as? String ?: ""
        require(tokenType.equals("Bearer", true) && access.isNotBlank() && refresh.isNotBlank() && expires in 1..31_536_000) {
            "ChatGPT could not renew the session. Sign in again."
        }
        require(scope.split(Regex("\\s+")).contains(REQUIRED_SCOPE)) { "ChatGPT plan use is no longer authorized. Sign in again." }
        val earliest = parseEarliest(body.opt("earliest_refresh_at"), previous.earliestRefreshAt)
        // A refresh response's optional ID token is verified by the caller before it can replace this retained one.
        return previous.copy(accessToken = access, refreshToken = refresh, scope = scope, expiresAt = now + expires * 1000, earliestRefreshAt = earliest)
    }

    fun verifyRefreshedIdToken(token: String, jwks: JSONObject, previous: Credentials, now: Long = System.currentTimeMillis()): Credentials {
        val identity = verifyIdToken(token, jwks, previous.clientId, null, previous.subject, now)
        return previous.copy(idToken = token, email = identity.second ?: previous.email)
    }

    private fun parseEarliest(value: Any?, fallback: Long): Long = when (value) {
        null, JSONObject.NULL -> fallback
        is Number -> {
            val raw = value.toDouble()
            require(raw.isFinite() && raw >= 0 && raw <= Long.MAX_VALUE.toDouble()) { "ChatGPT returned an invalid session renewal time." }
            val millis = value.toLong().let { if (it in 1..9_999_999_999L) it * 1000 else it }
            require(millis >= 0) { "ChatGPT returned an invalid session renewal time." }
            millis
        }
        is String -> {
            val parsed = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrElse {
                throw IllegalArgumentException("ChatGPT returned an invalid session renewal time.")
            }
            require(parsed >= 0) { "ChatGPT returned an invalid session renewal time." }
            parsed
        }
        else -> throw IllegalArgumentException("ChatGPT returned an invalid session renewal time.")
    }

    fun randomToken(): String = b64(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    const val TEN_MINUTES = 10 * 60 * 1000L
}
