package tools.superscanner.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Inet4Address
import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic text and isolated loopback only. No device notes or native clipboard are touched. */
@RunWith(AndroidJUnit4::class)
class ClipboardBridgeInstrumentedTest {
    private val pairCodes = LinkedBlockingQueue<String>()
    private val listener = object : ClipboardBridgeListener {
        override fun onPairRequest(clientFingerprint: String, shortCode: String) {
            pairCodes.offer(shortCode)
        }
    }
    private val server = ClipboardBridgeServer(LoopbackBridgeNetwork, listener)
    private val client = ClipboardBridgeClient(LoopbackBridgeNetwork)

    @After fun tearDown() {
        client.close()
        server.stop()
    }

    @Test fun approvedTransferAndRevocation(): Unit = runBlocking {
        val info = server.start()
        assertEquals(info, ClipboardPairInfo.parse(info.encode()))
        assertEquals(PairStatus.PENDING, client.requestPair(info))
        assertEquals(client.identityFingerprint(), server.pendingFingerprint)
        val code = client.approvalCode(info)
        assertEquals(code, server.pendingApprovalCode)
        assertEquals(code, pairCodes.poll(3, TimeUnit.SECONDS))
        assertEquals(12, code.length)
        assertTrue(code.matches(Regex("[0-9A-F]{12}")))
        fun changed(fingerprint: String): String = (if (fingerprint[0] == '0') "1" else "0") + fingerprint.drop(1)
        assertFalse(code == info.copy(serverFingerprint = changed(info.serverFingerprint)).approvalCode(client.identityFingerprint()))
        assertFalse(code == info.approvalCode(changed(client.identityFingerprint())))
        assertEquals(PairStatus.PENDING, client.pairStatus(info))
        assertFalse(server.approve("0".repeat(64)))
        assertEquals(PairStatus.PENDING, client.pairStatus(info))
        assertThrows(BridgeException::class.java) { runBlocking { client.receive(info) } }
        assertThrows(BridgeException::class.java) { runBlocking { client.send(info, "blocked") } }
        assertTrue(server.approve(client.identityFingerprint()))
        assertNull(server.pendingApprovalCode)
        assertEquals(PairStatus.APPROVED, client.pairStatus(info))

        val stranger = ClipboardBridgeClient(LoopbackBridgeNetwork) { BridgeTls.testIdentity() }
        try {
            assertEquals(PairStatus.UNPAIRED, stranger.pairStatus(info))
            assertThrows(BridgeException::class.java) { runBlocking { stranger.receive(info) } }
            assertThrows(BridgeException::class.java) { runBlocking { stranger.send(info, "blocked") } }
        } finally { stranger.close() }

        val sample = "മലയാളം \uD83C\uDF3F café"
        server.publishSnapshot(sample)
        assertEquals(sample, client.receive(info))
        client.send(info, sample)
        assertEquals(sample, server.receivedText)
        assertEquals(sample, server.takeReceivedText())
        assertNull(server.receivedText)

        server.unpair()
        assertNull(server.pendingApprovalCode)
        assertEquals(PairStatus.UNPAIRED, client.pairStatus(info))
        assertThrows(BridgeException::class.java) { runBlocking { client.receive(info) } }
        assertThrows(BridgeException::class.java) { runBlocking { client.send(info, "blocked") } }
        assertNull(server.receivedText)
    }

    @Test fun rejectedCandidateCannotUseStatusAsApproval(): Unit = runBlocking {
        val info = server.start()
        assertEquals(PairStatus.PENDING, client.requestPair(info))
        server.reject()
        assertEquals(PairStatus.UNPAIRED, client.pairStatus(info))
        assertFalse(server.approve(client.identityFingerprint()))
        assertThrows(BridgeException::class.java) { runBlocking { client.send(info, "blocked") } }
    }

    @Test fun mismatchedServerPinFailsBeforeRequest(): Unit = runBlocking {
        val info = server.start()
        val other = info.copy(serverFingerprint = "0".repeat(64))
        assertThrows(Exception::class.java) { runBlocking { client.requestPair(other) } }
        assertNull(server.pendingFingerprint)
    }

