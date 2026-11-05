package tools.supernote.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.supernote.scan.network.MirrorClient
import tools.supernote.scan.network.ChunkedInputStream
import tools.supernote.scan.network.MirrorFrameParser
import tools.supernote.scan.network.isPrivateIpv4

/** Exercises the production discovery and stream socket path against a local emulator-only fixture. */
class MirrorClientNetworkInstrumentedTest {
    @Test
    fun discoversAndStreamsTwoSyntheticScreenPagesOverChunkedMjpegThenClosesOnCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localIp = wifiIpv4(context)
        val pages = SampleNotes.pages
        // SampleNotes draws/compresses lazily. Render and frame the fixture before the production
        // client starts its short identity probe, so fixture work cannot consume probe time.
        val pageBytes = pages.map { it.image }
        val probePng = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val imagePartHeader = "Content-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val partHeader = "--frame\r\n".toByteArray(Charsets.US_ASCII) + imagePartHeader
        val firstPart = partHeader + pageBytes[0] + "\r\n".toByteArray(Charsets.US_ASCII)
        // firstStreamChunks ends with the next boundary, so do not repeat it here.
        val secondPart = imagePartHeader + pageBytes[1] + "\r\n--frame--\r\n".toByteArray(Charsets.US_ASCII)
        val probeBody = partHeader + probePng + "\r\n--frame--\r\n".toByteArray(Charsets.US_ASCII)
        val probeChunks = encodeChunked(probeBody)
        val encodedProbeBody = ByteArrayOutputStream().apply { probeChunks.forEach(::write) }.toByteArray()
        val parsedProbe = MirrorFrameParser(
            ChunkedInputStream(ByteArrayInputStream(encodedProbeBody)), "frame").readFrame()
        assertArrayEquals(probePng, parsedProbe.bytes)
        val firstStreamChunks = encodeChunked(firstPart + "--frame\r\n".toByteArray(Charsets.US_ASCII))
        val secondStreamChunks = encodeChunked(secondPart)
        val encodedStreamBody = ByteArrayOutputStream().apply {
            firstStreamChunks.forEach(::write)
            secondStreamChunks.forEach(::write)
        }.toByteArray()
        val streamParser = MirrorFrameParser(
            ChunkedInputStream(ByteArrayInputStream(encodedStreamBody)), "frame")
        assertArrayEquals(pageBytes[0], streamParser.readFrame().bytes)
        assertArrayEquals(pageBytes[1], streamParser.readFrame().bytes)
        val accepted = AtomicInteger()
        val cancellationObserved = AtomicReference(false)
        ServerSocket(MirrorClient.MIRROR_PORT, 4, InetAddress.getByName(localIp)).use { server ->
            server.soTimeout = 8_000
            val serverJob = async(Dispatchers.IO) {
                repeat(2) {
                    server.accept().use { socket ->
                        val index = accepted.incrementAndGet()
                        socket.soTimeout = 15_000
                        readRequest(socket)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                                (if (index == 1) "Content-Length: ${probeBody.size}\r\n"
                                    else "Transfer-Encoding: chunked\r\n") +
                                "Connection: keep-alive\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII))
                            // Split headers and both image bodies across irregular HTTP chunks.
                            // Parts omit Content-Length, and the bytes intentionally disagree
                            // with each part's JPEG label to exercise PNG sniffing. The chunk
                            // bodies were prebuilt before accepting the client's probe.
                            if (index == 1) {
                                write(probeBody)
                                flush()
                            } else {
                                // Delimit frame one, then pause briefly so the conflated production
                                // Flow can deliver it before frame two arrives.
                                writeChunks(this, firstStreamChunks)
                                delay(150)
                                writeChunks(this, secondStreamChunks)
                                cancellationObserved.set(try {
                                    socket.getInputStream().read() == -1
                                } catch (_: SocketTimeoutException) {
                                    false
                                })
                            }
                        }
                    }
                }
            }

            val client = MirrorClient(context)
            val discovered = withTimeout(8_000) { client.discover(manualHost = localIp) }
            assertEquals("Probe accepted ${accepted.get()} connections", listOf(localIp), discovered)
            val frames = withTimeout(15_000) { client.frames(localIp).take(2).toList() }
            assertEquals(2, frames.size)
            frames.zip(pages).forEach { (frame, page) ->
                assertEquals("image/png", frame.mimeType)
                assertEquals(SampleNotes.WIDTH, frame.width)
                assertEquals(SampleNotes.HEIGHT, frame.height)
                assertArrayEquals(page.image, frame.bytes)
            }
            withTimeout(3_000) {
                while (!cancellationObserved.get()) delay(20)
            }
            serverJob.await()
            assertEquals(2, accepted.get())
            assertTrue(cancellationObserved.get())
        }
    }

    private fun encodeChunked(payload: ByteArray): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        val sizes = listOf(97, 1031, 257, 8191, 2047, 16384, 4093, 307, 8192)
        var offset = 0
        var sizeIndex = 0
        while (offset < payload.size) {
            val count = minOf(sizes[sizeIndex++ % sizes.size], payload.size - offset)
            val block = ByteArrayOutputStream(count + 24).apply {
                write("${count.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                write(payload, offset, count)
                write("\r\n".toByteArray(Charsets.US_ASCII))
            }.toByteArray()
            output += block
            offset += count
        }
        return output
    }

    private fun writeChunks(output: java.io.OutputStream, chunks: List<ByteArray>) {
        chunks.forEach(output::write)
        output.flush()
    }

    private fun wifiIpv4(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { isPrivateIpv4(it.hostAddress.orEmpty()) }
                ?.hostAddress
        } ?: error("The emulator has no private IPv4 Wi-Fi address for the local mirror fixture.")
    }

    private fun readRequest(socket: Socket) {
        val request = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (request.size() < 8 * 1024) {
            val value = socket.getInputStream().read()
            if (value < 0) error("Mirror client closed before sending its request.")
            request.write(value)
            matched = if (value.toByte() == delimiter[matched]) matched + 1
                else if (value.toByte() == delimiter[0]) 1 else 0
            if (matched == delimiter.size) {
                val text = request.toString(Charsets.US_ASCII.name())
                assertTrue(text.startsWith("GET ${MirrorClient.MIRROR_PATH} HTTP/1.1"))
                return
            }
        }
        error("Mirror client request headers were too large.")
    }

}
