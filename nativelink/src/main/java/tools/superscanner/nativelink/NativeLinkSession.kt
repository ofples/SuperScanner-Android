package tools.superscanner.nativelink

import android.view.KeyEvent
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class NativeKeyboardVisibility { UNKNOWN, VISIBLE, HIDDEN }

/** Approved only by this client's live, on-device pair dialog result. */
class NativeLinkSession internal constructor(
    private val wifi: NativeWifi,
    private val route: NativeWifiRoute,
    private val deviceAddress: Inet4Address,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val keyboardOpened = AtomicBoolean(false)
    private val peerInitialized = AtomicBoolean(false)
    private val pressedKeys = PressedKeyLedger() // Protected by sendLock.
    private val receiveArmed = AtomicBoolean(false)
    private val receiveBusy = AtomicBoolean(false)
    private val receiveGeneration = AtomicLong(0)
    private val clipboardSendBusy = AtomicBoolean(false)
    private val sendLock = Any()
    private val tcpLock = Any()
    private val tcpSockets = mutableSetOf<Socket>()
    private val fetchSocket = AtomicReference<Pair<Long, Socket>?>(null)
    private val clipboardEvents = Channel<Pair<Long, String>>(1)
    private val visibility = MutableStateFlow(NativeKeyboardVisibility.UNKNOWN)
    val keyboardVisibility: StateFlow<NativeKeyboardVisibility> = visibility
    private val closedState = MutableStateFlow(false)
    val isClosed: StateFlow<Boolean> = closedState
    private val udp: DatagramSocket = DatagramSocket(null).also { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(route.localAddress, 0))
        route.network.bindSocket(socket)
        socket.connect(deviceAddress, 12345)
        socket.soTimeout = 500
    }
    @Volatile private var monitor: Closeable? = null
    init {
        try {
            monitor = wifi.watch(route) { close() }
            if (closed.get()) monitor?.close()
            Thread(::receiveLoop, "NativeLink-receive").apply { isDaemon = true }.start()
        } catch (error: Exception) {
            try { monitor?.close() } catch (_: Exception) { }
            udp.close()
            throw error
        }
    }

    /** Activates the tablet's native keyboard-sharing status; delivery is not acknowledged. */
    suspend fun openKeyboard() {
        sendUdp("OPEN|49152|0".toByteArray(StandardCharsets.US_ASCII), opening = true)
    }

    /** Sends an Android key-down event only while the tablet reports a visible input target. */
    suspend fun keyDown(keyCode: Int, metaState: Int = 0) {
        validateKeyCode(keyCode)
        sendUdp(NativeFrames.keyFrame(true, keyCode, metaState), requireVisible = true,
            keyDown = keyCode to metaState)
    }

    /** A key-up is sent even if visibility changed, to give an in-flight key a chance to release. */
    suspend fun keyUp(keyCode: Int, metaState: Int = 0) {
        validateKeyCode(keyCode)
        sendUdp(NativeFrames.keyFrame(false, keyCode, metaState), requireOpen = true,
            keyUp = keyCode to metaState)
    }

    suspend fun closeKeyboard() {
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(sendLock) {
                if (closed.get() || !keyboardOpened.get()) return@synchronized
                sendCloseLocked()
            }
        }
    }

    /** Register this phone as the clipboard reply peer while keeping native keyboard sharing closed. */
    suspend fun prepareClipboardReceive() {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            ensureUsable()
            synchronized(sendLock) {
                coroutineContext.ensureActive()
                ensureUsable()
                sendCloseLocked()
            }
        }
    }

    /** Sets the tablet's system clipboard on explicit user action; no remote acknowledgement exists. */
    suspend fun sendClipboardText(text: String) {
        val frame = NativeFrames.tcpClipboardFrame(text)
        check(clipboardSendBusy.compareAndSet(false, true)) { "Clipboard send is already active" }
        suspendCancellableCoroutine<Unit> { continuation ->
                val operationSocket = AtomicReference<Socket?>(null)
                continuation.invokeOnCancellation { operationSocket.get()?.closeQuietly() }
                Thread({
                    try {
                        if (!continuation.isActive) return@Thread
                        ensureUsable()
                        Socket().use { socket ->
                            registerTcp(socket)
                            operationSocket.set(socket)
                            val deadline = TCP_WATCHDOG.schedule({ socket.closeQuietly() }, 5_000, TimeUnit.MILLISECONDS)
                            try {
                                if (!continuation.isActive) return@Thread
                                route.network.bindSocket(socket)
                                if (!continuation.isActive) return@Thread
                                socket.connect(InetSocketAddress(deviceAddress, 12346), 3_000)
                                socket.soTimeout = 3_000
                                if (!continuation.isActive) return@Thread
                                ensureUsable()
                                socket.getOutputStream().write(frame)
                                socket.getOutputStream().flush()
                            } finally {
                                deadline.cancel(false)
                                operationSocket.set(null)
                                unregisterTcp(socket)
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        clipboardSendBusy.set(false)
                    }
                }, "NativeLink-clipboard-send").apply { isDaemon = true }.start()
        }
    }

    /** Arm reception first, then copy text on the tablet. No current-clipboard fetch exists. */
    suspend fun receiveClipboardText(timeoutMillis: Long = 15_000): String? {
        require(timeoutMillis in 100..60_000)
        ensureUsable()
        check(peerInitialized.get()) { "Prepare native clipboard receive before waiting for a new copy" }
        check(receiveBusy.compareAndSet(false, true)) { "Clipboard receive is already active" }
        receiveArmed.set(true)
        val generation = receiveGeneration.incrementAndGet()
        return try {
            while (clipboardEvents.tryReceive().isSuccess) { /* Discard an earlier canceled wait. */ }
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val event = clipboardEvents.receive()
                    if (event.first == generation) return@withTimeoutOrNull event.second
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally {
            receiveArmed.set(false)
            receiveGeneration.incrementAndGet()
            fetchSocket.get()?.takeIf { it.first == generation }?.second?.closeQuietly()
            receiveBusy.set(false)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closedState.value = true
        receiveArmed.set(false)
        receiveBusy.set(false)
        receiveGeneration.incrementAndGet()
        clipboardEvents.close()
        visibility.value = NativeKeyboardVisibility.UNKNOWN
        synchronized(tcpLock) { tcpSockets.toList().forEach { it.closeQuietly() } }
        try { monitor?.close() } catch (_: Exception) { }
        val wasOpen = keyboardOpened.getAndSet(false)
        peerInitialized.set(false)
        if (!wasOpen || !wifi.isCurrent(route)) {
            udp.close()
            return
        }
        Thread({
            synchronized(sendLock) {
                try {
                    if (wifi.isCurrent(route)) {
                        releasePressedKeysLocked()
                        udp.send(DatagramPacket(CLOSE_FRAME, CLOSE_FRAME.size))
                    }
                }
                catch (_: IOException) { }
                finally { udp.close() }
            }
        }, "NativeLink-close").apply { isDaemon = true }.start()
    }

    private suspend fun sendUdp(frame: ByteArray, opening: Boolean = false,
                                requireOpen: Boolean = false, requireVisible: Boolean = false,
                                keyDown: Pair<Int, Int>? = null, keyUp: Pair<Int, Int>? = null) {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            ensureUsable()
            synchronized(sendLock) {
                coroutineContext.ensureActive()
                ensureUsable()
                if (requireOpen || requireVisible) check(keyboardOpened.get()) { "Native keyboard is not open" }
                if (requireVisible) check(visibility.value == NativeKeyboardVisibility.VISIBLE) {
                    "No visible Supernote text field"
                }
                if (opening) {
                    visibility.value = NativeKeyboardVisibility.UNKNOWN
                }
                udp.send(DatagramPacket(frame, frame.size))
                if (opening) keyboardOpened.set(true)
                peerInitialized.set(true)
                if (keyDown != null) pressedKeys.down(keyDown.first, keyDown.second)
                if (keyUp != null) pressedKeys.up(keyUp.first)
            }
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_NATIVE_CLIPBOARD_BYTES + 512)
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { udp.receive(packet) }
            catch (_: SocketTimeoutException) { continue }
            catch (_: IOException) { close(); break }
            if (closed.get() || !wifi.isCurrent(route)) {
                close()
                break
            }
            val message = NativeFrames.decodeUtf8(buffer, packet.length) ?: continue
            when {
                message == "keyboard_visible" && keyboardOpened.get() -> {
                    // Native status is event-driven, not a periodic lease. Silence after
                    // a normal pause must not hide the phone input or block the next key.
                    visibility.value = NativeKeyboardVisibility.VISIBLE
                }
                message == "keyboard_hide" && keyboardOpened.get() -> {
                    visibility.value = NativeKeyboardVisibility.HIDDEN
                }
                message.startsWith("CLIP|") && receiveArmed.get() -> {
                    val text = message.removePrefix("CLIP|")
                    val generation = receiveGeneration.get()
                    if (text.isNotEmpty() && text.toByteArray(StandardCharsets.UTF_8).size <= MAX_NATIVE_CLIPBOARD_BYTES &&
                        receiveArmed.compareAndSet(true, false)) {
                        if (!closed.get() && receiveGeneration.get() == generation)
                            clipboardEvents.trySend(generation to text)
                    }
                }
                message.startsWith("CLIP_TCP_NOTIFY|") && receiveArmed.get() -> {
                    val length = NativeFrames.clipboardNotifyLength(message) ?: continue
                    val generation = receiveGeneration.get()
                    if (receiveArmed.compareAndSet(true, false)) {
                        if (receiveGeneration.get() != generation) continue
                        fetchClipboard(length, generation)?.let {
                            if (!closed.get() && receiveGeneration.get() == generation)
                                clipboardEvents.trySend(generation to it)
                        }
                    }
                }
            }
        }
    }

    private fun fetchClipboard(length: Int, generation: Long): String? {
        return try {
            if (closed.get() || !wifi.isCurrent(route)) return null
            Socket().use { socket ->
                registerTcp(socket)
                fetchSocket.set(generation to socket)
                val deadline = TCP_WATCHDOG.schedule({ socket.closeQuietly() }, 5_000, TimeUnit.MILLISECONDS)
                try {
                    if (receiveGeneration.get() != generation) return null
                    route.network.bindSocket(socket)
                    socket.connect(InetSocketAddress(deviceAddress, 12346), 3_000)
                    socket.soTimeout = 3_000
                    if (closed.get() || !wifi.isCurrent(route) || receiveGeneration.get() != generation) return null
                    socket.getOutputStream().write("CLIP_TCP_FETCH\n".toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                    NativeFrames.readClipboardFetch(socket.getInputStream(), length)
                } finally {
                    deadline.cancel(false)
                    fetchSocket.get()?.let { current ->
                        if (current.first == generation && current.second === socket)
                            fetchSocket.compareAndSet(current, null)
                    }
                    unregisterTcp(socket)
                }
            }
        } catch (_: IOException) { null }
    }

    /** Caller holds sendLock and has checked the pinned Wi-Fi route. */
    private fun releasePressedKeysLocked() {
        pressedKeys.release { keyCode, metaState ->
            val frame = NativeFrames.keyFrame(false, keyCode, metaState)
            try {
                udp.send(DatagramPacket(frame, frame.size))
                true
            } catch (_: IOException) {
                // A failed release cannot be replayed safely on another route.
                false
            }
        }
    }

    /** Caller holds sendLock. The known CLOSE frame registers our reply peer with sharing off. */
    private fun sendCloseLocked() {
        if (!wifi.isCurrent(route)) {
            close()
            throw IOException("Wi-Fi connection changed")
        }
        visibility.value = NativeKeyboardVisibility.UNKNOWN
        if (keyboardOpened.get()) releasePressedKeysLocked()
        try {
            udp.send(DatagramPacket(CLOSE_FRAME, CLOSE_FRAME.size))
        } catch (error: IOException) {
            close()
            throw error
        }
        keyboardOpened.set(false)
        visibility.value = NativeKeyboardVisibility.UNKNOWN
        pressedKeys.clear()
        peerInitialized.set(true)
    }

    private fun ensureUsable() {
        check(!closed.get()) { "Native Linking session is closed" }
        if (!wifi.isCurrent(route)) {
            close()
            throw IOException("Wi-Fi connection changed")
        }
    }

    private fun registerTcp(socket: Socket) = synchronized(tcpLock) {
        check(!closed.get()) { "Native Linking session is closed" }
        tcpSockets.add(socket)
    }

    private fun unregisterTcp(socket: Socket) = synchronized(tcpLock) { tcpSockets.remove(socket) }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: IOException) { }
    }

    private fun validateKeyCode(keyCode: Int) {
        require(keyCode in 0..minOf(0x3fff, KeyEvent.getMaxKeyCode()))
    }

    private companion object {
        val CLOSE_FRAME = "CLOSE|49152|0".toByteArray(StandardCharsets.US_ASCII)
        val TCP_WATCHDOG = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "NativeLink-tcp-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
