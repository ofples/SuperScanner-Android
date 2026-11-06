package tools.supernote.scan

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import tools.supernote.scan.network.MirrorClient

internal interface MirrorSource {
    suspend fun discover(manualHost: String? = null, cachedHost: String? = null): List<String>
    fun frames(host: String): kotlinx.coroutines.flow.Flow<MirrorFrame>
}

internal class RealMirrorSource(private val client: MirrorClient) : MirrorSource {
    override suspend fun discover(manualHost: String?, cachedHost: String?) = client.discover(manualHost, cachedHost)
    override fun frames(host: String) = client.frames(host)
}

internal interface RecognitionSource {
    fun hasApiKey(): Boolean
    fun setApiKey(value: String)
    fun subscriptionStatus(): String
    fun isChatGptConnected(): Boolean
    suspend fun beginChatGptSignIn(openBrowser: (Uri) -> Unit)
    fun cancelSignIn()
    suspend fun disconnectChatGpt()
    suspend fun listModels(provider: String): List<ModelChoice>
    suspend fun recognize(pages: List<CapturedPage>, settings: ScanSettings, onDelta: (String) -> Unit): String
}

internal class RealRecognitionSource(private val client: RecognitionClient) : RecognitionSource {
    override fun hasApiKey() = client.hasApiKey()
    override fun setApiKey(value: String) = client.setApiKey(value)
    override fun subscriptionStatus() = client.subscriptionStatus()
    override fun isChatGptConnected() = client.isChatGptConnected()
    override suspend fun beginChatGptSignIn(openBrowser: (Uri) -> Unit) = client.beginChatGptSignIn(openBrowser)
    override fun cancelSignIn() = client.cancelSignIn()
    override suspend fun disconnectChatGpt() = client.disconnectChatGpt()
    override suspend fun listModels(provider: String) = client.listModels(provider)
    override suspend fun recognize(pages: List<CapturedPage>, settings: ScanSettings, onDelta: (String) -> Unit) =
        client.recognize(pages, settings, onDelta)
}

internal enum class AppScreen { SCAN, RESULT, SETTINGS, PREVIEW, CLIPBOARD }

internal data class PageImage(val id: String, val bitmap: Bitmap)

internal data class AppUiState(
    val screen: AppScreen = AppScreen.SCAN,
    val pages: List<CapturedPage> = emptyList(),
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val selectedPreviewId: String? = null,
    val livePreview: Bitmap? = null,
    val isConnected: Boolean = false,
    val isDiscovering: Boolean = false,
    val connectionMessage: String = "Disconnected",
    val canSnap: Boolean = false,
    val isSavingCapture: Boolean = false,
    val currentPageCount: Int = 0,
    val captureFeedbackToken: Long = 0,
    val captureFeedbackPageId: String? = null,
    val resultText: String = "",
    val recognitionDraft: String = "",
    val isRecognizing: Boolean = false,
    val recognitionMessage: String = "",
    val recognitionError: String = "",
    val showRecognitionDisclosure: Boolean = false,
    val settings: ScanSettings = ScanSettings(),
    val settingsDraft: ScanSettings = ScanSettings(),
    val apiKeyDraft: String = "",
    val hasStoredApiKey: Boolean = false,
    val clearApiKey: Boolean = false,
    val chatGptConnected: Boolean = false,
    val isSigningIn: Boolean = false,
    val showPlanWelcome: Boolean = false,
    val subscription: String = "",
    val modelChoices: List<ModelChoice> = emptyList(),
    val discoveredHosts: List<String> = emptyList(),
    val showDeviceChooser: Boolean = false,
    val isLoadingModels: Boolean = false,
    val settingsMessage: String = "",
    val settingsError: String = "",
    val isSavingSettings: Boolean = false,
    val showDiscardDialog: Boolean = false,
    val showDeletePageId: String? = null,
    val isSavingGallery: Boolean = false,
    val gallerySaveMessage: String = "",
    val gallerySaveError: String = "",
    val notice: String = "",
    val storageError: String = "",
)

