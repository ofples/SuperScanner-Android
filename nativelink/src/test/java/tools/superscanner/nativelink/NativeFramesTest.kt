package tools.superscanner.nativelink

import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class NativeFramesTest {
    private fun input(value: String) = ByteArrayInputStream(value.toByteArray(StandardCharsets.UTF_8))

    @Test fun pairDecisionRequiresCompleteNativeBodyAndSuccessfulStatus() {
        assertEquals(200, NativeFrames.pairResponse(input("HTTP/1.0 200 OK\n\n{code:200,msg:\"accepted\"}\n")))
        assertEquals(201, NativeFrames.pairResponse(input("HTTP/1.0 200 OK\r\n\r\n{code:201,msg:\"refused\"}")))
        assertNull(NativeFrames.pairResponse(input("HTTP/1.0 500 Error\n\n{code:200,msg:\"accepted\"}")))
        assertNull(NativeFrames.pairResponse(input("HTTP/1.0 200 OK\n\n{code:200,msg:\"incomplete\"")))
        assertNull(NativeFrames.pairResponse(input("HTTP/1.0 200 OK\n\n" + "a".repeat(4096))))
    }

    @Test fun pairRequestsUseUniqueScopedNameAndNoPersistedHeaders() {
        val request = NativeFrames.pairRequest("SuperScanner-1A2B3C4D", "012345").toString(StandardCharsets.US_ASCII)
        assertEquals("GET / HTTP/1.0\r\nDeviceName: SuperScanner-1A2B3C4D\r\nStatus: 1\r\nPairCode: 012345\r\n\r\n", request)
        assertFalse(request.contains("pairing-token"))
        assertTrue(NativeFrames.pairCancel("SuperScanner-1A2B3C4D").toString(StandardCharsets.US_ASCII).contains("Status: 2\r\n"))
    }

    @Test fun discoveryUsesTrailingSeparatorsAndRejectsControlCharacters() {
        val address = InetAddress.getByName("192.168.1.7") as Inet4Address
        val valid = "My, Nomad,serial123,2".toByteArray(StandardCharsets.UTF_8)
        assertEquals("My, Nomad", NativeFrames.discoveryReply(valid, valid.size, address)?.name)
        val invalid = "Nomad\n,serial123,2".toByteArray(StandardCharsets.UTF_8)
        assertNull(NativeFrames.discoveryReply(invalid, invalid.size, address))
        val publicAddress = InetAddress.getByName("8.8.8.8") as Inet4Address
        assertNull(NativeFrames.discoveryReply(valid, valid.size, publicAddress))
    }

    @Test fun clipboardFramingHasStrictUtf8AndExactLengths() {
        val value = "നമസ്കാരം 👋"
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val sent = NativeFrames.tcpClipboardFrame(value)
        assertArrayEquals("TCP_STRING|${bytes.size}|".toByteArray(StandardCharsets.US_ASCII) + bytes, sent)
        val fetched = "CLIP_TCP_CONTENT|${bytes.size}|".toByteArray(StandardCharsets.US_ASCII) + bytes + byteArrayOf('\n'.code.toByte())
        assertEquals(value, NativeFrames.readClipboardFetch(ByteArrayInputStream(fetched), bytes.size))
        assertNull(NativeFrames.readClipboardFetch(ByteArrayInputStream(fetched), bytes.size - 1))
        val malformed = "CLIP_TCP_CONTENT|2|".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0xc3.toByte(), 0x28.toByte(), '\n'.code.toByte())
        assertNull(NativeFrames.readClipboardFetch(ByteArrayInputStream(malformed), 2))
        assertNull(NativeFrames.readClipboardFetch(input("CLIP_TCP_CONTENT|3|ab"), 3))
        assertEquals(65536, NativeFrames.clipboardNotifyLength("CLIP_TCP_NOTIFY|65536"))
        assertNull(NativeFrames.clipboardNotifyLength("CLIP_TCP_NOTIFY|+2"))
        assertNull(NativeFrames.clipboardNotifyLength("CLIP_TCP_NOTIFY|65537"))
        assertThrows(IllegalArgumentException::class.java) { NativeFrames.clipboardText("a".repeat(65537)) }
        assertThrows(Exception::class.java) { NativeFrames.clipboardText("\uD800") }
    }

    @Test fun keyFramesValidateCodesAndMetaFlags() {
        assertEquals("KEY_DOWN_EVENT|16413|1", NativeFrames.keyFrame(true, 29, 1).toString(StandardCharsets.US_ASCII))
        assertEquals("KEY_UP_EVENT|32806|0", NativeFrames.keyFrame(false, 38, 0).toString(StandardCharsets.US_ASCII))
        assertThrows(IllegalArgumentException::class.java) { NativeFrames.keyFrame(true, 0x4000, 0) }
        assertThrows(IllegalArgumentException::class.java) { NativeFrames.keyFrame(true, 29, 0x800) }
    }
}
