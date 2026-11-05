package tools.supernote.scan.network

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MirrorFrameParserTest {
    @Test
    fun readsFragmentedPngEvenWhenHeaderClaimsJpeg() {
        val image = pngFrame()
        val response = part(image, "image/jpeg", boundary = "frame", declaredLength = image.size)

        val parsed = MirrorFrameParser(FragmentedInputStream(response, maxChunk = 3), "frame").readFrame()

        assertEquals("image/png", parsed.mimeType)
        assertEquals(image.toList(), parsed.bytes.toList())
    }

    @Test
    fun acceptsFirstPartWithoutLeadingBoundary() {
        val image = jpegFrame()
        val response = "Content-Type: image/jpeg\r\nContent-Length: ${image.size}\r\n\r\n".toByteArray() + image

        val parsed = MirrorFrameParser(FragmentedInputStream(response, maxChunk = 1), "stream-boundary").readFrame()

        assertEquals("image/jpeg", parsed.mimeType)
        assertEquals(image.toList(), parsed.bytes.toList())
    }

    @Test
    fun acceptsMissingContentLengthAndReadsSuccessiveFrames() {
        val first = jpegFrame(1)
        val second = pngFrame(2)
        val response = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + first +
            "\r\n--frame\r\nContent-Type: image/png\r\n\r\n".toByteArray() + second + "\r\n--frame--\r\n".toByteArray()
        val parser = MirrorFrameParser(FragmentedInputStream(response, maxChunk = 5), "frame")

        assertEquals("image/jpeg", parser.readFrame().mimeType)
        assertEquals("image/png", parser.readFrame().mimeType)
    }

    @Test
    fun returnsACompleteUnboundedPngWithoutWaitingForTheNextBoundary() {
        val image = pngFrame()
        val singleFrame = "Content-Type: image/jpeg\r\n\r\n".toByteArray() + image

        val parsed = MirrorFrameParser(FragmentedInputStream(singleFrame, maxChunk = 1), "--frame").readFrame()

        assertEquals("image/png", parsed.mimeType)
        assertEquals(image.toList(), parsed.bytes.toList())
    }

    @Test
    fun rejectsPrematureEofAndNonMultipartContent() {
        val truncated = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 20\r\n\r\n\u00ff\u00d8cut".toByteArray()
        assertThrows(MirrorStreamException::class.java) {
            MirrorFrameParser(ByteArrayInputStream(truncated), "frame").readFrame()
        }
        assertThrows(MirrorStreamException::class.java) {
            MirrorFrameParser(ByteArrayInputStream("<html>not a mirror</html>".toByteArray()), "frame").readFrame()
        }
    }

    @Test
    fun rejectsUnsupportedFrameBytesAndOversizedDeclaredFrame() {
        val unsupported = part("no image".toByteArray(), "image/png", "frame", "no image".length)
        assertThrows(MirrorStreamException::class.java) {
            MirrorFrameParser(ByteArrayInputStream(unsupported), "frame").readFrame()
        }
        val tooLarge = "--frame\r\nContent-Type: image/png\r\nContent-Length: 13\r\n\r\n".toByteArray()
        assertThrows(MirrorStreamException::class.java) {
            MirrorFrameParser(ByteArrayInputStream(tooLarge), "frame", maxFrameBytes = 12).readFrame()
        }
    }

    @Test
    fun decodesChunkedHttpBodyBeforeMultipartParsing() {
        val image = jpegFrame()
        val multipart = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${image.size}\r\n\r\n".toByteArray() + image
        val chunked = multipart.toList().chunked(7).joinToString(separator = "") { chunk ->
            val bytes = chunk.map { it.toByte() }.toByteArray()
            "${bytes.size.toString(16)};ext=x\r\n" + bytes.toString(Charsets.ISO_8859_1) + "\r\n"
        }.toByteArray(Charsets.ISO_8859_1) + "0\r\n\r\n".toByteArray()
        val decoded = ChunkedInputStream(FragmentedInputStream(chunked, maxChunk = 2))

        val parsed = MirrorFrameParser(decoded, "frame").readFrame()

        assertEquals("image/jpeg", parsed.mimeType)
        assertEquals(image.toList(), parsed.bytes.toList())
    }

    private fun part(image: ByteArray, declaredType: String, boundary: String, declaredLength: Int): ByteArray =
        "--$boundary\r\nContent-Type: $declaredType\r\nContent-Length: $declaredLength\r\n\r\n".toByteArray() + image

    private fun jpegFrame(suffix: Int = 0) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), suffix.toByte(), 0xff.toByte(), 0xd9.toByte())

    private fun pngFrame(suffix: Int = 0) = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, suffix.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44.toByte(), 0xae.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    private class FragmentedInputStream(source: ByteArray, private val maxChunk: Int) : FilterInputStream(ByteArrayInputStream(source)) {
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, maxChunk))
    }
}

class MirrorDiscoveryAddressTest {
    @Test
    fun limitsCandidatesToPrivateAndLinkLocalIpv4Ranges() {
        assertEquals(true, isPrivateIpv4("10.0.0.8"))
        assertEquals(true, isPrivateIpv4("172.31.4.9"))
        assertEquals(true, isPrivateIpv4("192.168.1.9"))
        assertEquals(true, isPrivateIpv4("169.254.4.2"))
        assertEquals(false, isPrivateIpv4("172.32.0.1"))
        assertEquals(false, isPrivateIpv4("8.8.8.8"))
        assertEquals(false, isPrivateIpv4("192.168.1.999"))
    }
}