internal class AppViewModel internal constructor(
    application: Application,
    private val mirror: MirrorSource,
    private val recognition: RecognitionSource,
    private val store: SessionStore,
    autoConnect: Boolean = true,
    private val galleryExporter: GalleryExportSource = GalleryExporter(application),
    val isSample: Boolean = false,
    private val resumeKnownMirror: Boolean = false,
    private val recognitionDisclosure: RecognitionDisclosure? = null,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        RealMirrorSource(MirrorClient(application)),
        RealRecognitionSource(RecognitionClient(application)),
        SessionStore(application), autoConnect = false, resumeKnownMirror = true,
        recognitionDisclosure = RecognitionDisclosure(application))
    private val sessionWriteMutex = Mutex()
    private val captureMutex = Mutex()
    private val _ui = MutableStateFlow(AppUiState())
    val ui = _ui.asStateFlow()
    private val browserEvents = Channel<Uri>(Channel.BUFFERED)
    val browser = browserEvents.receiveAsFlow()
    private var currentFrame: MirrorFrame? = null
    private var lastFrameReceivedAt = 0L
    private var host: String? = null
    private var sessionId = UUID.randomUUID().toString()
    private var generation = 0
    private var mirrorJob: Job? = null
    private var discoveryJob: Job? = null
    private var mirrorRequested = false
    private var recognitionJob: Job? = null
    private var galleryExportJob: Job? = null
    private var signInJob: Job? = null
    private var storageReadFailed = false
    private var captureGeneration = 0
    private var clipboardReturnScreen = AppScreen.SCAN
    private var pendingRecognitionPageIds: List<String>? = null

    init {
        val saved = store.loadSession()
        val settings = store.loadSettings()
        host = saved.host
        sessionId = saved.sessionId
        val wasInterrupted = saved.interruptedRecognition
        storageReadFailed = saved.storageError != null
        _ui.value = AppUiState(
            pages = saved.pages,
            currentPageCount = saved.pages.size,
            resultText = saved.resultText,
            settings = settings,
            settingsDraft = settings,
            hasStoredApiKey = recognition.hasApiKey(),
            chatGptConnected = recognition.isChatGptConnected(),
            subscription = recognition.subscriptionStatus(),
            recognitionMessage = if (wasInterrupted) "Recognition stopped when the app closed. Retry when ready." else "",
            storageError = saved.storageError.orEmpty(),
            notice = saved.storageError.orEmpty(),
        )
        if (!storageReadFailed) persist()
        refreshThumbnails(saved.pages)
        viewModelScope.launch {
            while (true) {
                updateSnapAvailability()
                delay(750)
            }
        }
        mirrorRequested = autoConnect || (resumeKnownMirror && (host != null || settings.manualIp.isNotBlank()))
        if (autoConnect) resumeMirror()
    }

    fun openSettings() {
        _ui.update { it.copy(screen = AppScreen.SETTINGS, settingsDraft = it.settings,
            apiKeyDraft = "", clearApiKey = false, settingsMessage = "", settingsError = "") }
        refreshCredentialStatus()
    }

    fun openClipboard() {
        val current = _ui.value.screen
        if (current == AppScreen.CLIPBOARD || current == AppScreen.PREVIEW) return
        clipboardReturnScreen = current
        _ui.update { it.copy(screen = AppScreen.CLIPBOARD) }
    }

    fun closeClipboard() {
        _ui.update { it.copy(screen = clipboardReturnScreen).also { clipboardReturnScreen = AppScreen.SCAN } }
    }

    fun decodeClipboardPairingCode(onResult: (String?) -> Unit) {
        val frame = currentFrame?.takeIf { _ui.value.isConnected &&
            System.currentTimeMillis() - lastFrameReceivedAt <= FRAME_FRESH_MS }
        if (frame == null) {
            onResult(null)
            return
        }
        val bytes = frame.bytes.copyOf()
        viewModelScope.launch(Dispatchers.IO) {
            val code = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                    MultiFormatReader().apply {
                        setHints(mapOf(DecodeHintType.TRY_HARDER to true))
                    }.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                } finally { bitmap.recycle() }
            }.getOrNull()
            withContext(Dispatchers.Main) { onResult(code) }
        }
    }

    fun cancelSettings() {
        if (_ui.value.isSavingSettings) return
        _ui.update { it.copy(screen = AppScreen.SCAN, settingsDraft = it.settings,
            apiKeyDraft = "", clearApiKey = false, settingsError = "", settingsMessage = "") }
    }

    fun updateSettingsDraft(transform: (ScanSettings) -> ScanSettings) {
        if (_ui.value.isSavingSettings) return
        _ui.update {
            val changed = transform(it.settingsDraft)
            it.copy(settingsDraft = changed,
                modelChoices = if (changed.provider != it.settingsDraft.provider) emptyList() else it.modelChoices,
                settingsError = "", settingsMessage = "")
        }
    }

    fun updateRecognitionPromptDraft(value: String) {
        if (_ui.value.isSavingSettings) return
        if (value.length > MAX_RECOGNITION_PROMPT_CHARS) {
            _ui.update { it.copy(settingsError = "Recognition prompt is limited to $MAX_RECOGNITION_PROMPT_CHARS characters. Nothing was cut off.") }
            return
        }
        updateSettingsDraft { it.copy(prompt = value) }
    }

    fun restoreDefaultRecognitionPrompt() {
        updateSettingsDraft { it.copy(prompt = DEFAULT_RECOGNITION_PROMPT) }
    }

    fun updateApiKeyDraft(value: String) {
        if (!_ui.value.isSavingSettings) _ui.update { it.copy(apiKeyDraft = value, clearApiKey = false) }
    }
    fun requestClearApiKey() { if (!_ui.value.isSavingSettings) _ui.update { it.copy(clearApiKey = true, apiKeyDraft = "") } }
    fun cancelClearApiKey() { if (!_ui.value.isSavingSettings) _ui.update { it.copy(clearApiKey = false) } }

    fun saveSettings() {
        val state = _ui.value
        if (state.isSavingSettings) return
        val candidateKey = state.apiKeyDraft
        if (candidateKey.isNotBlank() &&
            (candidateKey != candidateKey.trim() || candidateKey.length > MAX_API_KEY_LENGTH || !candidateKey.startsWith("sk-"))) {
            _ui.update { it.copy(settingsError = "Enter a valid OpenAI API key, or clear the field to keep the saved key.") }
            return
        }
        if (state.settingsDraft.prompt.length > MAX_RECOGNITION_PROMPT_CHARS) {
            _ui.update { it.copy(settingsError = "Recognition prompt is limited to $MAX_RECOGNITION_PROMPT_CHARS characters. Nothing was cut off.") }
            return
        }
        val draft = state.settingsDraft.copy(
            provider = if (state.settingsDraft.provider == "chatgpt") "chatgpt" else "api",
            model = state.settingsDraft.model.trim().ifBlank { "gpt-6.1-sol" },
            manualIp = state.settingsDraft.manualIp.trim(),
            prompt = state.settingsDraft.prompt.takeIf { it.isNotBlank() } ?: DEFAULT_RECOGNITION_PROMPT,
        )
        _ui.update { it.copy(isSavingSettings = true, settingsError = "", settingsMessage = "Saving settings…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.saveSettings(draft)
                    try {
                        when {
                            state.clearApiKey -> recognition.setApiKey("")
                            candidateKey.isNotBlank() -> recognition.setApiKey(candidateKey)
                        }
                    } catch (error: Throwable) {
                        runCatching { store.saveSettings(state.settings) }
                        throw error
                    }
                }
            }.onSuccess {
                _ui.update { it.copy(settings = draft, settingsDraft = draft, apiKeyDraft = "",
                    clearApiKey = false, hasStoredApiKey = recognition.hasApiKey(),
                    isSavingSettings = false, screen = AppScreen.SCAN, settingsMessage = "Settings saved.", settingsError = "") }
                persist()
            }.onFailure { error ->
                _ui.update { it.copy(isSavingSettings = false, settingsMessage = "",
                    settingsError = error.message ?: "Could not save settings.") }
            }
        }
    }

    fun loadModels() {
        if (_ui.value.isLoadingModels) return
        if (_ui.value.settingsDraft.provider == "api" && _ui.value.apiKeyDraft.isNotBlank()) {
            _ui.update { it.copy(settingsMessage = "Save the API key first, then load account models.") }
            return
        }
        val provider = _ui.value.settingsDraft.provider
        _ui.update { it.copy(isLoadingModels = true, settingsError = "", settingsMessage = "Loading models…") }
        viewModelScope.launch {
            runCatching { recognition.listModels(provider) }
                .onSuccess { models -> _ui.update { current ->
                    if (current.settingsDraft.provider != provider) current.copy(isLoadingModels = false)
                    else current.copy(modelChoices = models, isLoadingModels = false,
                        settingsMessage = if (models.isEmpty()) "No models were returned." else "Loaded ${models.size} account models.")
                } }
                .onFailure { error -> _ui.update { current ->
                    if (current.settingsDraft.provider != provider) current.copy(isLoadingModels = false)
                    else current.copy(isLoadingModels = false, settingsMessage = "",
                        settingsError = error.message ?: "Could not load models.")
                } }
        }
    }

    fun signInToChatGpt() {
        if (_ui.value.isSigningIn) return
        _ui.update { it.copy(isSigningIn = true, settingsMessage = "Opening sign-in…", settingsError = "") }
        signInJob = viewModelScope.launch {
            try {
                recognition.beginChatGptSignIn { uri -> browserEvents.trySend(uri) }
                refreshCredentialStatus()
                _ui.update { it.copy(isSigningIn = false, settingsMessage = "ChatGPT sign-in complete.",
                    showPlanWelcome = it.chatGptConnected && recognitionDisclosure?.planWelcomeSeen() == false) }
            } catch (cancelled: CancellationException) {
                _ui.update { it.copy(isSigningIn = false) }
                throw cancelled
            } catch (error: Throwable) {
                _ui.update { it.copy(isSigningIn = false, settingsMessage = "",
                    settingsError = error.message ?: "Sign-in failed.") }
            }
        }
    }

    fun cancelChatGptSignIn() {
        recognition.cancelSignIn()
        signInJob?.cancel()
        _ui.update { it.copy(isSigningIn = false, settingsMessage = "Sign-in cancelled.") }
    }

    fun dismissPlanWelcome() {
        recognitionDisclosure?.dismissPlanWelcome()
        _ui.update { it.copy(showPlanWelcome = false) }
    }

    fun failBrowserLaunch(error: Throwable) {
        recognition.cancelSignIn()
        signInJob?.cancel()
        _ui.update { it.copy(isSigningIn = false, settingsMessage = "",
            settingsError = error.message ?: "Could not open the sign-in page.") }
    }

    fun disconnectChatGpt() {
        viewModelScope.launch {
            runCatching { recognition.disconnectChatGpt() }
                .onSuccess { refreshCredentialStatus(); _ui.update { it.copy(settingsMessage = "ChatGPT disconnected.") } }
                .onFailure { error -> _ui.update { it.copy(settingsError = error.message ?: "Could not disconnect ChatGPT.") } }
        }
    }

    private fun refreshCredentialStatus() {
        _ui.update { it.copy(hasStoredApiKey = recognition.hasApiKey(),
            chatGptConnected = recognition.isChatGptConnected(), subscription = recognition.subscriptionStatus()) }
    }

    fun reconnect() {
        if (_ui.value.isDiscovering) return
        mirrorRequested = true
        mirrorJob?.cancel()
        currentFrame = null
        lastFrameReceivedAt = 0L
        _ui.update { it.copy(isDiscovering = true, isConnected = false, canSnap = false, livePreview = null,
            connectionMessage = if (isSample) "Opening sample…" else "Looking for Supernote…", notice = "") }
        discoveryJob = viewModelScope.launch {
            try {
                val addresses = mirror.discover(_ui.value.settings.manualIp.takeIf(String::isNotBlank), host)
                currentCoroutineContext().ensureActive()
                val hosts = addresses.distinct()
                val selected = hosts.singleOrNull()
                if (hosts.isEmpty()) {
                    _ui.update { it.copy(isDiscovering = false, isConnected = false,
                        connectionMessage = "Supernote not found. Check Screen Mirroring and try again.") }
                } else if (selected == null) {
                    _ui.update { it.copy(isDiscovering = false, isConnected = false,
                        discoveredHosts = hosts, showDeviceChooser = true,
                        connectionMessage = "Choose a Supernote to connect.") }
                } else {
                    connectTo(selected)
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                _ui.update { it.copy(isDiscovering = false, isConnected = false,
                    connectionMessage = "Search timed out. Check Screen Mirroring and try again.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.update { it.copy(isDiscovering = false, isConnected = false,
                    connectionMessage = error.message ?: "Could not connect to Supernote.") }
            }
        }
    }

    /** Stop network and release the decoded mirror when the activity is no longer visible. */
    fun pauseMirror() {
        discoveryJob?.cancel()
        mirrorJob?.cancel()
        currentFrame = null
        lastFrameReceivedAt = 0L
        _ui.update { it.copy(isDiscovering = false, isConnected = false, canSnap = false, livePreview = null,
            showDeviceChooser = false, discoveredHosts = emptyList(),
            connectionMessage = if (mirrorRequested) "Mirror paused" else it.connectionMessage) }
    }

    fun resumeMirror() {
        if (mirrorRequested && discoveryJob?.isActive != true && mirrorJob?.isActive != true) reconnect()
    }

    fun advanceSamplePage() {
        val sample = mirror as? SampleMirrorSource ?: return
        if (!_ui.value.canSnap) return
        sample.nextPage()
        currentFrame = null
        lastFrameReceivedAt = 0L
        _ui.update { it.copy(canSnap = false, connectionMessage = "Loading sample page…") }
    }

    fun chooseDiscoveredHost(value: String) {
        if (value !in _ui.value.discoveredHosts) return
        _ui.update { it.copy(showDeviceChooser = false) }
        connectTo(value)
    }

    fun dismissDeviceChooser() { _ui.update { it.copy(showDeviceChooser = false) } }

    private fun connectTo(selected: String) {
        host = selected
        persist()
        _ui.update { it.copy(isDiscovering = false, isConnected = true,
            connectionMessage = if (isSample) "Sample mirror" else "Waiting for the first frame…") }
        collectFrames(selected)
    }

    private fun collectFrames(selected: String) {
        mirrorJob = viewModelScope.launch {
            var endMessage = "Mirror stream ended. Reconnect to continue."
            try {
                var previousBytes: ByteArray? = null
                mirror.frames(selected).collect { frame ->
                    // Sample heartbeat frames reuse their encoded image. Live frames are independent.
                    val preview = if (frame.bytes === previousBytes) _ui.value.livePreview else
                        withContext(Dispatchers.IO) { decodeBytes(frame.bytes, 1280) }
                    currentCoroutineContext().ensureActive()
                    if (mirror is SampleMirrorSource && !mirror.isCurrent(frame)) return@collect
                    if (preview != null) {
                        previousBytes = frame.bytes
                        currentFrame = frame
                        lastFrameReceivedAt = System.currentTimeMillis()
                        _ui.update { it.copy(livePreview = preview, isConnected = true,
                            connectionMessage = if (isSample) "Sample mirror" else "Live · $selected") }
                        updateSnapAvailability()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                endMessage = error.message ?: "Mirror connection lost. Reconnect to continue."
            }
            currentFrame = null
            lastFrameReceivedAt = 0L
            _ui.update { it.copy(isConnected = false, canSnap = false, livePreview = null,
                connectionMessage = endMessage) }
        }
    }

    private fun updateSnapAvailability() {
        val fresh = currentFrame != null && System.currentTimeMillis() - lastFrameReceivedAt <= FRAME_FRESH_MS
        _ui.update { it.copy(canSnap = fresh && it.isConnected && !it.isDiscovering &&
            !it.isSavingCapture && !it.isSavingGallery && !it.isRecognizing && !storageReadFailed &&
            it.storageError.isBlank() &&
            it.pages.size < SessionStore.MAX_PAGES) }
    }

    fun snap() = captureCurrentFrame(recognizeAfterSave = false)

    /** Captures one fresh frame from an empty scan, then recognizes only after its session commit. */
    fun snapAndRecognize() = captureCurrentFrame(recognizeAfterSave = true)

    private fun prepareRecognition(): Boolean {
        val disclosure = recognitionDisclosure ?: return true
        val state = _ui.value
        if (state.isRecognizing || state.isSavingCapture || state.isSavingGallery || state.storageError.isNotBlank()) return false
        if (state.pages.isEmpty()) return false
        val ready = if (state.settings.provider == "chatgpt") state.chatGptConnected else state.hasStoredApiKey
        if (!ready) {
            openSettings()
            _ui.update { it.copy(settingsMessage = if (state.settings.provider == "chatgpt")
                "Connect your ChatGPT plan to recognize pages." else "Add your OpenAI API key to recognize pages.") }
            return false
        }
        if (disclosure.acknowledged()) return true
        pendingRecognitionPageIds = state.pages.map { it.id }
        _ui.update { it.copy(showRecognitionDisclosure = true) }
        return false
    }

    fun dismissRecognitionDisclosure() {
        pendingRecognitionPageIds = null
        _ui.update { it.copy(showRecognitionDisclosure = false) }
    }

    fun confirmRecognitionDisclosure() {
        val pageIds = pendingRecognitionPageIds ?: return
        val disclosure = recognitionDisclosure ?: return
        dismissRecognitionDisclosure()
        if (_ui.value.pages.map { it.id } != pageIds) return
        disclosure.acknowledge()
        recognize()
    }

    private fun captureCurrentFrame(recognizeAfterSave: Boolean) {
        if (storageReadFailed) {
            _ui.update { it.copy(notice = "Saved scan state could not be read. Start a new scan to use storage again.") }
            return
        }
        val snapshot = _ui.value
        val frame = currentFrame
        if (snapshot.isSavingGallery) {
            _ui.update { it.copy(notice = "Wait for the Gallery save to finish before capturing another page.") }
            return
        }
        if (snapshot.storageError.isNotBlank()) {
            _ui.update { it.copy(notice = "Resolve the saved scan storage issue before capturing.") }
            return
        }
        if (recognizeAfterSave && snapshot.pages.isNotEmpty()) {
            _ui.update { it.copy(notice = "Clear the current pages before using Snap & recognize.") }
            return
        }
        if (!snapshot.isConnected || snapshot.isDiscovering || snapshot.isRecognizing || snapshot.isSavingCapture ||
            recognitionJob?.isActive == true ||
            frame == null || System.currentTimeMillis() - lastFrameReceivedAt > FRAME_FRESH_MS) {
            _ui.update { it.copy(notice = "Wait for a fresh mirror frame before snapping.") }
            return
        }
        if (snapshot.pages.size >= SessionStore.MAX_PAGES) {
            _ui.update { it.copy(notice = "This scan has reached ${SessionStore.MAX_PAGES} pages.") }
            return
        }
        _ui.update { it.copy(isSavingCapture = true, canSnap = false, notice = "Saving page…") }
        val expectedGeneration = captureGeneration
        val expectedSession = sessionId
        val expectedFrame = frame
        val expectedPages = snapshot.pages.toList()
        var savedPages: List<CapturedPage>? = null
        viewModelScope.launch {
            try {
                val pages = captureMutex.withLock {
                    val page = withContext(Dispatchers.IO) { store.savePage(expectedFrame, expectedPages) }
                    if (expectedGeneration != captureGeneration || expectedSession != sessionId || storageReadFailed) {
                        withContext(Dispatchers.IO) { store.deletePages(listOf(page)) }
                        return@withLock null
                    }
                    val candidate = expectedPages + page
                    sessionWriteMutex.withLock {
                        withContext(Dispatchers.IO) {
                            store.saveSession(candidate, host, _ui.value.resultText,
                                expectedSession, recognitionRunning = false)
                        }
                        if (expectedGeneration == captureGeneration && expectedSession == sessionId) {
                            _ui.update { it.copy(pages = candidate, currentPageCount = candidate.size,
                                captureFeedbackToken = it.captureFeedbackToken + 1,
                                captureFeedbackPageId = page.id, isSavingCapture = false, notice = "") }
                        }
                    }
                    candidate
                }
                savedPages = pages
                if (pages != null && expectedGeneration == captureGeneration && expectedSession == sessionId) {
                    refreshThumbnails(pages)
                } else if (expectedGeneration == captureGeneration && expectedSession == sessionId) {
                    _ui.update { it.copy(isSavingCapture = false, notice = "The scan changed before this page was saved.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (expectedGeneration == captureGeneration && expectedSession == sessionId) {
                    _ui.update { it.copy(isSavingCapture = false, canSnap = false,
                        storageError = "Could not save scan state: ${error.message ?: "storage error"}",
                        notice = "Could not save scan state. Captured originals are preserved.") }
                }
            }
            updateSnapAvailability()
            val committed = savedPages
            if (recognizeAfterSave && committed != null && expectedGeneration == captureGeneration &&
                expectedSession == sessionId && _ui.value.pages.any { it.id == committed.lastOrNull()?.id }) {
                recognize()
            }
        }
    }

    fun openPage(pageId: String) {
        if (_ui.value.isSavingGallery) return
        _ui.update { it.copy(selectedPreviewId = pageId, screen = AppScreen.PREVIEW,
            gallerySaveMessage = "", gallerySaveError = "") }
    }
    fun closePreview() { _ui.update { it.copy(screen = AppScreen.SCAN, selectedPreviewId = null) } }
    fun savePageToGallery(pageId: String) {
        val state = _ui.value
        if (state.isSavingGallery || state.isSavingCapture || state.isRecognizing) return
        val page = state.pages.firstOrNull { it.id == pageId } ?: return
        val requestSession = sessionId
        _ui.update { it.copy(isSavingGallery = true, gallerySaveMessage = "Saving original image to Gallery…",
            gallerySaveError = "") }
        galleryExportJob = viewModelScope.launch {
            try {
                val exported = galleryExporter.export(page)
                if (requestSession == sessionId && _ui.value.pages.any { it.id == page.id }) {
                    _ui.update { it.copy(isSavingGallery = false,
                        gallerySaveMessage = "Saved a copy to ${exported.relativePath}.", gallerySaveError = "",
                        notice = "Saved ${exported.displayName} to ${exported.relativePath}.") }
                }
            } catch (cancelled: CancellationException) {
                if (requestSession == sessionId) _ui.update { it.copy(isSavingGallery = false,
                    gallerySaveMessage = "Gallery save cancelled.", gallerySaveError = "",
                    notice = "Gallery save cancelled.") }
                throw cancelled
            } catch (error: Throwable) {
                if (requestSession == sessionId) {
                    val message = error.message ?: "Could not save the image to Gallery."
                    _ui.update { it.copy(isSavingGallery = false,
                        gallerySaveMessage = "", gallerySaveError = message, notice = message) }
                }
            }
        }
    }
    fun cancelGallerySave() { galleryExportJob?.cancel() }
    fun galleryPermissionDenied(pageId: String) {
        if (_ui.value.selectedPreviewId != pageId) return
        val message = "Storage permission was denied. Allow it in Android Settings > Apps > Supernote Scan > Permissions, then try again."
        _ui.update { it.copy(gallerySaveMessage = "", gallerySaveError = message, notice = message) }
    }
    fun askDeletePage(pageId: String) {
        if (!_ui.value.isRecognizing && !_ui.value.isSavingCapture && !_ui.value.isSavingGallery)
            _ui.update { it.copy(showDeletePageId = pageId) }
    }
    fun cancelDeletePage() { _ui.update { it.copy(showDeletePageId = null) } }
    fun confirmDeletePage() {
        if (_ui.value.isRecognizing || _ui.value.isSavingCapture || _ui.value.isSavingGallery || storageReadFailed || _ui.value.storageError.isNotBlank()) return
        val id = _ui.value.showDeletePageId ?: return
        val removed = _ui.value.pages.firstOrNull { it.id == id } ?: return
        val pages = _ui.value.pages.filterNot { it.id == id }
        val expectedSession = sessionId
        _ui.update { it.copy(isSavingCapture = true, notice = "Removing page…") }
        viewModelScope.launch {
            try {
                captureMutex.withLock {
                    if (expectedSession != sessionId) return@withLock
                    sessionWriteMutex.withLock {
                        withContext(Dispatchers.IO) {
                            store.saveSession(pages, host, _ui.value.resultText, expectedSession,
                                recognitionRunning = false)
                        }
                        _ui.update { it.copy(pages = pages, currentPageCount = pages.size,
                            thumbnails = it.thumbnails - id, showDeletePageId = null,
                            selectedPreviewId = null, screen = AppScreen.SCAN,
                            isSavingCapture = false, notice = "Page removed.") }
                        withContext(Dispatchers.IO) { store.deletePages(listOf(removed)) }
                    }
                }
            } catch (error: Throwable) {
                _ui.update { it.copy(isSavingCapture = false, storageError = "Could not remove page: ${error.message ?: "storage error"}",
                    notice = "Page was kept because saved scan state could not be updated.") }
            }
        }
    }

    fun movePage(pageId: String, offset: Int) {
        val pageIds = _ui.value.pages.map { it.id }.toMutableList()
        val index = pageIds.indexOf(pageId)
        val target = index + offset
        if (index < 0 || target !in pageIds.indices) return
        val page = pageIds.removeAt(index)
        pageIds.add(target, page)
        reorderPages(pageIds)
    }

    /** Persist one completed drag as a validated, atomic page-order change. */
    fun reorderPages(orderedIds: List<String>): Boolean {
        val before = _ui.value
        val currentIds = before.pages.map { it.id }
        if (before.isRecognizing || before.isSavingCapture || before.isSavingGallery ||
            storageReadFailed || before.storageError.isNotBlank() ||
            orderedIds.size != currentIds.size || orderedIds.toSet() != currentIds.toSet() ||
            orderedIds == currentIds) return false
        val expectedSession = sessionId
        val expectedCaptureGeneration = captureGeneration
        _ui.update { it.copy(isSavingCapture = true, canSnap = false, notice = "Saving page order…") }
        viewModelScope.launch {
            try {
                val saved = captureMutex.withLock {
                    if (expectedSession != sessionId || expectedCaptureGeneration != captureGeneration) return@withLock false
                    val latest = _ui.value
                    val pagesById = latest.pages.associateBy { it.id }
                    if (latest.isRecognizing || latest.isSavingGallery || latest.storageError.isNotBlank() ||
                        orderedIds.size != latest.pages.size || orderedIds.toSet() != pagesById.keys) return@withLock false
                    val orderedPages = orderedIds.map { pagesById.getValue(it) }
                    sessionWriteMutex.withLock {
                        withContext(Dispatchers.IO) {
                            store.saveSession(orderedPages, host, latest.resultText, expectedSession,
                                recognitionRunning = latest.isRecognizing)
                        }
                    }
                    if (expectedSession == sessionId && expectedCaptureGeneration == captureGeneration) {
                        _ui.update { it.copy(pages = orderedPages, currentPageCount = orderedPages.size,
                            isSavingCapture = false, notice = "Page order saved.") }
                        true
                    } else false
                }
                if (!saved && expectedSession == sessionId) {
                    _ui.update { it.copy(isSavingCapture = false, notice = "The scan changed before page order was saved.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (expectedSession == sessionId && expectedCaptureGeneration == captureGeneration) {
                    _ui.update { it.copy(isSavingCapture = false, storageError = "Could not save page order: ${error.message ?: "storage error"}",
                        notice = "Page order was not changed because scan state could not be saved.") }
                }
            } finally {
                if (expectedSession == sessionId) updateSnapAvailability()
            }
        }
        return true
    }

    fun openResult() {
        if (storageReadFailed || _ui.value.storageError.isNotBlank()) {
            _ui.update { it.copy(notice = "Saved scan storage needs a fresh session before recognition.") }
            return
        }
        recognize()
    }
    fun showExistingResult() { _ui.update { it.copy(screen = AppScreen.RESULT, recognitionError = "") } }
    fun openScanFromResult() { _ui.update { it.copy(screen = AppScreen.SCAN) } }
    fun updateResultText(value: String) {
        if (_ui.value.isRecognizing || _ui.value.isSavingCapture || storageReadFailed ||
            _ui.value.storageError.isNotBlank()) return
        _ui.update { it.copy(resultText = value) }
        persist()
    }

    fun recognize() {
        val state = _ui.value
        if (recognitionJob?.isActive == true || state.isSavingCapture || state.isSavingGallery ||
            state.pages.isEmpty() || storageReadFailed || state.storageError.isNotBlank()) return
        if (!prepareRecognition()) return
        val requestPages = state.pages.toList()
        val requestSettings = state.settings.copy()
        val requestSession = sessionId
        val requestGeneration = generation
        var resultCommitted = false
        _ui.update { it.copy(screen = AppScreen.RESULT, isRecognizing = true,
            recognitionDraft = "", recognitionError = "", recognitionMessage = "Recognizing pages…") }
        recognitionJob = viewModelScope.launch {
            try {
                sessionWriteMutex.withLock {
                    withContext(Dispatchers.IO) {
                        store.saveSession(requestPages, host, _ui.value.resultText,
                            requestSession, recognitionRunning = true)
                    }
                }
                val text = recognition.recognize(requestPages, requestSettings) { _ -> Unit }
                if (text.isBlank()) throw IllegalStateException(
                    "Recognition returned no text. Your pages and previous text were kept.")
                currentCoroutineContext().ensureActive()
                if (requestGeneration == generation && requestSession == sessionId) {
                    _ui.update { it.copy(isSavingCapture = true, canSnap = false,
                        notice = "Saving recognized text…") }
                    currentCoroutineContext().ensureActive()
                    val cleanupPending = withContext(NonCancellable) {
                        captureMutex.withLock {
                            if (requestGeneration != generation || requestSession != sessionId) return@withLock null
                            sessionWriteMutex.withLock {
                                if (requestGeneration != generation || requestSession != sessionId) return@withLock null
                                val cleanup = withContext(Dispatchers.IO) {
                                    store.saveSessionAndRetirePages(emptyList(), host, text, requestSession,
                                        recognitionRunning = false, retiredPages = requestPages)
                                }
                                if (requestGeneration == generation && requestSession == sessionId) {
                                    captureGeneration++
                                    _ui.update { it.copy(pages = emptyList(), thumbnails = emptyMap(), currentPageCount = 0,
                                        selectedPreviewId = null, resultText = text, recognitionDraft = "", isRecognizing = false,
                                        isSavingCapture = false, storageError = "", recognitionMessage = "Recognition complete.",
                                        recognitionError = "", notice = if (cleanup)
                                            "Recognition saved. Some original images will be cleaned up later." else "Recognition complete.") }
                                    updateSnapAvailability()
                                    resultCommitted = true
                                }
                                cleanup
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!resultCommitted && requestGeneration == generation && requestSession == sessionId) {
                    _ui.update { it.copy(isRecognizing = false, recognitionDraft = "",
                        isSavingCapture = false, recognitionMessage = "Recognition cancelled.") }
                    persist(recognitionRunning = false)
                }
                throw cancelled
            } catch (error: Throwable) {
                if (!resultCommitted && requestGeneration == generation && requestSession == sessionId) {
                    _ui.update { it.copy(isRecognizing = false, recognitionDraft = "", recognitionMessage = "",
                        isSavingCapture = false,
                        recognitionError = error.message ?: "Recognition failed. Your previous text is unchanged.") }
                    persist(recognitionRunning = false)
                }
            }
        }
    }

    fun cancelRecognition() {
        if (!_ui.value.isSavingCapture) recognitionJob?.cancel()
    }
    fun requestClearPages() {
        val state = _ui.value
        if (state.isSavingGallery || state.isSavingCapture || state.isRecognizing) {
            _ui.update { it.copy(notice = "Wait for the current operation to finish before clearing pages.") }
            return
        }
        if (state.pages.isEmpty()) {
            _ui.update { it.copy(notice = "There are no captured pages to clear.") }
            return
        }
        _ui.update { it.copy(showDiscardDialog = true) }
    }

    fun cancelClearPages() { _ui.update { it.copy(showDiscardDialog = false) } }

    fun confirmClearPages() {
        val before = _ui.value
        if (before.isSavingGallery || before.isSavingCapture || before.isRecognizing || storageReadFailed) return
        val retiredPages = before.pages.toList()
        if (retiredPages.isEmpty()) {
            _ui.update { it.copy(showDiscardDialog = false) }
            return
        }
        val expectedSession = sessionId
        _ui.update { it.copy(showDiscardDialog = false, isSavingCapture = true, canSnap = false,
            notice = "Clearing captured pages…") }
        viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                val cleanupPending = withContext(NonCancellable) {
                    captureMutex.withLock {
                        if (expectedSession != sessionId) return@withLock null
                        sessionWriteMutex.withLock {
                            if (expectedSession != sessionId) return@withLock null
                            val retainedText = _ui.value.resultText
                            val cleanup = withContext(Dispatchers.IO) {
                                store.saveSessionAndRetirePages(emptyList(), host, retainedText, expectedSession,
                                    recognitionRunning = false, retiredPages = retiredPages)
                            }
                            if (expectedSession == sessionId) {
                                captureGeneration++
                                _ui.update { it.copy(pages = emptyList(), thumbnails = emptyMap(), currentPageCount = 0,
                                    selectedPreviewId = null, screen = AppScreen.SCAN, isSavingCapture = false,
                                    storageError = "", notice = if (cleanup)
                                        "Pages cleared. Some original images will be cleaned up later." else "Pages cleared.") }
                                updateSnapAvailability()
                            }
                            cleanup
                        }
                    }
                }
                if (cleanupPending == null && expectedSession == sessionId) {
                    _ui.update { it.copy(isSavingCapture = false,
                        notice = "The scan changed before pages could be cleared.") }
                    updateSnapAvailability()
                }
            } catch (cancelled: CancellationException) {
                if (expectedSession == sessionId) _ui.update { it.copy(isSavingCapture = false) }
                throw cancelled
            } catch (error: Throwable) {
                if (expectedSession == sessionId) {
                    val detail = error.message ?: "storage error"
                    _ui.update { it.copy(isSavingCapture = false,
                        storageError = "Could not clear pages: $detail",
                        notice = "Pages and saved text were kept because the updated session could not be saved.") }
                }
            }
        }
    }

    /** Full reset retained only for damaged-storage recovery. */
    fun requestNewScan() {
        if (_ui.value.isSavingGallery) {
            _ui.update { it.copy(notice = "Wait for the Gallery save to finish before starting a new scan.") }
            return
        }
        _ui.update { it.copy(showDiscardDialog = true) }
    }
    fun cancelNewScan() { _ui.update { it.copy(showDiscardDialog = false) } }
    fun confirmNewScan() {
        if (_ui.value.isSavingGallery || _ui.value.isSavingCapture) return
        _ui.update { it.copy(showDiscardDialog = false, isSavingCapture = true, canSnap = false,
            isRecognizing = false, recognitionDraft = "", recognitionMessage = "", recognitionError = "",
            notice = "Starting a new scan…") }
        generation++
        captureGeneration++
        recognitionJob?.cancel()
        dismissRecognitionDisclosure()
        val nextSession = UUID.randomUUID().toString()
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    captureMutex.withLock {
                        sessionWriteMutex.withLock {
                            val cleanupPending = withContext(Dispatchers.IO) { store.resetSavedSession(host, nextSession) }
                            sessionId = nextSession
                            storageReadFailed = false
                            _ui.update { it.copy(pages = emptyList(), thumbnails = emptyMap(), currentPageCount = 0,
                                resultText = "", selectedPreviewId = null, screen = AppScreen.SCAN,
                                isSavingCapture = false, storageError = "", notice = if (cleanupPending)
                                    "New scan started. Some originals will be cleaned up later." else "New scan started.") }
                        }
                    }
                }
                updateSnapAvailability()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                storageReadFailed = true
                _ui.update { it.copy(isSavingCapture = false, storageError = "Could not reset saved scan: ${error.message ?: "storage error"}",
                    notice = "The previous scan was kept because the reset could not be saved.") }
            }
        }
    }

    fun clearNotice() { _ui.update { it.copy(notice = "") } }

    private fun persist(recognitionRunning: Boolean = _ui.value.isRecognizing) {
        if (storageReadFailed) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionWriteMutex.withLock {
                    val snapshot = _ui.value
                    store.saveSession(snapshot.pages, host, snapshot.resultText, sessionId,
                        recognitionRunning || snapshot.isRecognizing)
                }
            } catch (error: Throwable) {
                _ui.update { it.copy(storageError = "Could not save scan state: ${error.message ?: "storage error"}",
                    notice = "Could not save scan state. Captured originals are preserved.", canSnap = false) }
            }
        }
    }

    private fun refreshThumbnails(pages: List<CapturedPage>) {
        val expectedGeneration = captureGeneration
        val expectedIds = pages.map { it.id }.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            val thumbnails = pages.mapNotNull { page ->
                decodeFile(page.path, 320)?.let { page.id to it }
            }.toMap()
            if (expectedGeneration == captureGeneration && expectedIds == _ui.value.pages.map { it.id }.toSet())
                _ui.update { it.copy(thumbnails = thumbnails) }
        }
    }

    suspend fun loadPreview(pageId: String): Bitmap? {
        val page = _ui.value.pages.firstOrNull { it.id == pageId } ?: return null
        return withContext(Dispatchers.IO) { decodeFile(page.path, 2048) }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryJob?.cancel()
        mirrorJob?.cancel()
        // A running request is marked in state so a process restart never repeats it silently.
    }

    private fun decodeFile(path: String, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) })
    }.getOrNull()

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide * 2 || height / sample > maxSide * 2) sample *= 2
        return sample
    }

    companion object {
        private const val FRAME_FRESH_MS = 5_000L
        private const val MAX_API_KEY_LENGTH = 4096
    }
}
