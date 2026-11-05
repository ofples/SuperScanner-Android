package tools.superscanner.clipboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

interface ClipboardBridgeListener {
    /** UI must show this code and offer an explicit Approve or Reject action. Called on main thread. */
    fun onPairRequest(clientFingerprint: String, shortCode: String) {}
    fun onReceivedText() {}
    fun onSessionChanged() {}
}

/** Foreground helper session. Call stop() when the helper UI/session leaves the foreground. */
class ClipboardBridgeServer internal constructor(
    private val network: BridgeNetwork,
    private val listener: ClipboardBridgeListener,
) : AutoCloseable {
    constructor(context: Context, listener: ClipboardBridgeListener) : this(WifiBridgeNetwork(context), listener)

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var server: SSLServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ThreadPoolExecutor? = null
    private val active = mutableSetOf<Socket>()
    private var pending: String? = null
    private var pendingUntil = 0L
    private var nextPairAt = 0L
    private var approved: String? = null
    private var snapshot: String? = null
    private var received: String? = null
    private var pairInfo: ClipboardPairInfo? = null
    private var sessionGeneration = 0L

    val pendingFingerprint: String? get() = synchronized(lock) {
        expirePending(); pending
    }
    val approvedFingerprint: String? get() = synchronized(lock) { approved }
    val pendingApprovalCode: String? get() = synchronized(lock) {
        expirePending()
        pending?.let { fingerprint -> pairInfo?.approvalCode(fingerprint) }
    }
    val receivedText: String? get() = synchronized(lock) { received }

    fun start(): ClipboardPairInfo = synchronized(lock) {
        check(server == null) { "Session already active" }
        val address = network.bindingAddress()
        val identity = BridgeTls.identity(server = true)
        val factory = BridgeTls.context(identity, BridgeTls.candidateClientTrust).serverSocketFactory
        val listenerSocket = factory.createServerSocket(0, 4, address) as SSLServerSocket
        listenerSocket.needClientAuth = true
        listenerSocket.enabledProtocols = listenerSocket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        listenerSocket.soTimeout = 1000
        val pool = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(3)) { runnable ->
            Thread(runnable, "clipboard-worker").apply { isDaemon = true }
        }
        server = listenerSocket
        sessionGeneration++
        val info = ClipboardPairInfo(address.hostAddress.orEmpty(), listenerSocket.localPort, identity.fingerprint)
        pairInfo = info
        workers = pool
        nextPairAt = 0L
        val thread = Thread({ acceptLoop(listenerSocket, pool) }, "clipboard-accept").apply { isDaemon = true }
        acceptThread = thread
        thread.start()
        info
    }

    fun publishSnapshot(text: String) {
        BridgeWire.textBytes(text)
        synchronized(lock) {
            check(server != null) { "Session is stopped" }
            snapshot = text
        }
    }

    fun clearSnapshot() = synchronized(lock) { snapshot = null }

    fun takeReceivedText(): String? = synchronized(lock) {
        val result = received
        received = null
        result
    }

    fun clearReceivedText() = synchronized(lock) { received = null }

    fun approve(fingerprint: String): Boolean = synchronized(lock) {
        expirePending()
        if (server == null || pending != fingerprint || approved != null) return@synchronized false
        approved = fingerprint
        pending = null
        pendingUntil = 0L
        notifySession()
        true
    }

    fun reject() {
        synchronized(lock) {
            pending = null
            pendingUntil = 0L
            notifySession()
        }
    }

    fun unpair() {
        synchronized(lock) {
            approved = null
            pending = null
            pendingUntil = 0L
            snapshot = null
            received = null
            active.toList().forEach { runCatching { it.close() } }
            notifySession()
        }
    }

    override fun close() = stop()

    fun stop() {
        val sockets: List<Socket>
        val listenerSocket: SSLServerSocket?
        val pool: ThreadPoolExecutor?
        synchronized(lock) {
            listenerSocket = server
            server = null
            sessionGeneration++
            pairInfo = null
            pool = workers
            workers = null
            approved = null
            pending = null
            pendingUntil = 0L
            snapshot = null
            received = null
            sockets = active.toList()
        }
        runCatching { listenerSocket?.close() }
        sockets.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        notifySession()
    }

    private fun acceptLoop(listenerSocket: SSLServerSocket, pool: ThreadPoolExecutor) {
        while (!listenerSocket.isClosed) {
            val socket = try { listenerSocket.accept() as SSLSocket }
            catch (_: java.net.SocketTimeoutException) { continue }
            catch (_: SocketException) { break }
            catch (_: Exception) { break }
            socket.soTimeout = 5000
            if (!network.permits(socket.inetAddress.hostAddress.orEmpty())) {
                runCatching { socket.close() }
                continue
            }
            val accepted = synchronized(lock) {
                if (server !== listenerSocket) false else { active += socket; true }
            }
            if (!accepted) { runCatching { socket.close() }; continue }
            try { pool.execute { handle(socket) } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                synchronized(lock) { active.remove(socket) }
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: SSLSocket) {
        try {
            socket.use {
                it.startHandshake()
                val peer = (it.session.peerCertificates.firstOrNull() as? X509Certificate)
                    ?: throw BridgeException("Missing client identity")
                val fingerprint = BridgeTls.fingerprint(peer)
                val request = BridgeWire.read(DataInputStream(it.inputStream), request = true)
                val result = synchronized(lock) { dispatch(request, fingerprint) }
                BridgeWire.write(DataOutputStream(it.outputStream), status = result.first, text = result.second)
            }
        } catch (_: Exception) {
            // No text, certificate, or request details are logged or returned.
        } finally { synchronized(lock) { active.remove(socket) } }
    }

    private fun dispatch(message: BridgeWire.Message, fingerprint: String): Pair<String, String?> {
        if (server == null) return "error" to null
        expirePending()
        return when (message.op) {
            "pair" -> {
                if (approved != null) return (if (approved == fingerprint) "approved" else "rejected") to null
                if (pending == fingerprint) return "pending" to null
                val now = android.os.SystemClock.elapsedRealtime()
                if (pending != null || now < nextPairAt) return "rejected" to null
                pending = fingerprint
                pendingUntil = now + 60_000L
                nextPairAt = now + 5_000L
                val generation = sessionGeneration
                main.post {
                    val code = synchronized(lock) {
                        if (pending == fingerprint && server != null && sessionGeneration == generation)
                            pairInfo?.approvalCode(fingerprint) else null
                    }
                    if (code != null) listener.onPairRequest(fingerprint, code)
                }
                "pending" to null
            }
            "status" -> (when (fingerprint) {
                approved -> "approved"
                pending -> "pending"
                else -> "unpaired"
            }) to null
            "get" -> {
                if (approved != fingerprint) "rejected" to null
                else snapshot?.let { "ok" to it } ?: ("error" to null)
            }
            "put" -> {
                if (approved != fingerprint) "rejected" to null
                else if (received != null) "error" to null
                else {
                    received = message.text
                    main.post {
                        if (synchronized(lock) { server != null && received != null }) listener.onReceivedText()
                    }
                    "ok" to null
                }
            }
            else -> "error" to null
        }
    }

    private fun expirePending() {
        if (pending != null && android.os.SystemClock.elapsedRealtime() >= pendingUntil) {
            pending = null
            pendingUntil = 0L
            notifySession()
        }
    }

    private fun notifySession() { main.post { listener.onSessionChanged() } }
}
