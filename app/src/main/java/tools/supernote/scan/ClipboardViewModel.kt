package tools.supernote.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import tools.superscanner.clipboard.ClipboardBridgeClient
import tools.superscanner.clipboard.ClipboardPairInfo
import tools.superscanner.clipboard.PairStatus

internal interface ClipboardTransport : AutoCloseable {
    fun approvalCode(info: ClipboardPairInfo): String
    suspend fun requestPair(info: ClipboardPairInfo): PairStatus
    suspend fun pairStatus(info: ClipboardPairInfo): PairStatus
    suspend fun receive(info: ClipboardPairInfo): String
    suspend fun send(info: ClipboardPairInfo, text: String)
    override fun close()
}

private class RealClipboardTransport(context: Application) : ClipboardTransport {
    private val client = ClipboardBridgeClient(context)
    override fun approvalCode(info: ClipboardPairInfo) = client.approvalCode(info)
    override suspend fun requestPair(info: ClipboardPairInfo) = client.requestPair(info)
    override suspend fun pairStatus(info: ClipboardPairInfo) = client.pairStatus(info)
    override suspend fun receive(info: ClipboardPairInfo) = client.receive(info)
    override suspend fun send(info: ClipboardPairInfo, text: String) = client.send(info, text)
    override fun close() = client.close()
}

internal enum class ClipboardOperation { NONE, PAIRING, SENDING, RECEIVING }

internal data class ClipboardUiState(
    val pairingCode: String = "",
    val approvalCode: String? = null,
    val pairingStatus: PairStatus = PairStatus.UNPAIRED,
    val isPaired: Boolean = false,
    val sendText: String = "",
    val receivedText: String = "",
    val operation: ClipboardOperation = ClipboardOperation.NONE,
    val message: String = "",
    val error: String = "",
)