    @Test fun utf8AndLengthBounds(): Unit = runBlocking {
        val info = server.start()
        assertEquals(PairStatus.PENDING, client.requestPair(info))
        assertTrue(server.approve(client.identityFingerprint()))
        val exact = "é".repeat(BridgeWire.MAX_TEXT / 2)
        server.publishSnapshot(exact)
        assertEquals(exact, client.receive(info))
        assertThrows(IllegalArgumentException::class.java) { server.publishSnapshot(exact + "é") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.send(info, "é".repeat(BridgeWire.MAX_TEXT / 2 + 1)) } }
        assertThrows(IllegalArgumentException::class.java) { server.publishSnapshot("\uD800") }
    }

    @Test fun stopClosesSessionAndClearsTrust(): Unit = runBlocking {
        val info = server.start()
        client.requestPair(info)
        assertTrue(server.approve(client.identityFingerprint()))
        server.publishSnapshot("synthetic")
        server.stop()
        assertNull(server.approvedFingerprint)
        assertNull(server.receivedText)
        val next = server.start()
        assertNotNull(next.serverFingerprint)
        assertEquals(PairStatus.UNPAIRED, client.pairStatus(next))
    }

    @Test fun cancelledQueuedRequestNeverReachesHelper(): Unit = runBlocking {
        val info = server.start()
        client.requestPair(info)
        assertTrue(server.approve(client.identityFingerprint()))
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val permitCalls = AtomicInteger()
        val blockedNetwork = object : BridgeNetwork {
            override fun bindingAddress(): Inet4Address = LoopbackBridgeNetwork.bindingAddress()
            override fun permits(host: String): Boolean {
                permitCalls.incrementAndGet()
                entered.countDown()
                gate.await(3, TimeUnit.SECONDS)
                return LoopbackBridgeNetwork.permits(host)
            }
            override fun socket(host: String): Socket = LoopbackBridgeNetwork.socket(host)
        }
        val queuedClient = ClipboardBridgeClient(blockedNetwork)
        try {
            val first = async(Dispatchers.IO) { queuedClient.pairStatus(info) }
            val second = async(Dispatchers.IO) { queuedClient.pairStatus(info) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val cancelled = async(Dispatchers.IO) { queuedClient.send(info, "must never arrive") }
            cancelled.cancelAndJoin()
            gate.countDown()
            assertEquals(PairStatus.APPROVED, first.await())
            assertEquals(PairStatus.APPROVED, second.await())
            assertEquals(2, permitCalls.get())
            assertNull(server.receivedText)
        } finally {
            gate.countDown()
            queuedClient.close()
        }
    }

    @Test fun malformedLengthUtf8AndJsonAreRejected() {
        fun frame(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply { writeInt(bytes.size); write(bytes) }
        }.toByteArray()
        assertThrows(BridgeException::class.java) {
            BridgeWire.read(DataInputStream(ByteArrayInputStream(frame(ByteArray(BridgeWire.MAX_FRAME + 1)))), true)
        }
        assertThrows(BridgeException::class.java) {
            BridgeWire.read(DataInputStream(ByteArrayInputStream(frame(byteArrayOf(0xc3.toByte(), 0x28)))), true)
        }
        assertThrows(BridgeException::class.java) {
            BridgeWire.read(DataInputStream(ByteArrayInputStream(frame("{\"v\":1,\"op\":\"get\",\"extra\":1}".toByteArray()))), true)
        }
        assertThrows(BridgeException::class.java) {
            BridgeWire.read(DataInputStream(ByteArrayInputStream(frame("{\"v\":1,\"op\":7}".toByteArray()))), true)
        }
        assertThrows(BridgeException::class.java) {
            BridgeWire.read(DataInputStream(ByteArrayInputStream(frame("{\"v\":1,\"op\":\"get\",\"op\":\"put\"}".toByteArray()))), true)
        }
        assertThrows(BridgeException::class.java) {
            BridgeWire.read(DataInputStream(ByteArrayInputStream(frame("{v:1,op:'get'}".toByteArray()))), true)
        }
    }

    @Test fun stopClosesInflightTlsSocket() {
        val info = server.start()
        val identity = BridgeTls.identity(server = false)
        val tls = BridgeTls.context(identity, BridgeTls.pinnedServerTrust(info.serverFingerprint))
            .socketFactory.createSocket(info.host, info.port) as javax.net.ssl.SSLSocket
        tls.soTimeout = 2000
        try {
            tls.startHandshake()
            server.stop()
            val ended = try { tls.inputStream.read() == -1 } catch (_: IOException) { true }
            assertTrue(ended)
        } finally { tls.close() }
    }
}
