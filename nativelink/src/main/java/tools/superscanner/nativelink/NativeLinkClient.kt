package tools.superscanner.nativelink

import android.content.Context
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A discovery claim from the tablet's unauthenticated local-network reply. */
data class NativeDevice(val name: String, val ipv4: String, val identifier: String) {
    override fun toString(): String = "NativeDevice(name=$name, ipv4=$ipv4, identifier=<redacted>)"
}

sealed interface NativePairResult {
    data class Approved(val session: NativeLinkSession) : NativePairResult
    /** The device did not approve; this also includes the device dialog's own timeout/failure. */
    data object Rejected : NativePairResult
    data object TimedOut : NativePairResult
    data object Unavailable : NativePairResult
}

/** Implements the inspected firmware's native Linking discovery and approval prompt. */
class NativeLinkClient(context: Context) {
    private val wifi = NativeWifi(context)

    suspend fun discover(timeoutMillis: Long = 1_500): List<NativeDevice> = withContext(Dispatchers.IO) {
        require(timeoutMillis in 100..10_000)
        val route = wifi.select() ?: return@withContext emptyList()
        val found = linkedMapOf<String, NativeDevice>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(route.localAddress, 0))
            route.network.bindSocket(socket)
            socket.broadcast = true
            socket.soTimeout = 250
            val payload = "Supernote_Partner_Peer_Rquest".toByteArray(StandardCharsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, route.broadcastAddress(), 8100))
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (System.nanoTime() < deadline && found.size < 16) {
                coroutineContext.ensureActive()
                if (!wifi.isCurrent(route)) break
                val buffer = ByteArray(513)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val address = packet.address as? Inet4Address ?: continue
                if (packet.port != 8100) continue
                if (!route.contains(address)) continue
                val device = NativeFrames.discoveryReply(buffer, packet.length, address) ?: continue
                found[device.ipv4] = device
            }
        }
        found.values.toList()
    }

    /** Creates a fresh comparison code locally, before any packet is sent. */
    fun preparePair(device: NativeDevice): NativePairAttempt {
        val random = SecureRandom()
        val suffix = ByteArray(4).also(random::nextBytes).joinToString("") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 255)
        }
        val code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
        return NativePairAttempt(wifi, wifi.select(), device, "SuperScanner-$suffix", code)
    }
}

class NativePairAttempt internal constructor(
    private val wifi: NativeWifi,
    private val route: NativeWifiRoute?,
    private val device: NativeDevice,
    val displayName: String,
    val comparisonCode: String,
) {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val requestSent = AtomicBoolean(false)
    private val cancelSent = AtomicBoolean(false)
    private val socket = AtomicReference<Socket?>(null)

    /** A second call or cancellation fails closed. The tablet's own dialog expires at 60 seconds. */
    suspend fun awaitApproval(timeoutMillis: Long = 70_000): NativePairResult {
        require(timeoutMillis in 1_000..70_000)
        if (!started.compareAndSet(false, true) || cancelled.get()) return NativePairResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            Thread({
                val result = runPair(timeoutMillis)
                finished.set(true)
                if (continuation.isActive && !cancelled.get()) {
                    continuation.resume(result) { _, delivered, _ ->
                        if (delivered is NativePairResult.Approved) delivered.session.close()
                    }
                } else if (result is NativePairResult.Approved) {
                    result.session.close()
                }
            }, "NativeLink-pair").apply { isDaemon = true }.start()
        }
    }

    fun cancel() {
        if (finished.get()) return
        cancelled.set(true)
        socket.getAndSet(null)?.closeQuietly()
    }

    private fun runPair(timeoutMillis: Long): NativePairResult {
        val selected = route ?: return NativePairResult.Unavailable
        val ip = numericPrivateIpv4(device.ipv4) ?: return NativePairResult.Unavailable
        if (!selected.contains(ip) || !wifi.isCurrent(selected) || cancelled.get()) return NativePairResult.Unavailable
        var result: NativePairResult = NativePairResult.Unavailable
        val deadlineExpired = AtomicBoolean(false)
        var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            val connected = Socket()
            socket.set(connected)
            watchdog = PAIR_WATCHDOG.schedule({
                deadlineExpired.set(true)
                connected.closeQuietly()
            }, timeoutMillis, TimeUnit.MILLISECONDS)
            if (cancelled.get()) return NativePairResult.Unavailable
            selected.network.bindSocket(connected)
            if (cancelled.get()) return NativePairResult.Unavailable
            connected.connect(InetSocketAddress(ip, 60002), 3_000)
            if (cancelled.get() || !wifi.isCurrent(selected)) return NativePairResult.Unavailable
            connected.soTimeout = timeoutMillis.toInt()
            requestSent.set(true)
            connected.getOutputStream().write(NativeFrames.pairRequest(displayName, comparisonCode))
            connected.getOutputStream().flush()
            val code = NativeFrames.pairResponse(connected.getInputStream())
            result = when (code) {
                200 -> if (!cancelled.get() && !deadlineExpired.get() && wifi.isCurrent(selected)) {
                    try {
                        NativePairResult.Approved(NativeLinkSession(wifi, selected, ip))
                    } catch (_: IOException) { NativePairResult.Unavailable }
                } else NativePairResult.Unavailable
                201 -> NativePairResult.Rejected
                else -> NativePairResult.Unavailable
            }
            return result
        } catch (_: SocketTimeoutException) {
            result = NativePairResult.TimedOut
            return result
        } catch (_: Exception) {
            result = if (deadlineExpired.get()) NativePairResult.TimedOut else NativePairResult.Unavailable
            return result
        } finally {
            watchdog?.cancel(false)
            socket.getAndSet(null)?.closeQuietly()
            if (requestSent.get() && result !is NativePairResult.Approved &&
                result !is NativePairResult.Rejected && cancelSent.compareAndSet(false, true)) {
                sendScopedCancel()
            }
        }
    }

    private fun sendScopedCancel() {
        val selected = route ?: return
        val ip = numericPrivateIpv4(device.ipv4) ?: return
        if (!selected.contains(ip) || !wifi.isCurrent(selected)) return
        try {
            Socket().use { outgoing ->
                selected.network.bindSocket(outgoing)
                outgoing.connect(InetSocketAddress(ip, 60002), 1_500)
                outgoing.soTimeout = 500
                outgoing.getOutputStream().write(NativeFrames.pairCancel(displayName))
                outgoing.getOutputStream().flush()
            }
        } catch (_: IOException) {
            // The local approval state is already invalidated.
        }
    }

    private companion object {
        val PAIR_WATCHDOG = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "NativeLink-pair-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

private fun Socket.closeQuietly() {
    try { close() } catch (_: IOException) { }
}
