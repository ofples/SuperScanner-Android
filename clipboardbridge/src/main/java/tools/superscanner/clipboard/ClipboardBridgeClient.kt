package tools.superscanner.clipboard

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PairStatus { PENDING, APPROVED, REJECTED, UNPAIRED }

/** Phone half of the explicit, pinned, mutually authenticated transfer. */
class ClipboardBridgeClient internal constructor(
    private val network: BridgeNetwork,
    private val localIdentity: () -> BridgeTls.Identity = { BridgeTls.identity(server = false) },
) : AutoCloseable {
    constructor(context: Context) : this(WifiBridgeNetwork(context))

    private val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2)) { runnable ->
        Thread(runnable, "clipboard-client").apply { isDaemon = true }
    }
    private val sockets = mutableSetOf<Socket>()
    private val lock = Any()
    private var closed = false

    fun identityFingerprint(): String = localIdentity().fingerprint
    fun identityShortCode(): String = BridgeTls.shortCode(identityFingerprint())
    fun approvalCode(info: ClipboardPairInfo): String = info.approvalCode(identityFingerprint())

    suspend fun requestPair(info: ClipboardPairInfo): PairStatus = pairResult(info, "pair")
    suspend fun pairStatus(info: ClipboardPairInfo): PairStatus = pairResult(info, "status")

    suspend fun receive(info: ClipboardPairInfo): String {
        val result = exchange(info, "get")
        if (result.status != "ok" || result.text == null) throw BridgeException("No shared text or pairing unavailable")
        return result.text
    }

    suspend fun send(info: ClipboardPairInfo, text: String) {
        BridgeWire.textBytes(text)
        val result = exchange(info, "put", text)
        if (result.status != "ok") throw BridgeException("Transfer rejected or inbox full")
    }

    private suspend fun pairResult(info: ClipboardPairInfo, op: String): PairStatus = when (exchange(info, op).status) {
        "pending" -> PairStatus.PENDING
        "approved" -> PairStatus.APPROVED
        "rejected" -> PairStatus.REJECTED
        "unpaired" -> PairStatus.UNPAIRED
        else -> throw BridgeException("Pairing unavailable")
    }

    private suspend fun exchange(info: ClipboardPairInfo, op: String, text: String? = null): BridgeWire.Message =
        suspendCancellableCoroutine { continuation ->
            val holder = AtomicReference<Socket?>()
            continuation.invokeOnCancellation { runCatching { holder.get()?.close() } }
            val task = Runnable {
                var plain: Socket? = null
                try {
                    if (!continuation.isActive) return@Runnable
                    if (!network.permits(info.host)) throw BridgeException("Helper is outside connected private Wi-Fi")
                    val newSocket = network.socket(info.host)
                    plain = newSocket
                    holder.set(newSocket)
                    synchronized(lock) {
                        if (closed) throw BridgeException("Client closed")
                        sockets += newSocket
                    }
                    if (!continuation.isActive) throw BridgeException("Transfer cancelled")
                    newSocket.connect(InetSocketAddress(info.host, info.port), 5000)
                    newSocket.soTimeout = 5000
                    val identity = localIdentity()
                    val factory = BridgeTls.context(identity, BridgeTls.pinnedServerTrust(info.serverFingerprint)).socketFactory
                    if (!continuation.isActive) throw BridgeException("Transfer cancelled")
                    val tls = factory.createSocket(newSocket, info.host, info.port, true) as SSLSocket
                    holder.set(tls)
                    synchronized(lock) {
                        sockets.remove(newSocket)
                        if (closed) throw BridgeException("Client closed")
                        sockets += tls
                    }
                    if (!continuation.isActive) throw BridgeException("Transfer cancelled")
                    tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    tls.use {
                        it.startHandshake()
                        if (!continuation.isActive) throw BridgeException("Transfer cancelled")
                        BridgeWire.write(DataOutputStream(it.outputStream), op = op, text = text)
                        val result = BridgeWire.read(DataInputStream(it.inputStream), request = false)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    runCatching { holder.get()?.close() }
                    synchronized(lock) {
                        plain?.let(sockets::remove)
                        holder.get()?.let(sockets::remove)
                    }
                }
            }
            try { executor.execute(task) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(BridgeException("Client busy or closed"))
            }
        }

    override fun close() {
        val toClose = synchronized(lock) {
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        toClose.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }
}
