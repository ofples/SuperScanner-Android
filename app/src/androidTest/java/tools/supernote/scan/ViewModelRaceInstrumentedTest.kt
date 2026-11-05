package tools.supernote.scan

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tools.supernote.scan.network.MirrorClient

@RunWith(AndroidJUnit4::class)
class ViewModelRaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directoryName: String
    private lateinit var root: File
    private lateinit var store: SessionStore
    private lateinit var mirror: FakeMirrorSource
    private lateinit var recognition: FakeRecognitionSource

    @Before fun setUp() {
        assumeTrue("Synthetic storage/recognition regressions may run only on an Android emulator.",
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true))
        directoryName = "scan-vm-test-${UUID.randomUUID()}"
        root = File(context.filesDir, directoryName)
        store = SessionStore(context, directoryName)
        mirror = FakeMirrorSource()
        recognition = FakeRecognitionSource()
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun invalidApiKeySaveDoesNotWriteCandidateSettings() = runBlocking {
        val original = ScanSettings(model = "gpt-6-luna", glossary = "saved")
        store.saveSettings(original)
        val bytesBefore = File(root, "settings.json").readBytes()
        val vm = newViewModel()

        vm.updateSettingsDraft { it.copy(model = "gpt-6-astra") }
        vm.updateApiKeyDraft("invalid-key")
        vm.saveSettings()
        val error = awaitUi(vm) { it.settingsError.isNotBlank() }

        assertFalse(error.isSavingSettings)
        assertArrayEquals(bytesBefore, File(root, "settings.json").readBytes())
        assertEquals(original, SessionStore(context, directoryName).loadSettings())
    }

    @Test fun emptySnapAndRecognizeWaitsForCaptureCommitThenPersistsTextAndClearsOriginal() = runBlocking {
        store.saveSession(emptyList(), "192.168.1.20", "previous saved edit", "empty-before-snap", false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        recognition.action = { pages, _, _ ->
            recognition.requestedPageIds = pages.map(CapturedPage::id)
            recognition.requestedFilesExisted = pages.all { File(it.path).isFile }
            entered.complete(Unit)
            release.await()
        }
        val vm = newViewModel()
        connectAndSendFrame(vm)

        vm.snapAndRecognize()
        withTimeout(5_000) { entered.await() }

        val inFlight = store.loadSession()
        assertEquals(1, inFlight.pages.size)
        assertEquals("previous saved edit", inFlight.resultText)
        assertTrue(inFlight.interruptedRecognition)
        assertTrue(recognition.requestedFilesExisted)
        assertEquals(1, recognition.calls.get())
        val capturedPath = inFlight.pages.single().path
        assertTrue(File(capturedPath).isFile)

        release.complete("final synthetic transcript")
        val completed = awaitUi(vm) {
            !it.isRecognizing && !it.isSavingCapture &&
                it.pages.isEmpty() && it.resultText == "final synthetic transcript"
        }
        assertEquals(0, completed.currentPageCount)
        val committed = awaitPersistedResultWithoutPages("final synthetic transcript")
        assertEquals("final synthetic transcript", committed.resultText)
        assertFalse(File(capturedPath).exists())
        assertEquals(1, recognition.calls.get())

        vm.updateResultText("edited final synthetic transcript")
        awaitPersistedText("edited final synthetic transcript", vm)
        assertEquals("edited final synthetic transcript", store.loadSession().resultText)
    }

    @Test fun snapAndRecognizeWithNoOrStaleFrameNeverCallsRecognition() = runBlocking {
        val vm = newViewModel()
        vm.snapAndRecognize()
        assertEquals(0, recognition.calls.get())

        connectAndSendFrame(vm)
        AppViewModel::class.java.getDeclaredField("lastFrameReceivedAt").apply {
            isAccessible = true
            setLong(vm, 0L)
        }
        vm.snapAndRecognize()
        assertEquals(0, recognition.calls.get())
        assertTrue(vm.ui.value.pages.isEmpty())
    }

    @Test fun snapAndRecognizeCapturePersistenceFailureNeverCallsRecognition() = runBlocking {
        val vm = newViewModel()
        connectAndSendFrame(vm)
        val movedRoot = File(context.filesDir, directoryName + "-capture-failure")
        assertTrue(root.renameTo(movedRoot))
        root.writeText("not a directory")
        try {
            vm.snapAndRecognize()
            val failed = awaitUi(vm) { !it.isSavingCapture && it.storageError.isNotBlank() }
            assertTrue(failed.pages.isEmpty())
            assertEquals(0, recognition.calls.get())
        } finally {
            root.delete()
            assertTrue(movedRoot.renameTo(root))
        }
    }

    @Test fun clearPagesCancelPreservesSessionAndConfirmKeepsResultSettingsAndAccountState() = runBlocking {
        val settings = ScanSettings(model = "gpt-6-luna", glossary = "fixture glossary",
            instructions = "fixture guidance")
        store.saveSettings(settings)
        seedPageAndText("saved editable result")
        val pagePath = store.loadSession().pages.single().path
        val vm = newViewModel()

        vm.requestClearPages()
        assertTrue(vm.ui.value.showDiscardDialog)
        vm.cancelClearPages()
        assertEquals(1, vm.ui.value.pages.size)
        assertEquals("saved editable result", store.loadSession().resultText)
        assertTrue(File(pagePath).isFile)

        vm.requestClearPages()
        vm.confirmClearPages()
        val cleared = awaitUi(vm) { !it.isSavingCapture && it.pages.isEmpty() }
        assertEquals("", cleared.storageError)
        assertEquals("saved editable result", cleared.resultText)
        assertEquals(settings, cleared.settings)
        assertTrue(store.loadSession().pages.isEmpty())
        assertEquals("saved editable result", store.loadSession().resultText)
        assertEquals(settings, store.loadSettings())
        assertFalse(File(pagePath).exists())
    }

    @Test fun clearPagesSessionCommitFailureKeepsOriginalPageAndSavedText() = runBlocking {
        seedPageAndText("original result stays")
        val pageBefore = store.loadSession().pages.single()
        val bytesBefore = File(pageBefore.path).readBytes()
        val vm = newViewModel()
        val movedRoot = File(context.filesDir, directoryName + "-clear-failure")
        assertTrue(root.renameTo(movedRoot))
        root.writeText("not a directory")
        try {
            vm.requestClearPages()
            vm.confirmClearPages()
            val failed = awaitUi(vm) { !it.isSavingCapture && it.storageError.isNotBlank() }
            assertEquals(listOf(pageBefore.id), failed.pages.map(CapturedPage::id))
            assertEquals("original result stays", failed.resultText)
        } finally {
            root.delete()
            assertTrue(movedRoot.renameTo(root))
        }
        val retained = SessionStore(context, directoryName).loadSession()
        assertEquals(listOf(pageBefore.id), retained.pages.map(CapturedPage::id))
        assertEquals("original result stays", retained.resultText)
        assertArrayEquals(bytesBefore, File(pageBefore.path).readBytes())
    }

    @Test fun recognitionPromptSaveCancelAndRestoreDefaultKeepDraftSemantics() = runBlocking {
        val original = ScanSettings(glossary = "glossary", instructions = "keep this guidance",
            prompt = "Previously saved base prompt.")
        store.saveSettings(original)
        val vm = newViewModel()

        vm.openSettings()
        vm.updateRecognitionPromptDraft("temporary edit")
        vm.restoreDefaultRecognitionPrompt()
        assertEquals(DEFAULT_RECOGNITION_PROMPT, vm.ui.value.settingsDraft.prompt)
        vm.cancelSettings()
        assertEquals(original, vm.ui.value.settings)
        assertEquals(original, SessionStore(context, directoryName).loadSettings())

        vm.openSettings()
        vm.updateRecognitionPromptDraft("Saved custom transcription prompt.")
        vm.saveSettings()
        val saved = awaitUi(vm) { it.screen == AppScreen.SCAN && !it.isSavingSettings }
        assertEquals("Saved custom transcription prompt.", saved.settings.prompt)
        val persisted = SessionStore(context, directoryName).loadSettings()
        assertEquals("Saved custom transcription prompt.", persisted.prompt)
        assertEquals("keep this guidance", persisted.instructions)
        assertEquals("glossary", persisted.glossary)
    }

    @Test fun duplicateGallerySavesAreBlockedAndCaptureCannotBeDeletedOrResetMidExport() = runBlocking {
        val originalBytes = testPng()
        val page = store.savePage(MirrorFrame(originalBytes, "image/png", 2, 3, System.currentTimeMillis()), emptyList())
        store.saveSession(listOf(page), null, "saved text", "gallery-session", false)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<GalleryExportResult>()
        val calls = AtomicInteger()
        val exporter = object : GalleryExportSource {
            override suspend fun export(page: CapturedPage): GalleryExportResult {
                calls.incrementAndGet()
                started.complete(Unit)
                return finish.await()
            }
        }
        val vm = AppViewModel(application(), mirror, recognition, store, autoConnect = false,
            galleryExporter = exporter)
        vm.openPage(page.id)
        vm.savePageToGallery(page.id)
        vm.savePageToGallery(page.id)
        started.await()

        assertEquals(1, calls.get())
        assertTrue(vm.ui.value.isSavingGallery)
        vm.askDeletePage(page.id)
        vm.requestNewScan()
        vm.confirmNewScan()
        assertEquals(null, vm.ui.value.showDeletePageId)
        assertFalse(vm.ui.value.showDiscardDialog)
        assertEquals(listOf(page.id), vm.ui.value.pages.map { it.id })

        finish.complete(GalleryExportResult("fixture.png", "Pictures/SuperScanner"))
        val completed = awaitUi(vm) { !it.isSavingGallery }
        assertTrue(completed.gallerySaveMessage.contains("Pictures/SuperScanner"))
        assertTrue(File(page.path).isFile)
        assertArrayEquals(originalBytes, File(page.path).readBytes())
        assertEquals("saved text", SessionStore(context, directoryName).loadSession().resultText)
    }

    @Test fun deniedLegacyGalleryPermissionExplainsHowToRecoverAndKeepsCapture() {
        val originalBytes = testPng()
        val page = store.savePage(MirrorFrame(originalBytes, "image/png", 2, 3, System.currentTimeMillis()), emptyList())
        store.saveSession(listOf(page), null, "saved text", "gallery-permission", false)
        val vm = newViewModel()
        vm.openPage(page.id)
        vm.galleryPermissionDenied(page.id)

        assertTrue(vm.ui.value.gallerySaveError.contains("Android Settings > Apps"))
        assertEquals(vm.ui.value.gallerySaveError, vm.ui.value.notice)
        assertTrue(File(page.path).isFile)
        assertArrayEquals(originalBytes, File(page.path).readBytes())
    }

    @Test fun damagedStorageResetIsIgnoredWhileCaptureIsCommitting() = runBlocking {
        val vm = newViewModel()
        connectAndSendFrame(vm)

        vm.snap()
        vm.requestNewScan()
        vm.confirmNewScan()
        val captured = awaitUi(vm) { !it.isSavingCapture && it.pages.size == 1 }
        assertEquals(1, captured.pages.size)
        assertEquals(1, SessionStore(context, directoryName).loadSession().pages.size)

        vm.cancelNewScan()
        vm.requestNewScan()
        vm.confirmNewScan()
        awaitUi(vm) { !it.isSavingCapture && it.pages.isEmpty() }

        val loaded = SessionStore(context, directoryName).loadSession()
        assertTrue(loaded.pages.isEmpty())
        assertEquals("", loaded.resultText)
    }

    @Test fun failedDamagedStorageResetKeepsOriginalPagesAndSavedText() = runBlocking {
        seedPageAndText("result retained after failed reset")
        val originalPage = store.loadSession().pages.single()
        val originalBytes = File(originalPage.path).readBytes()
        val vm = newViewModel()
        val movedRoot = File(context.filesDir, "$directoryName-reset-failure")
        assertTrue(root.renameTo(movedRoot))
        root.writeText("not a directory")
        try {
            vm.requestNewScan()
            vm.confirmNewScan()
            val failed = awaitUi(vm) { !it.isSavingCapture && it.storageError.isNotBlank() }
            assertEquals(listOf(originalPage.id), failed.pages.map(CapturedPage::id))
            assertEquals("result retained after failed reset", failed.resultText)
        } finally {
            assertTrue(root.delete())
            assertTrue(movedRoot.renameTo(root))
        }

        assertTrue(File(originalPage.path).isFile)
        val restored = SessionStore(context, directoryName).loadSession()
        assertEquals(listOf(originalPage.id), restored.pages.map(CapturedPage::id))
        assertEquals("result retained after failed reset", restored.resultText)
        assertArrayEquals(originalBytes, File(originalPage.path).readBytes())
    }

    @Test fun lateRecognitionCompletionAfterNewScanCannotReplaceNewSessionText() = runBlocking {
        seedPageAndText("old text")
        var vm: AppViewModel? = null
        recognition.action = { _, _, delta ->
            delta("draft from old request")
            vm!!.requestNewScan()
            vm!!.confirmNewScan()
            "late old result"
        }
        vm = newViewModel()

        vm!!.openResult()
        awaitUi(vm!!) { !it.isSavingCapture && it.pages.isEmpty() }
        delay(100)

        assertEquals("", vm!!.ui.value.resultText)
        assertEquals("", SessionStore(context, directoryName).loadSession().resultText)
    }

    @Test fun failedRecognitionKeepsThePreviouslyEditedResult() = runBlocking {
        seedPageAndText("my edited transcription")
        val originalPage = store.loadSession().pages.single()
        recognition.action = { _, _, delta ->
            delta("incomplete streamed draft")
            throw IOException("mock provider failure")
        }
        val vm = newViewModel()

        vm.openResult()
        val failed = awaitUi(vm) { !it.isRecognizing && it.recognitionError.isNotBlank() }

        assertEquals("my edited transcription", failed.resultText)
        assertEquals("", failed.recognitionDraft)
        val retained = SessionStore(context, directoryName).loadSession()
        assertEquals("my edited transcription", retained.resultText)
        assertEquals(listOf(originalPage.id), retained.pages.map(CapturedPage::id))
        assertTrue(File(originalPage.path).isFile)
    }

    @Test fun cancelledRecognitionKeepsPagesAndPreviouslySavedText() = runBlocking {
        seedPageAndText("saved text before cancellation")
        val originalPage = store.loadSession().pages.single()
        val entered = CompletableDeferred<Unit>()
        recognition.action = { _, _, delta ->
            delta("unfinished draft")
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = newViewModel()

        vm.openResult()
        entered.await()
        vm.cancelRecognition()
        val cancelled = awaitUi(vm) { !it.isRecognizing && it.recognitionMessage.contains("cancelled", true) }

        assertEquals("saved text before cancellation", cancelled.resultText)
        assertEquals(listOf(originalPage.id), cancelled.pages.map(CapturedPage::id))
        assertTrue(File(originalPage.path).isFile)
        val disk = awaitPersistedSession(originalPage.id, "saved text before cancellation")
        assertEquals(listOf(originalPage.id), disk.pages.map(CapturedPage::id))
    }

    @Test fun blankRecognitionKeepsPagesAndPreviouslySavedText() = runBlocking {
        seedPageAndText("saved text before blank response")
        val originalPage = store.loadSession().pages.single()
        recognition.action = { _, _, _ -> " \n\t " }
        val vm = newViewModel()

        vm.openResult()
        val failed = awaitUi(vm) { !it.isRecognizing && it.recognitionError.isNotBlank() }

        assertEquals("saved text before blank response", failed.resultText)
        assertEquals(listOf(originalPage.id), failed.pages.map(CapturedPage::id))
        assertTrue(File(originalPage.path).isFile)
        val disk = store.loadSession()
        assertEquals("saved text before blank response", disk.resultText)
        assertEquals(listOf(originalPage.id), disk.pages.map(CapturedPage::id))
    }

    @Test fun successfulRecognitionSessionCommitFailureRetainsOriginalPageAndPriorText() = runBlocking {
        seedPageAndText("prior durable result")
        val pageBefore = store.loadSession().pages.single()
        val bytesBefore = File(pageBefore.path).readBytes()
        val movedRoot = File(context.filesDir, directoryName + "-recognition-commit-failure")
        recognition.action = { _, _, _ ->
            assertTrue(root.renameTo(movedRoot))
            root.writeText("not a directory")
            "new recognition result must not replace the previous text"
        }
        val vm = newViewModel()
        vm.recognize()
        try {
            val failed = awaitUi(vm) { !it.isRecognizing && it.recognitionError.isNotBlank() }
            assertEquals("prior durable result", failed.resultText)
            assertEquals(listOf(pageBefore.id), failed.pages.map(CapturedPage::id))
        } finally {
            if (root.isFile) assertTrue(root.delete())
            if (movedRoot.exists()) assertTrue(movedRoot.renameTo(root))
        }
        val retained = SessionStore(context, directoryName).loadSession()
        assertEquals(listOf(pageBefore.id), retained.pages.map(CapturedPage::id))
        assertEquals("prior durable result", retained.resultText)
        assertArrayEquals(bytesBefore, File(pageBefore.path).readBytes())
    }

    @Test fun reconnectClearsTheOldFrameAndPreventsDisconnectedSnap() = runBlocking {
        val vm = newViewModel()
        connectAndSendFrame(vm)
        assertTrue(vm.ui.value.canSnap)

        mirror.hosts = emptyList()
        vm.reconnect()
        awaitUi(vm) { !it.isDiscovering && !it.isConnected }
        vm.snap()

        assertFalse(vm.ui.value.canSnap)
        assertTrue(vm.ui.value.pages.isEmpty())
    }

    @Test fun sessionAndEditableTextSurviveViewModelRecreation() = runBlocking {
        val vm = newViewModel()
        connectAndSendFrame(vm)
        vm.snap()
        awaitUi(vm) { it.pages.size == 1 && !it.isSavingCapture }
        vm.updateResultText("saved edit")
        awaitPersistedText("saved edit", vm)

        val recreated = AppViewModel(application(), FakeMirrorSource(), FakeRecognitionSource(),
            SessionStore(context, directoryName), autoConnect = false)
        val restored = recreated.ui.value
        assertEquals(1, restored.pages.size)
        assertEquals("saved edit", restored.resultText)
        assertTrue(File(restored.pages.single().path).isFile)
    }

    private fun newViewModel(): AppViewModel = AppViewModel(application(), mirror, recognition, store, autoConnect = false)

    private suspend fun connectAndSendFrame(vm: AppViewModel) {
        vm.reconnect()
        awaitUi(vm) { it.isConnected }
        mirror.frames.subscriptionCount.first { it > 0 }
        val image = testPng()
        mirror.frames.emit(MirrorFrame(image, "image/png", 2, 3,
            System.currentTimeMillis()))
        awaitUi(vm) { it.isConnected && it.livePreview != null && it.canSnap }
    }

    private fun seedPageAndText(text: String) {
        val page = store.savePage(MirrorFrame(testPng(), "image/png", 2, 3, 100L), emptyList())
        store.saveSession(listOf(page), null, text, "seed-session", recognitionRunning = false)
    }

    private suspend fun awaitPersistedText(value: String, vm: AppViewModel) {
        val saved = withTimeoutOrNull(5_000) {
            while (true) {
                val session = SessionStore(context, directoryName).loadSession()
                if (session.resultText == value) return@withTimeoutOrNull true
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        check(saved == true) {
            val state = vm.ui.value
            val onDisk = SessionStore(context, directoryName).loadSession()
            "Text persistence timed out; uiTextLength=${state.resultText.length}, pages=${state.pages.size}, " +
                "saving=${state.isSavingCapture}, storageError=${state.storageError}, " +
                "diskTextLength=${onDisk.resultText.length}, diskPages=${onDisk.pages.size}, " +
                "diskError=${onDisk.storageError}"
        }
    }

    private suspend fun awaitPersistedSession(pageId: String, text: String): SessionStore.SavedSession {
        val saved = withTimeoutOrNull(5_000) {
            while (true) {
                val session = SessionStore(context, directoryName).loadSession()
                if (!session.interruptedRecognition && session.resultText == text &&
                    session.pages.any { it.id == pageId }) return@withTimeoutOrNull session
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return checkNotNull(saved) { "Expected the previous text and page reference to remain durable after cancellation." }
    }

    private suspend fun awaitPersistedResultWithoutPages(text: String): SessionStore.SavedSession {
        val saved = withTimeoutOrNull(5_000) {
            while (true) {
                val session = SessionStore(context, directoryName).loadSession()
                if (!session.interruptedRecognition && session.resultText == text && session.pages.isEmpty())
                    return@withTimeoutOrNull session
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return checkNotNull(saved) {
            val onDisk = SessionStore(context, directoryName).loadSession()
            "Expected durable result with no pages; pages=${onDisk.pages.size}, " +
                "textLength=${onDisk.resultText.length}, storageError=${onDisk.storageError}"
        }
    }

    private suspend fun awaitUi(vm: AppViewModel, predicate: (AppUiState) -> Boolean): AppUiState =
        withTimeoutOrNull(5_000) { vm.ui.first(predicate) } ?: error(
            "UI state wait timed out; connected=${vm.ui.value.isConnected}, discovering=${vm.ui.value.isDiscovering}, " +
                "pages=${vm.ui.value.pages.size}, saving=${vm.ui.value.isSavingCapture}, " +
                "recognizing=${vm.ui.value.isRecognizing}, storageError=${vm.ui.value.storageError}, notice=${vm.ui.value.notice}")

    private fun application(): Application = context.applicationContext as Application

    private fun testPng(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    private class FakeMirrorSource : MirrorSource {
        @Volatile var hosts: List<String> = listOf("192.168.1.20")
        val frames = MutableSharedFlow<MirrorFrame>(extraBufferCapacity = 2)
        override suspend fun discover(manualHost: String?, cachedHost: String?): List<String> = hosts
        override fun frames(host: String): Flow<MirrorFrame> = frames
    }

    private class FakeRecognitionSource : RecognitionSource {
        val calls = AtomicInteger()
        @Volatile var requestedPageIds: List<String> = emptyList()
        @Volatile var requestedFilesExisted = false
        @Volatile var action: suspend (List<CapturedPage>, ScanSettings, (String) -> Unit) -> String =
            { _, _, delta -> delta("recognized draft"); "recognized text" }
        override fun hasApiKey() = false
        override fun setApiKey(value: String) {
            require(value.isEmpty() || (value == value.trim() && value.length <= 4096 && value.startsWith("sk-")))
        }
        override fun subscriptionStatus() = "Not connected"
        override fun isChatGptConnected() = false
        override suspend fun beginChatGptSignIn(openBrowser: (Uri) -> Unit) = Unit
        override fun cancelSignIn() = Unit
        override suspend fun disconnectChatGpt() = Unit
        override suspend fun listModels(provider: String) = listOf(ModelChoice("gpt-6.1-sol", "GPT 6.1 Sol"))
        override suspend fun recognize(pages: List<CapturedPage>, settings: ScanSettings, onDelta: (String) -> Unit): String {
            calls.incrementAndGet()
            return action(pages, settings, onDelta)
        }
    }
}
