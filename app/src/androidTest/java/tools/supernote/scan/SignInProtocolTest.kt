package tools.supernote.scan

import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

@RunWith(AndroidJUnit4::class)
class SignInProtocolTest {
    @Test fun initialAuthorizationUsesDynamicRegistrationStableHostAndExactLoopbackUri() {
        val (pending, uri) = SignInProtocol.createPending(null, null, SignInProtocol.newHostId(), 41235)

        assertEquals("dynamic_agent_client", uri.getQueryParameter("client_id"))
        assertEquals("SuperScanner", uri.getQueryParameter("agent_name_hint"))
        assertEquals("http://127.0.0.1:41235/auth/callback", uri.getQueryParameter("redirect_uri"))
        assertEquals("https://api.openai.com/v1", uri.getQueryParameter("resource"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(pending.state, uri.getQueryParameter("state"))
        assertEquals(pending.nonce, uri.getQueryParameter("nonce"))
        assertEquals(43, uri.getQueryParameter("code_challenge")?.length)
        assertFalse(uri.toString().contains(pending.verifier))
        assertFalse(uri.toString().contains("client_secret"))
    }

    @Test fun returningAuthorizationReusesClientAndOmitsAgentName() {
        val client = "oaiapp_registered123"
        val (_, uri) = SignInProtocol.createPending(client, "subject-1", "urn:uuid:00000000-0000-4000-8000-000000000001", 41888)
        assertEquals(client, uri.getQueryParameter("client_id"))
        assertEquals("urn:uuid:00000000-0000-4000-8000-000000000001", uri.getQueryParameter("ext_agent_host_id"))
        assertEquals(null, uri.getQueryParameter("agent_name_hint"))
    }

    @Test fun callbackMustMatchStateRegistrationAndFreshness() {
        val (pending, _) = SignInProtocol.createPending(null, null, SignInProtocol.newHostId(), 42000, 1_000L)
        val correct = mapOf("code" to "one-use-code", "client_id" to "oaiapp_new123", "state" to pending.state)
        val form = SignInProtocol.exchangeForm(pending, correct, 2_000L)
        assertEquals("oaiapp_new123", form["client_id"])
        assertTrue(form["code_verifier"] != null)

        assertFails { SignInProtocol.exchangeForm(pending, correct + ("state" to "wrong"), 2_000L) }
        val registered = pending.copy(clientId = "oaiapp_saved123")
        assertFails { SignInProtocol.exchangeForm(registered, correct, 2_000L) }
        assertFails { SignInProtocol.exchangeForm(pending, correct, 1_000L + SignInProtocol.TEN_MINUTES + 1) }
        assertFails { SignInProtocol.exchangeForm(pending, correct + ("error" to "access_denied"), 2_000L) }
    }

    @Test fun idTokenChecksSignatureIssuerAudienceExpirationNonceAndAccount() {
        val pair = keyPair()
        val client = "oaiapp_registered123"
        val nonce = "expected-nonce"
        val claims = JSONObject()
            .put("iss", SignInProtocol.ISSUER).put("aud", client)
            .put("exp", 2_000_000_000L).put("nonce", nonce)
            .put("sub", "account-subject").put("email", "user@example.test")
        val jwks = jwks(pair)
        val good = signedToken(pair, claims)
        val identity = SignInProtocol.verifyIdToken(good, jwks, client, nonce, null, 1_900_000_000_000L)
        assertEquals("account-subject", identity.first)
        assertEquals("user@example.test", identity.second)

        assertFails { SignInProtocol.verifyIdToken(good, jwks, client, "wrong-nonce", null, 1_900_000_000_000L) }
        assertFails { SignInProtocol.verifyIdToken(good, jwks, "oaiapp_other", nonce, null, 1_900_000_000_000L) }
        assertFails { SignInProtocol.verifyIdToken(good, jwks, client, nonce, "different-account", 1_900_000_000_000L) }
        val wrongIssuer = signedToken(pair, JSONObject(claims.toString()).put("iss", "https://example.invalid"))
        assertFails { SignInProtocol.verifyIdToken(wrongIssuer, jwks, client, nonce, null, 1_900_000_000_000L) }
        val expired = signedToken(pair, JSONObject(claims.toString()).put("exp", 1_800_000_000L))
        assertFails { SignInProtocol.verifyIdToken(expired, jwks, client, nonce, null, 1_900_000_000_000L) }
        val parts = good.split('.')
        val changedPayload = (if (parts[1].first() == 'A') "B" else "A") + parts[1].drop(1)
        val tampered = "${parts[0]}.$changedPayload.${parts[2]}"
        assertFails { SignInProtocol.verifyIdToken(tampered, jwks, client, nonce, null, 1_900_000_000_000L) }
    }

    @Test fun tokenResponseRequiresRenewableGrantedPlanScopeAndValidEarliestRefresh() {
        val pair = keyPair()
        val client = "oaiapp_registered123"
        val idToken = signedToken(pair, JSONObject()
            .put("iss", SignInProtocol.ISSUER).put("aud", client).put("exp", 2_000_000_000L)
            .put("nonce", "n").put("sub", "subject"))
        val base = JSONObject().put("token_type", "Bearer").put("access_token", "access")
            .put("refresh_token", "refresh").put("id_token", idToken)
            .put("scope", "openid resource.invoke chatgpt.tokens.use.direct")
            .put("expires_in", 3600).put("earliest_refresh_at", 1_900_000_000L)
        val result = SignInProtocol.parseCredentials(base, client, jwks(pair), "n", null, 1_900_000_000_000L)
        assertEquals("access", result.accessToken)
        assertEquals("refresh", result.refreshToken)
        assertEquals(1_900_000_000_000L, result.earliestRefreshAt)

        assertFails { SignInProtocol.parseCredentials(JSONObject(base.toString()).put("scope", "openid resource.invoke"), client, jwks(pair), "n", null, 1_900_000_000_000L) }
        assertFails { SignInProtocol.parseCredentials(JSONObject(base.toString()).put("access_token", JSONObject.NULL), client, jwks(pair), "n", null, 1_900_000_000_000L) }
        assertFails { SignInProtocol.parseCredentials(JSONObject(base.toString()).put("refresh_token", JSONObject.NULL), client, jwks(pair), "n", null, 1_900_000_000_000L) }
        assertFails { SignInProtocol.parseCredentials(JSONObject(base.toString()).put("earliest_refresh_at", "not-a-time"), client, jwks(pair), "n", null, 1_900_000_000_000L) }
    }

    @Test fun refreshRotatesTokensAndPreservesPreviousIdentityOnMissingIdToken() {
        val previous = SignInProtocol.Credentials("old-access", "old-refresh", "old-id", "chatgpt.tokens.use.direct",
            1_900_000_000_000L, 0, "subject", null, "oaiapp_registered123")
        val form = SignInProtocol.refreshForm(previous)
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("old-refresh", form["refresh_token"])
        assertEquals(SignInProtocol.RESOURCE, form["resource"])
        val refreshed = SignInProtocol.parseRefresh(JSONObject()
            .put("token_type", "Bearer").put("access_token", "new-access")
            .put("refresh_token", "new-refresh").put("expires_in", 3600), previous, 1_900_000_000_000L)
        assertEquals("new-access", refreshed.accessToken)
        assertEquals("new-refresh", refreshed.refreshToken)
        assertEquals("old-id", refreshed.idToken)
        assertEquals(previous.subject, refreshed.subject)
        val base = JSONObject().put("token_type", "Bearer").put("expires_in", 3600)
            .put("access_token", "new-access").put("refresh_token", "new-refresh")
        assertFails { SignInProtocol.parseRefresh(JSONObject(base.toString()).put("access_token", JSONObject.NULL), previous, 1_900_000_000_000L) }
        assertFails { SignInProtocol.parseRefresh(JSONObject(base.toString()).put("refresh_token", JSONObject.NULL), previous, 1_900_000_000_000L) }
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun jwks(pair: KeyPair): JSONObject {
        val publicKey = pair.public as RSAPublicKey
        return JSONObject().put("keys", org.json.JSONArray().put(JSONObject()
            .put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", "test-key")
            .put("n", base64Url(unsigned(publicKey.modulus))).put("e", base64Url(unsigned(publicKey.publicExponent)))))
    }

    private fun signedToken(pair: KeyPair, claims: JSONObject): String {
        val header = JSONObject().put("alg", "RS256").put("kid", "test-key")
        val input = "${base64Url(header.toString().toByteArray())}.${base64Url(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update(input.toByteArray(Charsets.US_ASCII)); sign()
        }
        return "$input.${base64Url(signature)}"
    }

    private fun unsigned(value: java.math.BigInteger): ByteArray = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    private fun base64Url(value: ByteArray) = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected validation to fail", failed)
    }
}