internal class ClipboardViewModel internal constructor(
    application: Application,
    private val transportFactory: () -> ClipboardTransport = { RealClipboardTransport(application) },
) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(ClipboardUiState())
    val ui = _ui.asStateFlow()
    private var transport: ClipboardTransport = transportFactory()
    private var operationJob: Job? = null
    private var pairedInfo: ClipboardPairInfo? = null
    private var generation = 0

    fun updatePairingCode(value: String) {
        if (_ui.value.operation == ClipboardOperation.NONE) {
            _ui.update { it.copy(pairingCode = value.take(512), approvalCode = null, error = "", message = "") }
        }
    }

    fun acceptScannedPairingCode(code: String?) {
        if (code == null) {
            _ui.update { it.copy(error = "No pairing QR code was found in the current live mirror frame.") }
            return
        }
        try {
            ClipboardPairInfo.parse(code)
            updatePairingCode(code)
            _ui.update { it.copy(message = "Pairing code read. Tap Pair to prepare the comparison code.", error = "") }
        } catch (_: Exception) {
            _ui.update { it.copy(error = "The mirror QR code is not a valid Supernote helper pairing code.") }
        }
    }

    fun pair() {
        val state = _ui.value
        if (state.operation != ClipboardOperation.NONE) return
        val enteredPairingCode = state.pairingCode.trim()
        val info = try { ClipboardPairInfo.parse(enteredPairingCode) }
        catch (_: Exception) {
            _ui.update { it.copy(error = "Paste a valid helper pairing code first.", message = "") }
            return
        }
        val requestTransport = transport
        pairedInfo = info
        val expected = ++generation
        _ui.update { it.copy(operation = ClipboardOperation.PAIRING, isPaired = false,
            pairingStatus = PairStatus.UNPAIRED, approvalCode = null, sendText = "", receivedText = "",
            message = "Preparing the comparison code…", error = "") }
        operationJob = viewModelScope.launch {
            try {
                val approvalCode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requestTransport.approvalCode(info)
                }
                if (expected != generation) return@launch
                if (_ui.value.pairingCode.trim() != enteredPairingCode) {
                    _ui.update { it.copy(operation = ClipboardOperation.NONE, approvalCode = null,
                        message = "Pairing code changed. Tap Pair again to prepare a new comparison code.") }
                    return@launch
                }
                _ui.update { it.copy(approvalCode = approvalCode,
                    message = "Compare this code with the helper, then approve on the helper.") }
                val status = withTimeout(PAIR_TIMEOUT_MS) {
                    var current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { requestTransport.requestPair(info) }
                    while (current == PairStatus.PENDING) {
                        delay(PAIR_POLL_MS)
                        current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { requestTransport.pairStatus(info) }
                    }
                    current
                }
                if (expected != generation) return@launch
                when (status) {
                    PairStatus.APPROVED -> _ui.update { it.copy(operation = ClipboardOperation.NONE,
                        isPaired = true, pairingStatus = status, message = "Paired. Transfers require an explicit action.", error = "") }
                    PairStatus.REJECTED -> _ui.update { it.copy(operation = ClipboardOperation.NONE,
                        isPaired = false, pairingStatus = status, message = "", error = "The helper rejected this phone.") }
                    PairStatus.PENDING -> _ui.update { it.copy(operation = ClipboardOperation.NONE,
                        pairingStatus = status, message = "", error = "Pairing timed out. Keep the helper open and try again.") }
                    PairStatus.UNPAIRED -> _ui.update { it.copy(operation = ClipboardOperation.NONE,
                        pairingStatus = status, message = "", error = "The helper is no longer awaiting this phone. Start pairing again.") }
                }
            } catch (_: TimeoutCancellationException) {
                if (expected == generation) _ui.update { it.copy(operation = ClipboardOperation.NONE,
                    pairingStatus = PairStatus.PENDING, message = "",
                    error = "Pairing timed out. Keep the helper open and try again.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) _ui.update { it.copy(operation = ClipboardOperation.NONE,
                    isPaired = false, pairingStatus = PairStatus.UNPAIRED, message = "",
                    error = "Could not pair. Check the Wi-Fi connection and pairing code, then try again.") }
            }
        }
    }

    fun updateSendText(value: String) {
        if (_ui.value.operation == ClipboardOperation.NONE) _ui.update { it.copy(sendText = value, error = "", message = "") }
    }

    fun useRecognizedText(value: String) = updateSendText(value)

    fun pastePhoneClipboard(value: String?) {
        if (_ui.value.operation != ClipboardOperation.NONE) return
        if (value.isNullOrEmpty()) {
            _ui.update { it.copy(error = "The phone clipboard has no text to paste.", message = "") }
            return
        }
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
            _ui.update { it.copy(error = "Phone clipboard text is over the 64 KiB transfer limit. Nothing was truncated.", message = "") }
            return
        }
        _ui.update { it.copy(sendText = value, error = "", message = "Phone clipboard text loaded into the send box. Tap Send when ready.") }
    }

    fun send() = runTransfer(ClipboardOperation.SENDING) { info, client, expected ->
        val source = _ui.value.sendText
        if (source.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
            throw IllegalArgumentException("Text is over the 64 KiB transfer limit. Nothing was sent.")
        }
        client.send(info, source)
        if (expected == generation) _ui.update { it.copy(message = "Text sent to the helper.", error = "") }
    }

    fun receive() = runTransfer(ClipboardOperation.RECEIVING) { info, client, expected ->
        val text = client.receive(info)
        if (expected == generation) _ui.update { it.copy(receivedText = text,
            message = "Text received. Copy it only when ready.", error = "") }
    }

    fun showCopiedMessage() {
        if (_ui.value.receivedText.isNotEmpty()) _ui.update { it.copy(message = "Copied to the phone clipboard.", error = "") }
    }

    private fun runTransfer(kind: ClipboardOperation,
        action: suspend (ClipboardPairInfo, ClipboardTransport, Int) -> Unit) {
        val info = pairedInfo ?: return
        if (!_ui.value.isPaired || _ui.value.operation != ClipboardOperation.NONE) return
        val expected = ++generation
        _ui.update { it.copy(operation = kind, message = "", error = "") }
        operationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { action(info, transport, expected) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                if (expected == generation) _ui.update { it.copy(error = error.message ?: "Text is too large.") }
            } catch (_: Exception) {
                if (expected == generation) _ui.update { it.copy(error = "Transfer failed. The pairing or helper may have ended.") }
            } finally {
                if (expected == generation) _ui.update { it.copy(operation = ClipboardOperation.NONE) }
            }
        }
    }

    fun cancelOperation() {
        if (_ui.value.operation == ClipboardOperation.NONE) return
        generation++
        operationJob?.cancel()
        operationJob = null
        val old = transport
        transport = transportFactory()
        old.close()
        val wasPairing = _ui.value.operation == ClipboardOperation.PAIRING
        if (wasPairing) pairedInfo = null
        _ui.update { it.copy(operation = ClipboardOperation.NONE,
            approvalCode = if (wasPairing) null else it.approvalCode,
            isPaired = if (pairedInfo == null) false else it.isPaired,
            pairingStatus = if (pairedInfo == null) PairStatus.UNPAIRED else it.pairingStatus,
            message = "Operation cancelled.", error = "") }
    }

    fun forget() {
        generation++
        operationJob?.cancel()
        operationJob = null
        transport.close()
        transport = transportFactory()
        pairedInfo = null
        _ui.update { it.copy(pairingCode = "", approvalCode = null, pairingStatus = PairStatus.UNPAIRED,
            isPaired = false, sendText = "", receivedText = "", operation = ClipboardOperation.NONE,
            message = "Local pairing and text cleared. The phone clipboard was not changed.", error = "") }
    }

    override fun onCleared() {
        generation++
        operationJob?.cancel()
        transport.close()
        super.onCleared()
    }

    companion object {
        const val MAX_TEXT_BYTES = 65_536
        private const val PAIR_TIMEOUT_MS = 60_000L
        private const val PAIR_POLL_MS = 1_500L

        fun factory(application: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ClipboardViewModel(application) as T
        }
    }
}
