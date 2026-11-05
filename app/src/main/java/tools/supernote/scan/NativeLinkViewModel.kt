package tools.supernote.scan

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tools.superscanner.nativelink.NativeDevice
import tools.superscanner.nativelink.NativeKeyboardVisibility
import tools.superscanner.nativelink.NativeLinkClient
import tools.superscanner.nativelink.NativeLinkSession
import tools.superscanner.nativelink.NativePairAttempt
import tools.superscanner.nativelink.NativePairResult
import java.nio.charset.StandardCharsets

internal interface NativeSessionPort {
    val keyboardVisibility: StateFlow<NativeKeyboardVisibility>
    val isClosed: StateFlow<Boolean>
    suspend fun openKeyboard()
    suspend fun keyDown(keyCode: Int, metaState: Int = 0)
    suspend fun keyUp(keyCode: Int, metaState: Int = 0)
    suspend fun closeKeyboard()
    suspend fun prepareClipboardReceive()
    suspend fun sendClipboard(text: String)
    suspend fun receiveClipboard(timeoutMillis: Long = 15_000): String?
    fun close()
}

internal sealed interface NativePairOutcome {
    data class Approved(val session: NativeSessionPort) : NativePairOutcome
    data object Rejected : NativePairOutcome
    data object TimedOut : NativePairOutcome
    data object Unavailable : NativePairOutcome
}

internal interface NativePairPort {
    val comparisonCode: String
    val displayName: String
    suspend fun awaitApproval(timeoutMillis: Long = 70_000): NativePairOutcome
    fun cancel()
}

internal interface NativeLinkSource {
    suspend fun discover(timeoutMillis: Long = 1_500): List<NativeDevice>
    fun preparePair(device: NativeDevice): NativePairPort
}

private class RealNativeLinkSource(context: Application) : NativeLinkSource {
    private val client = NativeLinkClient(context)
    override suspend fun discover(timeoutMillis: Long) = client.discover(timeoutMillis)
    override fun preparePair(device: NativeDevice): NativePairPort = RealNativePairPort(client.preparePair(device))
}

private class RealNativePairPort(private val attempt: NativePairAttempt) : NativePairPort {
    override val comparisonCode get() = attempt.comparisonCode
    override val displayName get() = attempt.displayName
    override suspend fun awaitApproval(timeoutMillis: Long): NativePairOutcome = when (val result = attempt.awaitApproval(timeoutMillis)) {
        is NativePairResult.Approved -> NativePairOutcome.Approved(RealNativeSessionPort(result.session))
        NativePairResult.Rejected -> NativePairOutcome.Rejected
        NativePairResult.TimedOut -> NativePairOutcome.TimedOut
        NativePairResult.Unavailable -> NativePairOutcome.Unavailable
    }
    override fun cancel() = attempt.cancel()
}

private class RealNativeSessionPort(private val session: NativeLinkSession) : NativeSessionPort {
    override val keyboardVisibility get() = session.keyboardVisibility
    override val isClosed get() = session.isClosed
    override suspend fun openKeyboard() = session.openKeyboard()
    override suspend fun keyDown(keyCode: Int, metaState: Int) = session.keyDown(keyCode, metaState)
    override suspend fun keyUp(keyCode: Int, metaState: Int) = session.keyUp(keyCode, metaState)
    override suspend fun closeKeyboard() = session.closeKeyboard()
    override suspend fun prepareClipboardReceive() = session.prepareClipboardReceive()
    override suspend fun sendClipboard(text: String) = session.sendClipboardText(text)
    override suspend fun receiveClipboard(timeoutMillis: Long) = session.receiveClipboardText(timeoutMillis)
    override fun close() = session.close()
}

internal enum class NativeLinkOperation { NONE, DISCOVERING, PAIRING, SENDING_CLIPBOARD, RECEIVING_CLIPBOARD, OPENING_KEYBOARD, CLOSING_KEYBOARD, SENDING_KEYS }

internal data class NativeLinkUiState(
    val devices: List<NativeDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val operation: NativeLinkOperation = NativeLinkOperation.NONE,
    val comparisonCode: String? = null,
    val pairDisplayName: String? = null,
    val isPaired: Boolean = false,
    val pairedName: String = "",
    val clipboardDraft: String = "",
    val clipboardDraftEdited: Boolean = false,
    val receivedText: String? = null,
    val keyboardVisibility: NativeKeyboardVisibility = NativeKeyboardVisibility.UNKNOWN,
    val keyboardStarted: Boolean = false,
    val keyboardDraft: String = "",
    val keyboardHalted: Boolean = false,
    val message: String = "",
    val error: String = "",
)

internal interface PhoneClipboardPort {
    fun readPlainText(): String? = null
    fun writeText(text: String)
}

private class AndroidPhoneClipboardPort(context: Application) : PhoneClipboardPort {
    private val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    override fun readPlainText(): String? = manager.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.text?.toString()
    override fun writeText(text: String) {
        manager.setPrimaryClip(android.content.ClipData.newPlainText("Supernote text", text))
    }
}

