package tools.superscanner.nativelink

import android.content.Context
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLinkSocketInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var client: NativeLinkClient

    @Before fun resetFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Synthetic loopback host fixture is opt-in", arguments.getString("nativeLinkHostFixture") == "true")
        assumeTrue("Synthetic host alias is supported only on an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        client = NativeLinkClient(context)
        control("RESET approve")
    }

    @Test fun approvedResponseOpensReceiveOnlySessionWithoutHeartbeat() = runBlocking {
        val attempt = client.preparePair(fixtureDevice())
        val result = attempt.awaitApproval(5_000)
        assertTrue("Expected synthetic approval; fixture=${state()}", result is NativePairResult.Approved)
        val session = (result as NativePairResult.Approved).session
        try {
            val state = waitState { it.getInt("pairRequests") == 1 }
            assertEquals(0, state.getInt("pairCancels"))
            assertEquals(0, state.getJSONArray("udpFrames").length())
        } finally {
            session.close()
        }
        assertTrue(session.isClosed.value)
    }

    @Test fun selectedProductionWifiRouteBindsToTheSyntheticHostFixture() {
        val route = NativeWifi(context).select()
        assertNotNull("No eligible production Wi-Fi route is available on this emulator", route)
        route!!
        val fixtureAddress = numericPrivateIpv4(FIXTURE_HOST)
        assertNotNull(fixtureAddress)
        assertTrue("Selected Wi-Fi route ${route.localAddress.hostAddress}/${route.prefixLength} does not contain the synthetic host",
            route.contains(fixtureAddress!!))
        assertTrue(control("STATE", route.network).has("pairRequests"))
    }

    @Test fun directSocketResponseDiagnosticLogsOnlySanitizedFraming() {
        control("RESET reject")
        val route = NativeWifi(context).select()
        assertNotNull("No eligible production Wi-Fi route is available", route)
        route!!
        val fixtureAddress = numericPrivateIpv4(FIXTURE_HOST)!!
        assertTrue(route.contains(fixtureAddress))
        val started = SystemClock.elapsedRealtime()
        val bytes = ByteArrayOutputStream()
        Socket().use { socket ->
            route.network.bindSocket(socket)
            socket.connect(InetSocketAddress(FIXTURE_HOST, PAIR_PORT), 2_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write(NativeFrames.pairRequest("SuperScanner-AB12CD34", "123456"))
            socket.getOutputStream().flush()
            val input = socket.getInputStream()
            while (bytes.size() < 4096) {
                val next = input.read()
                if (next < 0) break
                bytes.write(next)
                if (next == '}'.code) break
            }
        }
        val responseBytes = bytes.toByteArray()
        val sanitized = String(responseBytes, StandardCharsets.ISO_8859_1)
        val statusLine = sanitized.substringBefore('\n').trimEnd('\r')
        val body = sanitized.substringAfter("\n\n", "").trim()
        val bodyCode = body.takeIf { it.startsWith("{code:") && it.endsWith("}") }
            ?.substringAfter("{code:")?.substringBefore(',')?.toIntOrNull()
        val elapsed = SystemClock.elapsedRealtime() - started
        println("NativeLink synthetic pair framing: responseBytes=${responseBytes.size}; status=$statusLine; bodyCode=$bodyCode; elapsedMs=$elapsed")
        assertEquals("bytes=${responseBytes.size}; status=$statusLine; bodyCode=$bodyCode; elapsedMs=$elapsed",
            201, bodyCode)
    }

    @Test fun bodyRefusalWinsOverHttp200AndDoesNotCreateSession() = runBlocking {
        control("RESET reject")
        val result = client.preparePair(fixtureDevice()).awaitApproval(5_000)
        assertEquals("Synthetic HTTP 200/body 201 should reject; fixture=${state()}", NativePairResult.Rejected, result)
        val state = waitState { it.getInt("pairRequests") == 1 }
        assertEquals(0, state.getInt("pairCancels"))
        assertEquals(1, state.getJSONArray("pairStatuses").length())
    }

    @Test fun timeoutSendsOnlyScopedStatusTwoForTheTemporaryPairName() = runBlocking {
        control("RESET hold")
        val result = client.preparePair(fixtureDevice()).awaitApproval(1_000)
        assertEquals(NativePairResult.TimedOut, result)
        val state = waitState { it.getInt("pairCancels") == 1 }
        assertEquals(1, state.getInt("pairRequests"))
        assertEquals(true, state.getBoolean("sameTemporaryName"))
        assertEquals(listOf(1, 2), state.getJSONArray("pairStatuses").toIntList())
    }

    @Test fun explicitKeyboardAndClipboardActionsUseBoundedProductionSockets() = runBlocking {
        val result = client.preparePair(fixtureDevice()).awaitApproval(5_000)
        assertTrue(result is NativePairResult.Approved)
        val session = (result as NativePairResult.Approved).session
        try {
            assertEquals(0, state().getJSONArray("udpFrames").length())
            session.openKeyboard()
            withTimeout(3_000) { session.keyboardVisibility.first { it == NativeKeyboardVisibility.VISIBLE } }
            session.keyDown(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON)
            session.keyUp(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON)
            session.sendClipboardText("native synthetic clipboard ✓")

            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                session.receiveClipboardText(timeoutMillis = 5_000)
            }
            control("NOTIFY")
            assertEquals("synthetic incoming clipboard 🧪", waiting.await())

            session.keyDown(KeyEvent.KEYCODE_B)
        } finally {
            session.close()
        }

        val finalState = waitState {
            it.getInt("fetchCount") == 1 && it.getBoolean("sendMatchesFixture") &&
                it.getJSONArray("udpFrames").length() >= 6
        }
        assertEquals(true, finalState.getBoolean("sendMatchesFixture"))
        assertEquals("native synthetic clipboard ✓".toByteArray(StandardCharsets.UTF_8).size,
            finalState.getInt("sendBytes"))
        assertEquals(1, finalState.getInt("notifyCount"))
        assertEquals(listOf(
            "OPEN|49152|0",
            "KEY_DOWN_EVENT|16413|1",
            "KEY_UP_EVENT|32797|1",
            "KEY_DOWN_EVENT|16414|0",
            "KEY_UP_EVENT|32798|0",
            "CLOSE|49152|0",
        ), finalState.getJSONArray("udpFrames").toStringList())
    }

    @Test fun oneShotVisibleStatusRemainsUsableUntilExplicitHide() = runBlocking {
        val result = client.preparePair(fixtureDevice()).awaitApproval(5_000)
        assertTrue(result is NativePairResult.Approved)
        val session = (result as NativePairResult.Approved).session
        try {
            session.openKeyboard()
            withTimeout(3_000) { session.keyboardVisibility.first { it == NativeKeyboardVisibility.VISIBLE } }

            // Native Linking sends visibility on OPEN and on state changes, not as a heartbeat.
            delay(3_500)
            assertEquals(NativeKeyboardVisibility.VISIBLE, session.keyboardVisibility.value)
            session.keyDown(KeyEvent.KEYCODE_A)
            session.keyUp(KeyEvent.KEYCODE_A)

            assertTrue(control("HIDE").getBoolean("ok"))
            waitState { it.getInt("hideCount") == 1 }
            withTimeout(3_000) { session.keyboardVisibility.first { it == NativeKeyboardVisibility.HIDDEN } }

            var hiddenKeyRejected = false
            try {
                session.keyDown(KeyEvent.KEYCODE_B)
            } catch (_: IllegalStateException) {
                hiddenKeyRejected = true
            }
            assertTrue("Key input must stop after an explicit hidden status", hiddenKeyRejected)
            val frames = state().getJSONArray("udpFrames").toStringList()
            assertEquals(listOf(
                "OPEN|49152|0",
                "KEY_DOWN_EVENT|16413|0",
                "KEY_UP_EVENT|32797|0",
            ), frames)
        } finally {
            session.close()
        }
    }

    @Test fun canceledReceiveIgnoresLaterClipboardNotification() = runBlocking {
        val result = client.preparePair(fixtureDevice()).awaitApproval(5_000)
        assertTrue(result is NativePairResult.Approved)
        val session = (result as NativePairResult.Approved).session
        try {
            session.openKeyboard()
            withTimeout(3_000) { session.keyboardVisibility.first { it == NativeKeyboardVisibility.VISIBLE } }
            val receiving = async(start = CoroutineStart.UNDISPATCHED) {
                session.receiveClipboardText(timeoutMillis = 10_000)
            }
            receiving.cancel()
            try { receiving.await() } catch (_: CancellationException) { }
            control("NOTIFY")
            delay(250)
            assertEquals(0, state().getInt("fetchCount"))
        } finally {
            session.close()
        }
    }

    @Test fun clipboardOnlyReceiveRegistersPeerWithoutOpeningKeyboard() = runBlocking {
        val result = client.preparePair(fixtureDevice()).awaitApproval(5_000)
        assertTrue(result is NativePairResult.Approved)
        val session = (result as NativePairResult.Approved).session
        try {
            session.prepareClipboardReceive()
            waitState { it.getJSONArray("udpFrames").toStringList() == listOf("CLOSE|49152|0") }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                session.receiveClipboardText(timeoutMillis = 5_000)
            }
            control("NOTIFY")
            assertEquals("synthetic incoming clipboard 🧪", waiting.await())
        } finally {
            session.close()
        }
        val frames = waitState { it.getInt("fetchCount") == 1 }.getJSONArray("udpFrames").toStringList()
        assertEquals(listOf("CLOSE|49152|0"), frames)
    }

    @Test fun leavingKeyboardReleasesKeysThenKeepsClipboardReceiveAvailable() = runBlocking {
        val result = client.preparePair(fixtureDevice()).awaitApproval(5_000)
        assertTrue(result is NativePairResult.Approved)
        val session = (result as NativePairResult.Approved).session
        try {
            session.openKeyboard()
            withTimeout(3_000) { session.keyboardVisibility.first { it == NativeKeyboardVisibility.VISIBLE } }
            session.keyDown(KeyEvent.KEYCODE_A)
            session.closeKeyboard()
            session.prepareClipboardReceive()
            waitState { it.getJSONArray("udpFrames").length() >= 5 }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                session.receiveClipboardText(timeoutMillis = 5_000)
            }
            control("NOTIFY")
            assertEquals("synthetic incoming clipboard 🧪", waiting.await())
        } finally {
            session.close()
        }
        val frames = waitState { it.getInt("fetchCount") == 1 }.getJSONArray("udpFrames").toStringList()
        assertEquals(listOf(
            "OPEN|49152|0",
            "KEY_DOWN_EVENT|16413|0",
            "KEY_UP_EVENT|32797|0",
            "CLOSE|49152|0",
            "CLOSE|49152|0",
        ), frames)
    }

    private fun fixtureDevice() = NativeDevice("Synthetic fixture", "10.0.2.2", "fixture-id")

    private fun control(command: String, network: Network? = null): JSONObject = Socket().use { socket ->
        network?.bindSocket(socket)
        socket.connect(InetSocketAddress(FIXTURE_HOST, CONTROL_PORT), 2_000)
        socket.soTimeout = 2_000
        socket.getOutputStream().write((command + "\n").toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().flush()
        val response = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
            ?: error("Fixture control closed without a response")
        JSONObject(response)
    }

    private fun state() = control("STATE")

    private suspend fun waitState(predicate: (JSONObject) -> Boolean): JSONObject = withTimeout(5_000) {
        while (true) {
            val current = state()
            if (predicate(current)) return@withTimeout current
            delay(25)
        }
        error("Unreachable")
    }

    private fun org.json.JSONArray.toIntList(): List<Int> = (0 until length()).map { getInt(it) }
    private fun org.json.JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        const val FIXTURE_HOST = "10.0.2.2"
        const val PAIR_PORT = 60002
        const val CONTROL_PORT = 60003
    }
}
