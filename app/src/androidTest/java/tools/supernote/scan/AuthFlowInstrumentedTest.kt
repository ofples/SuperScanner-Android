package tools.supernote.scan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicReference

/** End-to-end loopback sign-in fixtures. No request leaves the app process. */
@RunWith(AndroidJUnit4::class)
class AuthFlowInstrumentedTest {
    @Test fun loopbackCallbackRejectsWrongStateAndActivatesOnlyVerifiedIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearOwnTestSession(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val fixture = AuthFixture()
            val client = RecognitionClient(context, fixture)

            fixture.signWrongIdToken = true
            val invalid = runCatching { finishBrowserSignIn(client, fixture) }.exceptionOrNull()
            assertTrue("Invalid identity signature must reject sign-in", invalid != null)
            assertFalse("No session may activate before identity validation", client.isChatGptConnected())

            fixture.signWrongIdToken = false
            val callbackStatuses = finishBrowserSignIn(client, fixture)
            assertEquals(listOf(400, 200), callbackStatuses)
            assertTrue("Validated identity should activate the ChatGPT session", client.isChatGptConnected())
            assertEquals("ChatGPT plan connected", client.subscriptionStatus())
            assertListenerClosed(fixture.lastRedirect)

            // A failed reauthorization for the same account must preserve the already active session.
            fixture.signWrongIdToken = true
            val failedReauth = runCatching { finishBrowserSignIn(client, fixture) }.exceptionOrNull()
            assertTrue(failedReauth != null)
            assertTrue(client.isChatGptConnected())
            assertEquals("gpt-6.1-sol", client.listModels("chatgpt").single().id)
            assertEquals("Bearer access-2", fixture.lastModelAuthorization)
        } finally {
            scenario.close()
            clearOwnTestSession(context)
        }
    }

    @Test fun cancellingPendingCallbackClosesListenerAndPreservesSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearOwnTestSession(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val fixture = AuthFixture()
            val client = RecognitionClient(context, fixture)
            finishBrowserSignIn(client, fixture)
            assertTrue(client.isChatGptConnected())

            val launchedUri = CompletableDeferred<Uri>()
            val operation = async { client.beginChatGptSignIn { launchedUri.complete(it) } }
            val authUri = withTimeout(5_000) { launchedUri.await() }
            val redirect = Uri.parse(authUri.getQueryParameter("redirect_uri"))
            val waitingSocket = withContext(Dispatchers.IO) { Socket("127.0.0.1", redirect.port) }
            // Let the accepted socket block at the bounded request-line read before cancellation.
            Thread.sleep(120)

            client.cancelSignIn()
            runCatching { operation.await() }
            val closed = runCatching { withContext(Dispatchers.IO) { waitingSocket.getInputStream().read() } }.getOrDefault(-1)
            waitingSocket.close()
            assertTrue("Cancel must close the accepted loopback socket", closed == -1)
            assertTrue("Cancel must preserve the prior verified account", client.isChatGptConnected())
            assertEquals("gpt-6.1-sol", client.listModels("chatgpt").single().id)
            assertEquals("Bearer access-1", fixture.lastModelAuthorization)
            assertListenerClosed(authUri.getQueryParameter("redirect_uri"))
        } finally {
            scenario.close()
            clearOwnTestSession(context)
        }
    }

    @Test fun loopbackWaitsForDelayedBrowserReturnAfterActivityStops() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearOwnTestSession(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val fixture = AuthFixture()
            val client = RecognitionClient(context, fixture)
            val launchedUri = CompletableDeferred<Uri>()
            val operation = async { client.beginChatGptSignIn { launchedUri.complete(it) } }
            val authUri = withTimeout(5_000) { launchedUri.await() }
            fixture.currentNonce = authUri.getQueryParameter("nonce") ?: error("Synthetic nonce missing")
            val redirectValue = authUri.getQueryParameter("redirect_uri") ?: error("Synthetic loopback redirect missing")
            fixture.lastRedirect = redirectValue

            // Simulate the app moving behind a browser tab while the user signs in. The callback
            // listener is owned by the sign-in operation, not a foreground service or activity.
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(300)
            assertListenerAccepting(redirectValue)
            val status = sendCallback(Uri.parse(redirectValue), mapOf(
                "state" to (authUri.getQueryParameter("state") ?: error("Synthetic state missing")),
                "code" to "synthetic-delayed-browser-return",
                "client_id" to fixture.clientId,
            ))
            assertEquals(200, status)
            withTimeout(15_000) { operation.await() }
            assertTrue("The delayed synthetic callback should activate the verified fixture session",
                client.isChatGptConnected())
            assertListenerClosed(redirectValue)
        } finally {
            scenario.close()
            clearOwnTestSession(context)
        }
    }

    @Test fun uncertainRefreshIsPersistedAndOldRotatingTokenIsNeverReplayed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearOwnTestSession(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val fixture = AuthFixture().apply { expiresInSeconds = 1 }
            val client = RecognitionClient(context, fixture)
            finishBrowserSignIn(client, fixture)
            assertTrue(client.isChatGptConnected())

            val failedRefresh = runCatching { client.listModels("chatgpt") }.exceptionOrNull()
            assertTrue("An interrupted rotating refresh must fail safely", failedRefresh != null)
            assertEquals(1, fixture.refreshCount)
            assertEquals("ChatGPT session needs sign-in again", client.subscriptionStatus())

            val reloaded = RecognitionClient(context, fixture)
            assertFalse("The encrypted uncertainty marker must survive app reconstruction", reloaded.isChatGptConnected())
            val blocked = runCatching { reloaded.listModels("chatgpt") }.exceptionOrNull()
            assertTrue(blocked != null)
            assertEquals("The old refresh token must never be submitted twice", 1, fixture.refreshCount)
        } finally {
            scenario.close()
            clearOwnTestSession(context)
        }
    }

    @Test fun clientsReloadRotatedSessionAndObserveAnotherClientsDisconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearOwnTestSession(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val fixture = AuthFixture().apply { expiresInSeconds = 1; failRefresh = false }
            val first = RecognitionClient(context, fixture)
            finishBrowserSignIn(first, fixture)
            val second = RecognitionClient(context, fixture)

            Thread.sleep(1_300)
            assertEquals("gpt-6.1-sol", first.listModels("chatgpt").single().id)
            assertEquals(1, fixture.refreshCount)
            assertEquals("Bearer rotated-access-1", fixture.lastModelAuthorization)

            // This instance was created before rotation; it must reload the committed pair rather
            // than submitting the now-invalid prior refresh token.
            assertEquals("gpt-6.1-sol", second.listModels("chatgpt").single().id)
            assertEquals(1, fixture.refreshCount)
            assertEquals("Bearer rotated-access-1", fixture.lastModelAuthorization)

            first.disconnectChatGpt()
            assertFalse("A second client must observe local disconnect", second.isChatGptConnected())
            assertTrue("A second client must not continue using a disconnected session",
                runCatching { second.listModels("chatgpt") }.isFailure)
        } finally {
            scenario.close()
            clearOwnTestSession(context)
        }
    }

    private suspend fun finishBrowserSignIn(client: RecognitionClient, fixture: AuthFixture): List<Int> = coroutineScope {
        val statuses = AtomicReference<List<Int>>(emptyList())
        val operation = async { client.beginChatGptSignIn { uri ->
            fixture.currentNonce = uri.getQueryParameter("nonce") ?: error("Authorization nonce missing")
            fixture.lastRedirect = uri.getQueryParameter("redirect_uri")
            Thread {
                val redirect = Uri.parse(fixture.lastRedirect)
                val invalidState = sendCallback(redirect, mapOf("state" to "wrong-state", "code" to "ignored"))
                val valid = sendCallback(redirect, mapOf(
                    "state" to (uri.getQueryParameter("state") ?: ""),
                    "code" to "synthetic-auth-code-${fixture.authorizationCount + 1}",
                    "client_id" to fixture.clientId,
                ))
                statuses.set(listOf(invalidState, valid))
            }.apply { name = "synthetic-browser-return"; isDaemon = true; start() }
        } }
        withTimeout(15_000) { operation.await() }
        statuses.get()
    }

    private fun sendCallback(redirect: Uri, parameters: Map<String, String>): Int {
        val query = parameters.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        Socket("127.0.0.1", redirect.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET ${redirect.encodedPath}?$query HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine() ?: return 0
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        }
    }

    private fun assertListenerClosed(redirectValue: String?) {
        val redirect = Uri.parse(redirectValue)
        val connected = runCatching { Socket("127.0.0.1", redirect.port).use { true } }.getOrDefault(false)
        assertFalse("The temporary loopback listener must close after sign-in", connected)
    }

    private fun assertListenerAccepting(redirectValue: String) {
        val redirect = Uri.parse(redirectValue)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", redirect.port), 1_000)
        }
    }

    /** Only the instrumentation app's own encrypted credential entries are cleared. */
    private fun clearOwnTestSession(context: Context) {
        val secure = SecureStore(context)
        secure.write("chatgpt_session", null)
        secure.write("chatgpt_host_id", null)
        secure.write("api_key", null)
    }

    private class AuthFixture : RecognitionTransport {
        private val signingKeys = rsaPair()
        private val wrongKeys = rsaPair()
        val clientId = "oaiapp_authflowtest123"
        @Volatile var currentNonce = ""
        @Volatile var lastRedirect: String? = null
        @Volatile var signWrongIdToken = false
        @Volatile var expiresInSeconds = 3600
        @Volatile var authorizationCount = 0
        @Volatile var refreshCount = 0
        @Volatile var lastModelAuthorization = ""
        @Volatile var failRefresh = true

        override suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): WireReply {
            return when {
                url.endsWith("/api/accounts/oauth/token") -> {
                    val form = parseForm(body.orEmpty())
                    when (form["grant_type"]) {
                        "authorization_code" -> {
                            authorizationCount += 1
                            val tokenPair = if (signWrongIdToken) wrongKeys else signingKeys
                            val accessToken = "access-$authorizationCount"
                            val claims = JSONObject().put("iss", SignInProtocol.ISSUER)
                                .put("aud", form["client_id"] ?: clientId).put("exp", System.currentTimeMillis() / 1000 + 7200)
                                .put("nonce", currentNonce).put("sub", "synthetic-account")
                                .put("email", "synthetic@example.test")
                            val response = JSONObject().put("token_type", "Bearer")
                                .put("access_token", accessToken).put("refresh_token", "refresh-$authorizationCount")
                                .put("id_token", signedIdToken(tokenPair, claims)).put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                                .put("expires_in", expiresInSeconds)
                            WireReply(200, response.toString())
                        }
                        "refresh_token" -> {
                            refreshCount += 1
                            if (failRefresh) throw IOException("synthetic disconnect after rotation submission")
                            WireReply(200, JSONObject().put("token_type", "Bearer")
                                .put("access_token", "rotated-access-$refreshCount")
                                .put("refresh_token", "rotated-refresh-$refreshCount")
                                .put("expires_in", 3600)
                                .put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct").toString())
                        }
                        else -> WireReply(400, "{}")
                    }
                }
                url.endsWith("/.well-known/openid-configuration") -> WireReply(200, JSONObject()
                    .put("issuer", SignInProtocol.ISSUER)
                    .put("jwks_uri", "${SignInProtocol.ISSUER}/jwks")
                    .put("revocation_endpoint", "${SignInProtocol.ISSUER}/revoke").toString())
                url.endsWith("/jwks") -> WireReply(200, jwks(signingKeys).toString())
                url.endsWith("/revoke") -> WireReply(200, "{}")
                url == "https://api.openai.com/v1/models" -> {
                    lastModelAuthorization = headers["Authorization"].orEmpty()
                    WireReply(200, JSONObject().put("models", JSONArray().put(JSONObject()
                        .put("slug", "gpt-6.1-sol").put("display_name", "GPT-6.1 Sol").put("visibility", "list"))).toString())
                }
                else -> throw AssertionError("Unexpected fixture request URL: $url")
            }
        }

        override suspend fun stream(url: String, headers: Map<String, String>, body: String, onEvent: (JSONObject) -> Unit) {
            throw AssertionError("Auth regression test unexpectedly attempted recognition.")
        }

        private fun parseForm(body: String): Map<String, String> = body.split('&').filter(String::isNotEmpty).associate { item ->
            val parts = item.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

        private fun rsaPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private fun jwks(pair: KeyPair): JSONObject {
            val key = pair.public as RSAPublicKey
            return JSONObject().put("keys", JSONArray().put(JSONObject()
                .put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", "fixture-key")
                .put("n", b64(unsigned(key.modulus))).put("e", b64(unsigned(key.publicExponent)))))
        }

        private fun signedIdToken(pair: KeyPair, claims: JSONObject): String {
            val header = JSONObject().put("alg", "RS256").put("kid", "fixture-key")
            val input = "${b64(header.toString().toByteArray())}.${b64(claims.toString().toByteArray())}"
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private); update(input.toByteArray(Charsets.US_ASCII)); sign()
            }
            return "$input.${b64(signature)}"
        }

        private fun unsigned(value: java.math.BigInteger): ByteArray = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        private fun b64(value: ByteArray): String = android.util.Base64.encodeToString(value,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