internal class NativeLinkViewModel internal constructor(
    application: Application,
    private val source: NativeLinkSource = RealNativeLinkSource(application),
    private val phoneClipboard: PhoneClipboardPort = AndroidPhoneClipboardPort(application),
) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(NativeLinkUiState())
    val ui = _ui.asStateFlow()
    @Volatile private var active = false
    @Volatile private var generation = 0
    private var pairingJob: Job? = null
    private var operationJob: Job? = null
    private var draftFlushJob: Job? = null
    private var visibilityJob: Job? = null
    private var closedJob: Job? = null
    @Volatile private var compositionActive = false
    private var pairAttempt: NativePairPort? = null
    private var session: NativeSessionPort? = null
    private var needsDiscovery = true
    @Volatile private var keyWriteGeneration = 0
    private var keysInFlight: String? = null
    private var queuedPhoneBackspaces = 0
    private val keyWriteMutex = Mutex()
    private var clipboardReceiveGeneration = 0

    fun enterScreen() {
        active = true
        if ((needsDiscovery || _ui.value.devices.isEmpty()) && _ui.value.operation == NativeLinkOperation.NONE) discover()
    }

    fun discover() {
        if (!active || _ui.value.operation != NativeLinkOperation.NONE || _ui.value.isPaired) return
        val expected = ++generation
        needsDiscovery = false
        _ui.update { it.copy(operation = NativeLinkOperation.DISCOVERING, devices = emptyList(),
            selectedDeviceId = null, comparisonCode = null, message = "Searching on connected Wi-Fi…", error = "") }
        operationJob = viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { source.discover() }
                if (expected != generation || !active) return@launch
                _ui.update { it.copy(devices = found.distinctBy(NativeDevice::identifier),
                    selectedDeviceId = found.distinctBy(NativeDevice::identifier).singleOrNull()?.identifier,
                    operation = NativeLinkOperation.NONE,
                    message = if (found.isEmpty()) "No Supernote found. Check the same Wi-Fi and try again." else "Choose your Supernote, then pair.",
                    error = "") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) _ui.update { it.copy(operation = NativeLinkOperation.NONE,
                    error = "Could not discover a Supernote. Check Wi-Fi and try again.", message = "") }
            }
        }
    }

    fun selectDevice(device: NativeDevice) {
        if (_ui.value.operation != NativeLinkOperation.NONE || _ui.value.isPaired) return
        if (_ui.value.devices.any { it.identifier == device.identifier }) {
            _ui.update { it.copy(selectedDeviceId = device.identifier, comparisonCode = null, error = "", message = "") }
        }
    }

    fun pair() {
        val chosen = _ui.value.devices.firstOrNull { it.identifier == _ui.value.selectedDeviceId } ?: return
        if (!active || _ui.value.operation != NativeLinkOperation.NONE || _ui.value.isPaired) return
        val expected = ++generation
        _ui.update { it.copy(operation = NativeLinkOperation.PAIRING, comparisonCode = null, pairDisplayName = null,
            message = "Preparing a comparison code…", error = "") }
        pairingJob = viewModelScope.launch {
            var attempt: NativePairPort? = null
            try {
                attempt = withContext(Dispatchers.IO) { source.preparePair(chosen) }
                if (expected != generation || !active) {
                    attempt.cancel()
                    return@launch
                }
                pairAttempt = attempt
                _ui.update { it.copy(comparisonCode = attempt.comparisonCode, pairDisplayName = attempt.displayName,
                    message = "Match this code on your Supernote and approve there.") }
                when (val result = withContext(Dispatchers.IO) { attempt.awaitApproval() }) {
                    is NativePairOutcome.Approved -> {
                        if (expected != generation || !active) {
                            result.session.close()
                            return@launch
                        }
                        pairAttempt = null
                        session = result.session
                        closedJob?.cancel()
                        closedJob = viewModelScope.launch {
                            result.session.isClosed.collect { closed ->
                                if (closed) onSessionClosed(result.session, expected)
                            }
                        }
                        visibilityJob?.cancel()
                        visibilityJob = viewModelScope.launch {
                            result.session.keyboardVisibility.collect { visible ->
                                if (expected == generation && active) {
                                    _ui.update { it.copy(keyboardVisibility = visible) }
                                    if (visible == NativeKeyboardVisibility.VISIBLE) scheduleKeyboardDraftFlush()
                                    else draftFlushJob?.cancel()
                                }
                            }
                        }
                        _ui.update { it.copy(operation = NativeLinkOperation.NONE, isPaired = true,
                            pairedName = chosen.name, keyboardVisibility = result.session.keyboardVisibility.value,
                            comparisonCode = null, pairDisplayName = null,
                            message = "Connected to ${chosen.name}.", error = "") }
                    }
                    NativePairOutcome.Rejected -> finishPair(expected, "Supernote did not approve the request. Check its pairing prompt and try again.")
                    NativePairOutcome.TimedOut -> finishPair(expected, "Pairing timed out. Try again on both devices.")
                    NativePairOutcome.Unavailable -> finishPair(expected, "Supernote Linking is unavailable. Reopen it and try again.")
                }
            } catch (cancelled: CancellationException) {
                attempt?.cancel()
                throw cancelled
            } catch (_: Exception) {
                attempt?.cancel()
                if (expected == generation) finishPair(expected, "Could not pair. Confirm the code and try again.")
            }
        }
    }

    private fun finishPair(expected: Int, failure: String) {
        if (expected != generation) return
        pairAttempt = null
        _ui.update { it.copy(operation = NativeLinkOperation.NONE, isPaired = false,
            comparisonCode = null, pairDisplayName = null, message = "", error = failure) }
    }

    fun cancelPairing() {
        if (_ui.value.operation != NativeLinkOperation.PAIRING) return
        generation++
        pairAttempt?.cancel()
        pairAttempt = null
        pairingJob?.cancel()
        pairingJob = null
        _ui.update { it.copy(operation = NativeLinkOperation.NONE, comparisonCode = null, pairDisplayName = null,
            message = "Pairing cancelled.", error = "") }
    }

    fun enterClipboardPane(windowFocused: Boolean) {
        if (!windowFocused || !active || !_ui.value.isPaired || _ui.value.clipboardDraftEdited) return
        val text = try { phoneClipboard.readPlainText() } catch (_: Exception) {
            _ui.update { it.copy(error = "Could not read plain text from the phone clipboard.") }
            return
        } ?: ""
        val bytes = utf8ByteCount(text)
        if (bytes == null || bytes > MAX_CLIPBOARD_BYTES) {
            _ui.update { it.copy(error = "Phone clipboard text exceeds the 64 KiB limit or contains invalid text.") }
            return
        }
        if (active && _ui.value.isPaired && !_ui.value.clipboardDraftEdited) {
            _ui.update { it.copy(clipboardDraft = text, error = "") }
        }
    }

    fun updateClipboardDraft(value: String): Boolean {
        if (_ui.value.operation != NativeLinkOperation.NONE || !_ui.value.isPaired) return false
        val bytes = utf8ByteCount(value)
        if (bytes == null || bytes > MAX_CLIPBOARD_BYTES) {
            _ui.update { it.copy(error = if (bytes == null) "Text contains invalid Unicode and was not accepted."
                else "Text exceeds the 64 KiB limit. Nothing was sent.") }
            return false
        }
        _ui.update { it.copy(clipboardDraft = value, clipboardDraftEdited = true, error = "", message = "") }
        return true
    }

    fun clearClipboardDraft() {
        if (_ui.value.operation != NativeLinkOperation.NONE || !_ui.value.isPaired) return
        _ui.update { it.copy(clipboardDraft = "", clipboardDraftEdited = true, error = "", message = "") }
    }

    fun sendClipboard() = sendClipboard(_ui.value.clipboardDraft)

    fun sendClipboard(value: String) = withSession(NativeLinkOperation.SENDING_CLIPBOARD) { current, expected ->
        val byteCount = utf8ByteCount(value)
        if (value.isEmpty() || byteCount == null || byteCount > MAX_CLIPBOARD_BYTES) {
            throw IllegalArgumentException(when {
                value.isEmpty() -> "Enter text before sending."
                byteCount == null -> "Text contains invalid Unicode. Nothing was sent."
                else -> "Text exceeds the 64 KiB limit. Nothing was sent."
            })
        }
        current.sendClipboard(value)
        if (expected == generation && active) _ui.update {
            it.copy(message = "Sent. Check the Supernote.", error = "")
        }
    }

    fun receiveClipboard() {
        val expectedReceive = ++clipboardReceiveGeneration
        withSession(NativeLinkOperation.RECEIVING_CLIPBOARD) { current, expected ->
            current.prepareClipboardReceive()
            if (expected != generation || expectedReceive != clipboardReceiveGeneration || !active || session !== current) return@withSession
            if (expected == generation && expectedReceive == clipboardReceiveGeneration && active && session === current) _ui.update {
                it.copy(message = "Now copy text on your Supernote.", error = "")
            }
            val received = current.receiveClipboard()
            if (expected == generation && expectedReceive == clipboardReceiveGeneration && active && session === current && _ui.value.isPaired) {
                if (received != null) {
                    val bytes = utf8ByteCount(received)
                    if (bytes == null || bytes > MAX_CLIPBOARD_BYTES) {
                        throw IllegalArgumentException("Received text exceeds the 64 KiB limit or contains invalid text.")
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (expected == generation && expectedReceive == clipboardReceiveGeneration && active && session === current && _ui.value.isPaired) {
                        if (received != null) phoneClipboard.writeText(received)
                        _ui.update { it.copy(receivedText = received,
                            clipboardDraft = received ?: it.clipboardDraft,
                            clipboardDraftEdited = if (received != null) true else it.clipboardDraftEdited,
                            message = if (received == null) "No new device clipboard text arrived." else "Text received to the phone clipboard.", error = "") }
                    }
                }
            }
        }
    }

    fun cancelClipboardReceive() {
        if (_ui.value.operation != NativeLinkOperation.RECEIVING_CLIPBOARD) return
        clipboardReceiveGeneration++
        operationJob?.cancel()
        _ui.update { it.copy(operation = NativeLinkOperation.NONE,
            message = "Clipboard receive cancelled.", error = "") }
    }

    fun updateKeyboardDraft(value: String, committed: Boolean): Boolean {
        val state = _ui.value
        if (!state.isPaired || !state.keyboardStarted || state.keyboardHalted || state.operation !in setOf(
                NativeLinkOperation.NONE, NativeLinkOperation.SENDING_KEYS)) return false
        if (value.length > MAX_KEYBOARD_BUFFER_CHARS || (committed && value.any { !isSupportedAscii(it) })) {
            _ui.update { it.copy(error = if (value.length > MAX_KEYBOARD_BUFFER_CHARS)
                "Typing buffer is limited to $MAX_KEYBOARD_BUFFER_CHARS characters." else "Only printable ASCII is supported; unsupported text was rejected.") }
            return false
        }
        val inFlight = keysInFlight
        if (inFlight != null && !value.startsWith(inFlight)) {
            haltKeyboard("Delivery is uncertain because the typing buffer changed during send. Check the Supernote and restart typing.")
            return false
        }
        compositionActive = !committed
        _ui.update { it.copy(keyboardDraft = value, error = "", message = "") }
        draftFlushJob?.cancel()
        if (committed && value.isNotEmpty() && state.keyboardVisibility == NativeKeyboardVisibility.VISIBLE)
            scheduleKeyboardDraftFlush()
        return true
    }

    /** Returns true when an IME delete is routed to the active Supernote field instead of the phone buffer. */
    fun handlePhoneBackspace(buffer: String, selectionStart: Int, selectionEnd: Int,
                             hasComposition: Boolean, perform: Boolean, count: Int = 1): Boolean {
        if (hasComposition || selectionStart != selectionEnd) return false
        val state = _ui.value
        if (!active || !state.isPaired || !state.keyboardStarted || state.keyboardHalted ||
            state.keyboardVisibility != NativeKeyboardVisibility.VISIBLE) return false

        val inFlight = keysInFlight
        val deleteAfterFlight = state.operation == NativeLinkOperation.SENDING_KEYS &&
            state.keyboardDraft == buffer && buffer == inFlight && selectionStart == buffer.length
        val bufferEmpty = buffer.isEmpty() && state.keyboardDraft.isEmpty() &&
            (state.operation == NativeLinkOperation.NONE || deleteAfterFlight)
        if (deleteAfterFlight || bufferEmpty) {
            // Bound only remote operations; ordinary local edits remain delegated to EditText.
            if (count <= 0 || count > MAX_QUEUED_PHONE_BACKSPACES) {
                if (perform) _ui.update { it.copy(error = "That Backspace request is too large to send.") }
                return true
            }
            if (perform) {
                if (queuedPhoneBackspaces + count > MAX_QUEUED_PHONE_BACKSPACES) {
                    _ui.update { it.copy(error = "Too many Backspace presses while text is sending. Check the Supernote.") }
                } else if (state.operation == NativeLinkOperation.NONE) {
                    queuedPhoneBackspaces += count - 1
                    remoteBackspace()
                } else {
                    queuedPhoneBackspaces += count
                }
            }
            return true
        }
        return false
    }

    private fun scheduleKeyboardDraftFlush() {
        val state = _ui.value
        if (!state.isPaired || !active || !state.keyboardStarted || state.keyboardHalted ||
            state.keyboardVisibility != NativeKeyboardVisibility.VISIBLE || state.keyboardDraft.isEmpty() ||
            compositionActive || keysInFlight != null || state.operation != NativeLinkOperation.NONE) return
        draftFlushJob?.cancel()
        draftFlushJob = viewModelScope.launch {
            delay(KEY_DEBOUNCE_MS)
            flushKeyboardDraft()
        }
    }

    fun stopKeyboardSharing() {
        val current = session ?: return
        val state = _ui.value
        if (!active || !state.isPaired) return
        draftFlushJob?.cancel()
        queuedPhoneBackspaces = 0
        compositionActive = true
        if (!state.keyboardStarted && state.operation !in setOf(
                NativeLinkOperation.OPENING_KEYBOARD,
                NativeLinkOperation.SENDING_KEYS,
                NativeLinkOperation.CLOSING_KEYBOARD,
            )) return

        val expectedSession = generation
        val expectedKeyboard = ++keyWriteGeneration
        operationJob?.cancel()
        _ui.update { it.copy(operation = NativeLinkOperation.CLOSING_KEYBOARD, keyboardStarted = false,
            message = "Stopping keyboard sharing…", error = "") }
        operationJob = viewModelScope.launch {
            try {
                keyWriteMutex.withLock { withContext(Dispatchers.IO) { current.closeKeyboard() } }
                if (expectedSession == generation && expectedKeyboard == keyWriteGeneration && active && session === current) {
                    keysInFlight = null
                    _ui.update { it.copy(operation = NativeLinkOperation.NONE, keyboardStarted = false,
                        message = "Keyboard sharing stopped.", error = "") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expectedSession == generation && expectedKeyboard == keyWriteGeneration && active && session === current) {
                    keysInFlight = null
                    _ui.update { it.copy(operation = NativeLinkOperation.NONE, keyboardStarted = false,
                        error = "Keyboard sharing may still be active on Supernote. Check the tablet.", message = "") }
                }
            }
        }
    }

    fun startTyping() {
        if (!active || !_ui.value.isPaired || _ui.value.operation != NativeLinkOperation.NONE) return
        val clearOldDraft = !_ui.value.keyboardStarted
        val expectedKeyboard = ++keyWriteGeneration
        withSession(NativeLinkOperation.OPENING_KEYBOARD) { current, expected ->
            keyWriteMutex.withLock { current.openKeyboard() }
            if (expected == generation && expectedKeyboard == keyWriteGeneration && active) {
                compositionActive = false
                _ui.update { it.copy(keyboardStarted = true, keyboardHalted = false,
                    keyboardDraft = if (clearOldDraft) "" else it.keyboardDraft,
                    message = "Keyboard request sent. Check the Supernote screen.", error = "") }
            }
        }
    }

    fun doneTyping() {
        val current = session ?: return
        if (!active || !_ui.value.keyboardStarted || _ui.value.operation != NativeLinkOperation.NONE) return
        if (compositionActive) {
            _ui.update { it.copy(error = "Finish the current phone text composition before Done.") }
            return
        }
        if (!validateKeyboardChunk(_ui.value.keyboardDraft)) {
            _ui.update { it.copy(error = "Only printable ASCII can be sent. Nothing was sent.") }
            return
        }
        compositionActive = false
        draftFlushJob?.cancel()
        val snapshot = _ui.value.keyboardDraft
        val expectedKeyboard = keyWriteGeneration
        keysInFlight = snapshot
        _ui.update { it.copy(operation = NativeLinkOperation.CLOSING_KEYBOARD,
            message = "Finishing typing and closing the Supernote keyboard…", error = "") }
        val expected = generation
        operationJob = viewModelScope.launch {
            try {
                keyWriteMutex.withLock {
                    if (snapshot.isNotEmpty()) withContext(Dispatchers.IO) { sendAscii(current, snapshot, expected, expectedKeyboard) }
                    if (expected == generation && expectedKeyboard == keyWriteGeneration && active)
                        withContext(Dispatchers.IO) { current.closeKeyboard() }
                }
                if (expected == generation && expectedKeyboard == keyWriteGeneration && active) {
                    keyWriteGeneration++
                    _ui.update { it.copy(operation = NativeLinkOperation.NONE,
                    keyboardStarted = false,
                    keyboardDraft = if (it.keyboardDraft.startsWith(snapshot)) it.keyboardDraft.removePrefix(snapshot) else it.keyboardDraft,
                    message = "Done. Check the Supernote.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) {
                if (expected == generation && expectedKeyboard == keyWriteGeneration) _ui.update { it.copy(operation = NativeLinkOperation.NONE,
                    error = error.message ?: "Text was not sent.", message = "") }
            }
            catch (_: Exception) {
                if (expected == generation && expectedKeyboard == keyWriteGeneration) haltKeyboard("Keyboard close failed. Delivery state is uncertain; check the Supernote.")
            }
            finally { if (keysInFlight == snapshot) keysInFlight = null }
        }
    }

    fun remoteBackspace() = sendRemoteKey(KEYCODE_DEL, "Backspace sent.")
    fun remoteEnter() = sendRemoteKey(KEYCODE_ENTER, "Enter sent.")
    fun remoteSelectAll() = sendRemoteKey(KeyEvent.KEYCODE_A, "Select all sent.", META_CTRL_ON)
    fun remoteCut() = sendRemoteKey(KeyEvent.KEYCODE_X, "Cut sent.", META_CTRL_ON)
    fun remoteCopy() = sendRemoteKey(KeyEvent.KEYCODE_C, "Copy sent.", META_CTRL_ON)
    fun remotePaste() = sendRemoteKey(KeyEvent.KEYCODE_V, "Paste sent.", META_CTRL_ON)

    private fun sendRemoteKey(keyCode: Int, message: String, metaState: Int = 0) {
        val current = session ?: return
        if (!active || !_ui.value.keyboardStarted || _ui.value.keyboardHalted ||
            _ui.value.keyboardVisibility != NativeKeyboardVisibility.VISIBLE || _ui.value.operation != NativeLinkOperation.NONE) return
        if (compositionActive) {
            _ui.update { it.copy(error = "Finish the current phone text composition before sending a tablet key.") }
            return
        }
        val generationAtStart = generation
        val keyboardGenerationAtStart = keyWriteGeneration
        val toFlush = _ui.value.keyboardDraft
        if (!validateKeyboardChunk(toFlush)) {
            _ui.update { it.copy(error = "Only printable ASCII can be sent. Nothing was sent.") }
            return
        }
        compositionActive = false
        keysInFlight = toFlush
        _ui.update { it.copy(operation = NativeLinkOperation.SENDING_KEYS, error = "") }
        operationJob = viewModelScope.launch {
            try {
                draftFlushJob?.cancel()
                keyWriteMutex.withLock {
                    if (toFlush.isNotEmpty()) {
                        sendAscii(current, toFlush, generationAtStart, keyboardGenerationAtStart)
                        if (generationAtStart == generation && keyboardGenerationAtStart == keyWriteGeneration && active &&
                            !_ui.value.keyboardDraft.startsWith(toFlush)) {
                            haltKeyboard("The sent prefix changed before confirmation. Check the Supernote; typing was stopped.")
                            return@withLock
                        }
                    }
                    if (generationAtStart == generation && keyboardGenerationAtStart == keyWriteGeneration && active)
                        sendKey(current, keyCode, generationAtStart, keyboardGenerationAtStart, metaState)
                    drainQueuedPhoneBackspaces(current, generationAtStart, keyboardGenerationAtStart)
                }
                if (generationAtStart == generation && keyboardGenerationAtStart == keyWriteGeneration && active && !_ui.value.keyboardHalted) {
                    _ui.update { it.copy(operation = NativeLinkOperation.NONE,
                        keyboardDraft = if (_ui.value.keyboardDraft.startsWith(toFlush)) _ui.value.keyboardDraft.removePrefix(toFlush) else _ui.value.keyboardDraft,
                        message = "$message Check the Supernote.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                queuedPhoneBackspaces = 0
                if (generationAtStart == generation && keyboardGenerationAtStart == keyWriteGeneration && active)
                    haltKeyboard("Key delivery is uncertain. Check the Supernote and restart typing; no key replay was attempted.")
            }
            finally {
                if (keysInFlight == toFlush) keysInFlight = null
                if (generationAtStart == generation && keyboardGenerationAtStart == keyWriteGeneration && active && !_ui.value.keyboardHalted &&
                    _ui.value.keyboardDraft.isNotEmpty() && !compositionActive && _ui.value.operation == NativeLinkOperation.NONE) {
                    draftFlushJob?.cancel()
                    draftFlushJob = viewModelScope.launch { delay(KEY_DEBOUNCE_MS); flushKeyboardDraft() }
                }
            }
        }
    }

    private suspend fun flushKeyboardDraft() {
        val current = session ?: return
        val snapshot = _ui.value.keyboardDraft
        if (snapshot.isEmpty() || !_ui.value.keyboardStarted || _ui.value.keyboardHalted || !active ||
            _ui.value.keyboardVisibility != NativeKeyboardVisibility.VISIBLE ||
            _ui.value.operation != NativeLinkOperation.NONE || keysInFlight != null || compositionActive) return
        val expected = generation
        val expectedKeyboard = keyWriteGeneration
        keysInFlight = snapshot
        _ui.update { it.copy(operation = NativeLinkOperation.SENDING_KEYS, message = "Sending committed keys…", error = "") }
        operationJob = viewModelScope.launch {
            try {
                keyWriteMutex.withLock {
                    sendAscii(current, snapshot, expected, expectedKeyboard)
                    drainQueuedPhoneBackspaces(current, expected, expectedKeyboard)
                }
                if (expected != generation || expectedKeyboard != keyWriteGeneration || !active) return@launch
                if (!_ui.value.keyboardDraft.startsWith(snapshot)) {
                    haltKeyboard("The sent prefix changed before confirmation. Check the Supernote; typing was stopped.")
                } else {
                    _ui.update { it.copy(keyboardDraft = it.keyboardDraft.removePrefix(snapshot),
                        operation = NativeLinkOperation.NONE,
                        message = "Sent. Check the Supernote.", error = "") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                queuedPhoneBackspaces = 0
                if (expected == generation && expectedKeyboard == keyWriteGeneration && active)
                    haltKeyboard("Key delivery is uncertain. Check the Supernote and restart typing; no key replay was attempted.")
            }
            finally {
                if (keysInFlight == snapshot) keysInFlight = null
                if (expected == generation && expectedKeyboard == keyWriteGeneration && active && !_ui.value.keyboardHalted && _ui.value.keyboardDraft.isNotEmpty() &&
                    !compositionActive && _ui.value.operation == NativeLinkOperation.NONE) {
                    draftFlushJob?.cancel()
                    draftFlushJob = viewModelScope.launch { delay(KEY_DEBOUNCE_MS); flushKeyboardDraft() }
                }
            }
        }
    }

    private suspend fun sendAscii(current: NativeSessionPort, text: String, expected: Int, expectedKeyboard: Int) {
        require(validateKeyboardChunk(text)) { "Only printable ASCII can be sent. Nothing was sent." }
        for (char in text) {
            if (expected != generation || expectedKeyboard != keyWriteGeneration || !active) throw CancellationException("Typing session ended")
            if (current.keyboardVisibility.value != NativeKeyboardVisibility.VISIBLE) {
                throw IllegalStateException("Supernote keyboard is no longer visible")
            }
            val (code, meta) = encodeAscii(char)
            current.keyDown(code, meta)
            if (expected != generation || expectedKeyboard != keyWriteGeneration || !active) throw CancellationException("Typing session ended")
            current.keyUp(code, meta)
            delay(KEY_EVENT_GAP_MS)
        }
    }

    private suspend fun drainQueuedPhoneBackspaces(current: NativeSessionPort, expected: Int, expectedKeyboard: Int) {
        while (queuedPhoneBackspaces > 0) {
            if (expected != generation || expectedKeyboard != keyWriteGeneration || !active) {
                queuedPhoneBackspaces = 0
                throw CancellationException("Typing session ended")
            }
            queuedPhoneBackspaces--
            sendKey(current, KEYCODE_DEL, expected, expectedKeyboard)
            delay(KEY_EVENT_GAP_MS)
        }
    }

    private suspend fun sendKey(current: NativeSessionPort, keyCode: Int, expected: Int, expectedKeyboard: Int, metaState: Int = 0) {
        if (expected != generation || expectedKeyboard != keyWriteGeneration || !active ||
            current.keyboardVisibility.value != NativeKeyboardVisibility.VISIBLE) {
            throw IllegalStateException("Supernote keyboard is no longer available")
        }
        current.keyDown(keyCode, metaState)
        if (expected != generation || expectedKeyboard != keyWriteGeneration || !active) throw CancellationException("Typing session ended")
        current.keyUp(keyCode, metaState)
    }

    private fun haltKeyboard(reason: String) {
        generation++
        keyWriteGeneration++
        draftFlushJob?.cancel()
        if (_ui.value.operation == NativeLinkOperation.SENDING_KEYS) operationJob?.cancel()
        keysInFlight = null
        queuedPhoneBackspaces = 0
        val current = session
        session = null
        current?.close()
        visibilityJob?.cancel()
        closedJob?.cancel()
        needsDiscovery = true
        _ui.update { it.copy(operation = NativeLinkOperation.NONE, isPaired = false, pairedName = "",
            devices = emptyList(), selectedDeviceId = null,
            keyboardStarted = false, keyboardHalted = true, keyboardVisibility = NativeKeyboardVisibility.UNKNOWN,
            message = "", error = reason) }
    }

    private fun withSession(kind: NativeLinkOperation, action: suspend (NativeSessionPort, Int) -> Unit) {
        val current = session ?: return
        if (!active || !_ui.value.isPaired || _ui.value.operation != NativeLinkOperation.NONE) return
        val expected = generation
        _ui.update { it.copy(operation = kind, error = "") }
        operationJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action(current, expected) }
                if (expected == generation && active) _ui.update { it.copy(operation = NativeLinkOperation.NONE) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) {
                if (expected == generation) _ui.update { it.copy(operation = NativeLinkOperation.NONE,
                    error = error.message ?: "Text was not sent.", message = "") }
            }
            catch (_: Exception) {
                if (expected == generation) _ui.update { it.copy(operation = NativeLinkOperation.NONE,
                    error = "The local network request failed. Check the Supernote before retrying.", message = "") }
            }
        }
    }

    fun leaveScreen() {
        active = false
        dropSession("Disconnected when leaving Supernote Linking.")
    }

    fun onBackground() {
        if (active) {
            active = false
            dropSession("Disconnected while the app was in the background.")
        }
    }

    fun disconnect() {
        dropSession("Disconnected. Pair again before sending anything.")
    }

    private fun dropSession(message: String) {
        generation++
        clipboardReceiveGeneration++
        keyWriteGeneration++
        pairingJob?.cancel()
        operationJob?.cancel()
        draftFlushJob?.cancel()
        visibilityJob?.cancel()
        closedJob?.cancel()
        pairAttempt?.cancel()
        pairAttempt = null
        keysInFlight = null
        queuedPhoneBackspaces = 0
        val current = session
        session = null
        current?.close()
        needsDiscovery = true
        _ui.update { it.copy(isPaired = false, pairedName = "", devices = emptyList(), selectedDeviceId = null,
            comparisonCode = null, pairDisplayName = null,
            operation = NativeLinkOperation.NONE, receivedText = null, keyboardStarted = false,
            keyboardVisibility = NativeKeyboardVisibility.UNKNOWN, keyboardDraft = "", keyboardHalted = false,
            message = message, error = "") }
    }

    private fun onSessionClosed(closedSession: NativeSessionPort, expected: Int) {
        if (expected != generation || !active || session !== closedSession) return
        generation++
        clipboardReceiveGeneration++
        keyWriteGeneration++
        pairingJob?.cancel()
        operationJob?.cancel()
        draftFlushJob?.cancel()
        visibilityJob?.cancel()
        closedJob?.cancel()
        keysInFlight = null
        queuedPhoneBackspaces = 0
        session = null
        needsDiscovery = true
        _ui.update { it.copy(isPaired = false, pairedName = "", devices = emptyList(), selectedDeviceId = null,
            operation = NativeLinkOperation.NONE,
            keyboardStarted = false, keyboardHalted = true, keyboardVisibility = NativeKeyboardVisibility.UNKNOWN,
            message = "Connection closed. Check the Supernote; unfinished text was not replayed.",
            error = "Pair again before sending more text.") }
    }

    override fun onCleared() {
        active = false
        generation++
        keyWriteGeneration++
        pairingJob?.cancel()
        operationJob?.cancel()
        draftFlushJob?.cancel()
        visibilityJob?.cancel()
        closedJob?.cancel()
        pairAttempt?.cancel()
        queuedPhoneBackspaces = 0
        session?.close()
        super.onCleared()
    }

    private fun encodeAscii(char: Char): Pair<Int, Int> = when (char) {
        in 'a'..'z' -> (KEYCODE_A + char.code - 'a'.code) to 0
        in 'A'..'Z' -> (KEYCODE_A + char.code - 'A'.code) to META_SHIFT_ON
        in '1'..'9' -> (KEYCODE_1 + char.code - '1'.code) to 0
        '0' -> KEYCODE_0 to 0
        ' ' -> KEYCODE_SPACE to 0
        '.' -> KEYCODE_PERIOD to 0
        ',' -> KEYCODE_COMMA to 0
        '?' -> KEYCODE_SLASH to META_SHIFT_ON
        '/' -> KEYCODE_SLASH to 0
        '!' -> KEYCODE_1 to META_SHIFT_ON
        '@' -> KEYCODE_2 to META_SHIFT_ON
        '#' -> KEYCODE_3 to META_SHIFT_ON
        '$' -> KEYCODE_4 to META_SHIFT_ON
        '%' -> KEYCODE_5 to META_SHIFT_ON
        '^' -> KEYCODE_6 to META_SHIFT_ON
        '&' -> KEYCODE_7 to META_SHIFT_ON
        '*' -> KEYCODE_8 to META_SHIFT_ON
        '(' -> KEYCODE_9 to META_SHIFT_ON
        ')' -> KEYCODE_0 to META_SHIFT_ON
        '-' -> KEYCODE_MINUS to 0
        '_' -> KEYCODE_MINUS to META_SHIFT_ON
        '\'' -> KEYCODE_APOSTROPHE to 0
        '"' -> KEYCODE_APOSTROPHE to META_SHIFT_ON
        ':' -> KEYCODE_SEMICOLON to META_SHIFT_ON
        ';' -> KEYCODE_SEMICOLON to 0
        '`' -> KEYCODE_GRAVE to 0
        '~' -> KEYCODE_GRAVE to META_SHIFT_ON
        '=' -> KEYCODE_EQUALS to 0
        '+' -> KEYCODE_EQUALS to META_SHIFT_ON
        '[' -> KEYCODE_LEFT_BRACKET to 0
        '{' -> KEYCODE_LEFT_BRACKET to META_SHIFT_ON
        ']' -> KEYCODE_RIGHT_BRACKET to 0
        '}' -> KEYCODE_RIGHT_BRACKET to META_SHIFT_ON
        '\\' -> KEYCODE_BACKSLASH to 0
        '|' -> KEYCODE_BACKSLASH to META_SHIFT_ON
        '<' -> KEYCODE_COMMA to META_SHIFT_ON
        '>' -> KEYCODE_PERIOD to META_SHIFT_ON
        else -> throw IllegalArgumentException("Unsupported ASCII key")
    }

    private fun isSupportedAscii(char: Char): Boolean = when (char) {
        in ' '..'~' -> true
        else -> false
    }

    private fun validateKeyboardChunk(text: String) = text.all(::isSupportedAscii)

    companion object {
        const val MAX_KEYBOARD_BUFFER_CHARS = 256
        const val MAX_CLIPBOARD_BYTES = 65_536
        const val MAX_QUEUED_PHONE_BACKSPACES = 64
        private const val DISCOVERY_TIMEOUT_MS = 1_500L
        private const val KEY_DEBOUNCE_MS = 240L
        private const val KEY_EVENT_GAP_MS = 20L
        private const val KEYCODE_A = 29
        private const val KEYCODE_1 = 8
        private const val KEYCODE_2 = 9
        private const val KEYCODE_3 = 10
        private const val KEYCODE_4 = 11
        private const val KEYCODE_5 = 12
        private const val KEYCODE_6 = 13
        private const val KEYCODE_7 = 14
        private const val KEYCODE_8 = 15
        private const val KEYCODE_9 = 16
        private const val KEYCODE_0 = 7
        private const val KEYCODE_SPACE = 62
        private const val KEYCODE_PERIOD = 56
        private const val KEYCODE_COMMA = 55
        private const val KEYCODE_SLASH = 76
        private const val KEYCODE_MINUS = 69
        private const val KEYCODE_APOSTROPHE = 75
        private const val KEYCODE_SEMICOLON = 74
        private const val KEYCODE_GRAVE = 68
        private const val KEYCODE_EQUALS = 70
        private const val KEYCODE_LEFT_BRACKET = 71
        private const val KEYCODE_RIGHT_BRACKET = 72
        private const val KEYCODE_BACKSLASH = 73
        private const val KEYCODE_DEL = 67
        private const val KEYCODE_ENTER = 66
        private const val META_SHIFT_ON = 0x1
        private const val META_CTRL_ON = KeyEvent.META_CTRL_ON

        fun factory(application: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeLinkViewModel(application) as T
        }
    }
}

private fun utf8ByteCount(value: String): Int? {
    val encoder = StandardCharsets.UTF_8.newEncoder()
    if (!encoder.canEncode(value)) return null
    return value.toByteArray(StandardCharsets.UTF_8).size
}
