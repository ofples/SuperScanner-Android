package tools.supernote.scan.network

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import tools.supernote.scan.MirrorFrame

class MirrorClient(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    suspend fun discover(manualHost: String? = null, cachedHost: String? = null): List<String> = withContext(Dispatchers.IO) {
        val networks = wifiNetworks()
        if (networks.isEmpty()) return@withContext emptyList()

        if (!manualHost.isNullOrBlank()) {
            val host = validateLocalHost(manualHost)
            val network = networks.firstOrNull { it.contains(host) } ?: return@withContext emptyList()
            return@withContext if (probe(network, host)) listOf(host) else emptyList()
        }

        if (!cachedHost.isNullOrBlank()) {
            val host = runCatching { validateLocalHost(cachedHost) }.getOrNull()
            val network = host?.let { candidate -> networks.firstOrNull { it.contains(candidate) } }
            if (host != null && network != null && probe(network, host)) return@withContext listOf(host)
        }

        val candidates = networks.flatMap { network ->
            connectivity.getLinkProperties(network)?.let(::wifiIpv4Candidates).orEmpty()
        }.distinct()
        if (candidates.isEmpty()) return@withContext emptyList()

        val executor = Executors.newFixedThreadPool(MAX_SCAN_WORKERS)
        try {
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(SCAN_TIMEOUT_MILLIS) {
                    coroutineScope {
                        candidates.map { host -> async {
                            val network = networks.firstOrNull { it.contains(host) }
                            network != null && probeCancellable(network, host, executor)
                        } }.awaitAll().let { results ->
                            candidates.zip(results).filter { it.second }.map { it.first }
                        }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun frames(host: String): Flow<MirrorFrame> = callbackFlow {
        val verifiedHost = runCatching { validateLocalHost(host) }.getOrElse {
            close(it)
            return@callbackFlow
        }
        val socket = AtomicReference<Socket?>()
        val readerJob = launch(Dispatchers.IO) {
            try {
                val network = wifiNetworks().firstOrNull { it.contains(verifiedHost) }
                    ?: throw MirrorStreamException("Connect this phone to the same Wi-Fi network as the Supernote.")
                val currentSocket = openMirrorSocket(network, verifiedHost) { socket.set(it) }
                val stream = BufferedInputStream(currentSocket.getInputStream(), 32 * 1024)
                val response = readResponseHeaders(stream)
                if (!response.contentType.startsWith("multipart/", ignoreCase = true)) {
                    throw MirrorStreamException("This address is not serving the Supernote screen mirror.")
                }
                val boundary = Regex("boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
                    .find(response.contentType)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    ?: throw MirrorStreamException("The mirror response has no multipart boundary.")
                val parser = MirrorFrameParser(bodyStream(stream, response), boundary)
                while (isActive) {
                    val encoded = parser.readFrame()
                    val size = bitmapSize(encoded.bytes)
                    send(MirrorFrame(encoded.bytes, encoded.mimeType, size.first, size.second, System.currentTimeMillis()))
                }
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) close(error)
            } finally {
                runCatching { socket.getAndSet(null)?.close() }
            }
        }
        awaitClose {
            readerJob.cancel()
            runCatching { socket.getAndSet(null)?.close() }
        }
    }.buffer(Channel.CONFLATED)

    private fun wifiNetworks(): List<Network> {
        val active = connectivity.activeNetwork
        return connectivity.allNetworks.asSequence()
            .filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .sortedByDescending { it == active }
            .filter { network -> connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address is Inet4Address && isPrivateIpv4(it.address.hostAddress.orEmpty()) } == true }
            .toList()
    }

    private fun Network.contains(host: String): Boolean {
        val target = ipv4ToLong(host)
        return connectivity.getLinkProperties(this)?.linkAddresses.orEmpty().any { link ->
            val address = link.address as? Inet4Address ?: return@any false
            val local = address.hostAddress.orEmpty()
            if (!isPrivateIpv4(local)) return@any false
            val prefix = link.prefixLength.coerceIn(0, 32)
            val mask = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
            (ipv4ToLong(local) and mask) == (target and mask)
        }
    }

    private fun wifiIpv4Candidates(linkProperties: LinkProperties): List<String> {
        val candidates = LinkedHashSet<String>()
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address as? Inet4Address ?: continue
            val ip = address.hostAddress ?: continue
            if (!isPrivateIpv4(ip)) continue
            val prefix = linkAddress.prefixLength.coerceIn(0, 32)
            val scanPrefix = maxOf(prefix, 24)
            val ipNumber = ipv4ToLong(ip)
            val mask = if (scanPrefix == 0) 0L else (0xffff_ffffL shl (32 - scanPrefix)) and 0xffff_ffffL
            val base = ipNumber and mask
            val count = 1L shl (32 - scanPrefix)
            for (offset in 1 until count - 1) {
                val candidate = longToIpv4(base + offset)
                if (candidate != ip && isPrivateIpv4(candidate)) candidates += candidate
            }
        }
        return candidates.toList()
    }

    private suspend fun probeCancellable(network: Network, host: String, executor: java.util.concurrent.ExecutorService): Boolean =
        suspendCancellableCoroutine { continuation ->
            val socket = runCatching { network.socketFactory.createSocket() }.getOrNull()
            if (socket == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            executor.execute {
                val found = runCatching {
                    socket.use {
                        it.connect(InetSocketAddress(host, MIRROR_PORT), PROBE_TIMEOUT_MILLIS)
                        it.soTimeout = PROBE_TIMEOUT_MILLIS
                        val request = "GET $MIRROR_PATH HTTP/1.1\r\nHost: $host:$MIRROR_PORT\r\nConnection: close\r\n\r\n"
                        it.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                        val stream = BufferedInputStream(it.getInputStream(), 32 * 1024)
                        val response = readResponseHeaders(stream)
                        if (!response.contentType.startsWith("multipart/", ignoreCase = true)) return@runCatching false
                        val boundary = Regex("boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
                            .find(response.contentType)?.let { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                            ?: return@runCatching false
                        validImageSize(MirrorFrameParser(bodyStream(stream, response), boundary).readFrame().bytes)
                    }
                }.getOrDefault(false)
                if (continuation.isActive) continuation.resume(found)
            }
        }

    private suspend fun probe(network: Network, host: String): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return kotlinx.coroutines.withTimeout(PROBE_VERIFY_TIMEOUT_MILLIS) {
                probeCancellable(network, host, executor)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            return false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun openMirrorSocket(network: Network, host: String, onCreated: (Socket) -> Unit): Socket = network.socketFactory.createSocket().also { socket ->
        onCreated(socket)
        socket.connect(InetSocketAddress(host, MIRROR_PORT), CONNECT_TIMEOUT_MILLIS)
        socket.soTimeout = STREAM_READ_TIMEOUT_MILLIS
        val request = "GET $MIRROR_PATH HTTP/1.1\r\nHost: $host:$MIRROR_PORT\r\nConnection: keep-alive\r\n\r\n"
        BufferedOutputStream(socket.getOutputStream()).apply {
            write(request.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun readResponseHeaders(input: InputStream): MirrorResponseHeaders {
        val output = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (output.size() < MAX_RESPONSE_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) throw MirrorStreamException("The mirror closed before sending response headers.")
            output.write(next)
            matched = if (next.toByte() == delimiter[matched]) matched + 1 else if (next.toByte() == delimiter[0]) 1 else 0
            if (matched == delimiter.size) {
                val headers = output.toString(Charsets.ISO_8859_1.name())
                val status = headers.lineSequence().firstOrNull().orEmpty()
                if (!status.contains(" 200 ")) throw MirrorStreamException("The mirror returned an error response.")
                val contentType = Regex("(?im)^content-type:\\s*([^\\r\\n]+)")
                    .find(headers)?.groupValues?.get(1)?.trim()
                    ?: throw MirrorStreamException("The mirror response has no content type.")
                val transferEncoding = Regex("(?im)^transfer-encoding:\\s*([^\\r\\n]+)")
                    .find(headers)?.groupValues?.get(1)?.trim().orEmpty()
                if (transferEncoding.isNotBlank() && !transferEncoding.equals("chunked", ignoreCase = true)) {
                    throw MirrorStreamException("The mirror uses an unsupported HTTP transfer encoding.")
                }
                return MirrorResponseHeaders(contentType, transferEncoding.equals("chunked", ignoreCase = true))
            }
        }
        throw MirrorStreamException("The mirror response headers are too large.")
    }

    private fun bitmapSize(bytes: ByteArray): Pair<Int, Int> {
        val dimensions = readBitmapBounds(bytes)
        if (!validDimensions(dimensions.first, dimensions.second)) {
            throw MirrorStreamException("The mirror image dimensions exceed the supported limit.")
        }
        return dimensions
    }

    private fun validImageSize(bytes: ByteArray): Boolean = runCatching {
        val dimensions = readBitmapBounds(bytes)
        validDimensions(dimensions.first, dimensions.second)
    }.getOrDefault(false)

    private fun readBitmapBounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw MirrorStreamException("The mirror image has invalid dimensions.")
        return options.outWidth to options.outHeight
    }

    private fun validDimensions(width: Int, height: Int): Boolean =
        width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION && width.toLong() * height <= MAX_IMAGE_PIXELS

    private fun bodyStream(stream: InputStream, response: MirrorResponseHeaders): InputStream =
        if (response.chunked) ChunkedInputStream(stream) else stream

    private fun validateLocalHost(value: String): String {
        val normalized = value.trim()
        require(IPV4_LITERAL.matches(normalized)) { "Enter a local IPv4 address." }
        val parsed = runCatching { InetAddress.getByName(normalized) }.getOrNull() as? Inet4Address
            ?: throw IllegalArgumentException("Enter a local IPv4 address.")
        val host = parsed.hostAddress
        require(isPrivateIpv4(host)) { "Screen Mirroring must use a private or link-local IPv4 address." }
        return host
    }

    private fun ipv4ToLong(host: String): Long = host.split('.').fold(0L) { value, part -> (value shl 8) or part.toLong() }

    private fun longToIpv4(value: Long): String = listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255).toString() }

    companion object {
        const val MIRROR_PORT = 8080
        const val MIRROR_PATH = "/screencast.mjpeg"
        const val MAX_SCAN_WORKERS = 32
        private const val PROBE_TIMEOUT_MILLIS = 450
        private const val CONNECT_TIMEOUT_MILLIS = 2_500
        private const val STREAM_READ_TIMEOUT_MILLIS = 5_000
        private const val SCAN_TIMEOUT_MILLIS = 10_000L
        private const val PROBE_VERIFY_TIMEOUT_MILLIS = 2_500L
        private const val MAX_RESPONSE_HEADER_BYTES = 16 * 1024
        private const val MAX_IMAGE_DIMENSION = 8_000
        private const val MAX_IMAGE_PIXELS = 16_000_000L
        private val IPV4_LITERAL = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    }
}

private data class MirrorResponseHeaders(val contentType: String, val chunked: Boolean)

internal class ChunkedInputStream(private val source: InputStream) : InputStream() {
    private var remaining = 0L
    private var finished = false
    private var needsTerminator = false

    override fun read(): Int {
        if (!prepareChunk()) return -1
        val value = source.read()
        if (value < 0) throw IOException("The mirror ended in an HTTP chunk.")
        remaining--
        if (remaining == 0L) needsTerminator = true
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!prepareChunk()) return -1
        val read = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw IOException("The mirror ended in an HTTP chunk.")
        remaining -= read
        if (remaining == 0L) needsTerminator = true
        return read
    }

    private fun prepareChunk(): Boolean {
        if (finished) return false
        if (remaining > 0) return true
        if (needsTerminator) {
            expectCrlf()
            needsTerminator = false
        }
        val sizeLine = readCrlfLine()
        val sizeText = sizeLine.substringBefore(';').trim()
        val size = sizeText.toLongOrNull(16) ?: throw IOException("The mirror returned an invalid HTTP chunk size.")
        if (size < 0) throw IOException("The mirror returned an invalid HTTP chunk size.")
        if (size == 0L) {
            while (readCrlfLine().isNotEmpty()) Unit
            finished = true
            return false
        }
        remaining = size
        return true
    }

    private fun readCrlfLine(): String {
        val line = ByteArrayOutputStream()
        var prior = -1
        while (line.size() < MAX_CHUNK_HEADER_BYTES) {
            val value = source.read()
            if (value < 0) throw IOException("The mirror ended in HTTP chunk framing.")
            if (prior == '\r'.code && value == '\n'.code) {
                val bytes = line.toByteArray()
                return bytes.copyOf(bytes.size - 1).toString(Charsets.ISO_8859_1)
            }
            line.write(value)
            prior = value
        }
        throw IOException("The mirror returned an oversized HTTP chunk header.")
    }

    private fun expectCrlf() {
        if (source.read() != '\r'.code || source.read() != '\n'.code) throw IOException("The mirror returned invalid HTTP chunk framing.")
    }

    override fun close() = source.close()

    companion object { private const val MAX_CHUNK_HEADER_BYTES = 128 }
}


internal fun isPrivateIpv4(host: String): Boolean {
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}
