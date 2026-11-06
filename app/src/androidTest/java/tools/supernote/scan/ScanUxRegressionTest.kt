package tools.supernote.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.test.espresso.intent.Intents.getIntents

/** Synthetic-only UI regressions for capture ordering, reset, recognition and image sharing. */
@RunWith(AndroidJUnit4::class)
class ScanUxRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var vmState: MutableState<AppViewModel>? = null
    private var vm: AppViewModel? = null
    private var store: SessionStore? = null
    private var storeName: String? = null
    private var seededPages = emptyList<CapturedPage>()
    private var sessionSnapshot: SessionStore.SavedSession? = null
    private var existingShareFiles = emptySet<String>()
    private var galleryCalls: AtomicInteger? = null
    private var composeContentSet = false
    private var intentsStarted = false
    private var emulatorOnlyChecked = false

    @Before
    fun requireDedicatedEmulator() {
        assumeTrue("Scanner UX regressions may run only on an Android emulator.",
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true))
        emulatorOnlyChecked = true
        existingShareFiles = File(context.cacheDir, "share-captures").listFiles()
            ?.map { it.name }?.toSet().orEmpty()
    }

    @After
    fun tearDown() {
        if (!emulatorOnlyChecked) return
        if (intentsStarted) runCatching { Intents.release() }
        runCatching { compose.activity.runOnUiThread { compose.activity.viewModelStore.clear() } }

        val activeStore = store
        if (activeStore != null && seededPages.isNotEmpty()) {
            runCatching { activeStore.deletePages(seededPages) }
        }
        val originalSession = sessionSnapshot
        if (activeStore != null && originalSession != null) {
            runCatching {
                activeStore.saveSession(originalSession.pages, originalSession.host, originalSession.resultText,
                    originalSession.sessionId, originalSession.interruptedRecognition)
            }
        }
        File(context.cacheDir, "share-captures").listFiles()
            ?.filter { it.name !in existingShareFiles }?.forEach { runCatching { it.delete() } }
        storeName?.let { name -> File(context.filesDir, name).deleteRecursively() }
        vm = null
        vmState = null
        store = null
        seededPages = emptyList()
        sessionSnapshot = null
    }

    @Test
    fun longPressDragAutoscrollPersistsOrderAndRecognitionReceivesThatOrder() {
        val testStore = newStore()
        val pages = seedPages(testStore, count = 7)
        val recognition = TestRecognition()
        recognition.action = { received, _, _ ->
            recognition.receivedIds = received.map(CapturedPage::id)
            "synthetic ordered result"
        }
        val viewModel = launch(testStore, recognition)
        val originalIds = pages.map(CapturedPage::id)

        compose.waitUntil(10_000) { viewModel.ui.value.pages.map(CapturedPage::id) == originalIds }
        val row = compose.onNodeWithTag("page_thumbnail_row").performScrollTo().assertExists()
        compose.onNodeWithTag("scan_thumbnail_${originalIds.first()}").assertExists()

        repeat(4) {
            row.performTouchInput {
                down(center)
                moveTo(Offset(center.x - 420f, center.y), 300)
                up()
            }
        }
        val swipeRowBounds = compose.onNodeWithTag("page_thumbnail_row").fetchSemanticsNode().boundsInRoot
        val lastPageBounds = compose.onNodeWithTag("scan_thumbnail_${originalIds.last()}")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Ordinary short swipes should bring the last page into view.",
            lastPageBounds.left < swipeRowBounds.right && lastPageBounds.right > swipeRowBounds.left)
        repeat(4) {
            row.performTouchInput {
                down(center)
                moveTo(Offset(center.x + 420f, center.y), 300)
                up()
            }
        }
        compose.onNodeWithTag("scan_thumbnail_${originalIds.first()}").assertExists()

        compose.onNodeWithContentDescription("Open page 1 preview").performTouchInput { click() }
        compose.waitUntil(5_000) { viewModel.ui.value.screen == AppScreen.PREVIEW }
        compose.onNodeWithText("Back to scan").performClick()
        compose.waitUntil(5_000) { viewModel.ui.value.screen == AppScreen.SCAN }

        val rowBounds = compose.onNodeWithTag("page_thumbnail_row").fetchSemanticsNode().boundsInRoot
        val firstBounds = compose.onNodeWithTag("scan_thumbnail_${originalIds.first()}")
            .fetchSemanticsNode().boundsInRoot
        val start = Offset(firstBounds.center.x - rowBounds.left, firstBounds.center.y - rowBounds.top)
        assertTrue("The fixture must start within the visible thumbnail viewport.", start.x in 0f..rowBounds.width)
        val rightEdge = Offset(rowBounds.width - 5f, start.y)
        injectHeldDrag(rowBounds, start, rightEdge)

        val expected = originalIds.drop(1) + originalIds.first()
        compose.waitUntil(10_000) { viewModel.ui.value.pages.map(CapturedPage::id) == expected }
        compose.waitUntil(10_000) { testStore.loadSession().pages.map(CapturedPage::id) == expected }
        assertEquals(expected, viewModel.ui.value.pages.map(CapturedPage::id))

        compose.onNodeWithTag("recognize_button").performClick()
        compose.waitUntil(10_000) { !viewModel.ui.value.isRecognizing }
        assertEquals("Recognition must receive the same persisted order as the scan.",
            expected, recognition.receivedIds)
    }

    @Test
    fun thumbnailXRemovesOnlyTheSelectedPageWithoutOpeningPreview() {
        val testStore = newStore()
        val pages = seedPages(testStore, count = 3)
        val viewModel = launch(testStore)
        val removed = pages[1]

        compose.onNodeWithTag("remove_page_${removed.id}").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) {
            !viewModel.ui.value.isSavingCapture && viewModel.ui.value.pages.none { it.id == removed.id }
        }

        val expected = pages.filterNot { it.id == removed.id }.map(CapturedPage::id)
        assertEquals(AppScreen.SCAN, viewModel.ui.value.screen)
        assertEquals(null, viewModel.ui.value.selectedPreviewId)
        assertEquals(expected, viewModel.ui.value.pages.map(CapturedPage::id))
        assertEquals(expected, testStore.loadSession().pages.map(CapturedPage::id))
        compose.onNodeWithContentDescription("Full page preview").assertDoesNotExist()
    }

    @Test
    fun clearPagesCancelPreservesSessionAndConfirmedClearKeepsResultAndSettings() {
        val testStore = newStore()
        val settings = ScanSettings(model = "fixture-vision", glossary = "synthetic glossary",
            instructions = "synthetic guidance", prompt = "fixture prompt")
        testStore.saveSettings(settings)
        val pages = seedPages(testStore, count = 2, result = "synthetic saved result")
        val viewModel = launch(testStore)

        compose.onNodeWithTag("clear_pages").assertExists().performScrollTo().performClick()
        compose.waitUntil(5_000) { viewModel.ui.value.showDiscardDialog }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Cancel").assertExists() }.isSuccess }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { !viewModel.ui.value.showDiscardDialog }
        assertEquals(pages.map(CapturedPage::id), viewModel.ui.value.pages.map(CapturedPage::id))
        assertEquals("synthetic saved result", viewModel.ui.value.resultText)
        assertEquals(settings, testStore.loadSettings())

        compose.onNodeWithTag("clear_pages").performScrollTo().performClick()
        compose.waitUntil(5_000) { viewModel.ui.value.showDiscardDialog }
        val clearActions = compose.onAllNodesWithText("Clear pages")
        assertEquals(2, clearActions.fetchSemanticsNodes().size)
        clearActions[1].performClick()
        compose.waitUntil(10_000) { !viewModel.ui.value.isSavingCapture && viewModel.ui.value.pages.isEmpty() }
        compose.waitUntil(10_000) { testStore.loadSession().pages.isEmpty() }
        assertEquals("synthetic saved result", viewModel.ui.value.resultText)
        assertEquals("synthetic saved result", testStore.loadSession().resultText)
        assertEquals(settings, viewModel.ui.value.settings)
        assertEquals(settings, testStore.loadSettings())
    }

    @Test
    fun recognitionKeepsPriorTextVisibleAndNeverRendersIntermediateDeltas() {
        val testStore = newStore()
        seedPages(testStore, count = 1, result = "prior synthetic text")
        val recognition = TestRecognition(hasConfiguredApiKey = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        recognition.action = { received, _, onDelta ->
            recognition.receivedIds = received.map(CapturedPage::id)
            onDelta("PRIVATE_INTERMEDIATE_FIXTURE_DELTA")
            entered.complete(Unit)
            release.await()
        }
        val viewModel = launch(testStore, recognition)

        compose.onNodeWithTag("recognize_button").performClick()
        compose.waitUntil(10_000) { entered.isCompleted && viewModel.ui.value.isRecognizing }
        compose.onNodeWithTag("recognition_result_text").assertTextContains("prior synthetic text")
        compose.onNodeWithTag("recognition_progress").assertExists()
        compose.onNodeWithText("PRIVATE_INTERMEDIATE_FIXTURE_DELTA").assertDoesNotExist()
        compose.onNodeWithText("Recognizing again sends these pages to the selected provider.").assertDoesNotExist()
        assertEquals("prior synthetic text", viewModel.ui.value.resultText)

        release.complete("final synthetic text")
        compose.waitUntil(10_000) { !viewModel.ui.value.isRecognizing && viewModel.ui.value.resultText == "final synthetic text" }
        compose.onNodeWithTag("recognition_result_text").assertTextContains("final synthetic text")
        compose.onNodeWithText("PRIVATE_INTERMEDIATE_FIXTURE_DELTA").assertDoesNotExist()
        compose.onNodeWithTag("recognition_progress").assertDoesNotExist()
    }

    @Test
    fun cancelledRecognitionHidesInterimAndRetainsPreviousText() {
        val testStore = newStore()
        seedPages(testStore, count = 1, result = "prior text survives cancellation")
        val recognition = TestRecognition()
        val entered = CompletableDeferred<Unit>()
        recognition.action = { _, _, onDelta ->
            onDelta("CANCELLED_INTERMEDIATE_FIXTURE")
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = launch(testStore, recognition)

        compose.onNodeWithTag("recognize_button").performClick()
        compose.waitUntil(10_000) { entered.isCompleted && viewModel.ui.value.isRecognizing }
        compose.onNodeWithTag("recognition_result_text").assertTextContains("prior text survives cancellation")
        compose.onNodeWithText("CANCELLED_INTERMEDIATE_FIXTURE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel recognition").performClick()
        compose.waitUntil(10_000) { !viewModel.ui.value.isRecognizing }

        assertEquals("prior text survives cancellation", viewModel.ui.value.resultText)
        compose.onNodeWithTag("recognition_result_text").assertTextContains("prior text survives cancellation")
        compose.onNodeWithText("CANCELLED_INTERMEDIATE_FIXTURE").assertDoesNotExist()
    }

    @Test
    fun previewShareUsesScopedFullResolutionImageAndChooserCancelDoesNotExportToGallery() {
        // CaptureSharer intentionally accepts only the production private-page root. This test
        // uses an otherwise empty instrumentation app store and a generated image only.
        val productionStore = SessionStore(context)
        val previous = productionStore.loadSession()
        assumeTrue("The dedicated test app store must not contain existing pages.", previous.pages.isEmpty())
        sessionSnapshot = previous
        store = productionStore
        val original = testPng(Color.rgb(31, 119, 203))
        val page = productionStore.savePage(
            MirrorFrame(original, "image/png", 48, 64, System.currentTimeMillis()), emptyList())
        seededPages = listOf(page)
        productionStore.saveSession(listOf(page), null, "", "share-fixture-${UUID.randomUUID()}", false)
        val exporterCalls = AtomicInteger()
        galleryCalls = exporterCalls
        val exporter = object : GalleryExportSource {
            override suspend fun export(page: CapturedPage): GalleryExportResult {
                exporterCalls.incrementAndGet()
                return GalleryExportResult("unexpected.png", "Pictures/SuperScanner")
            }
        }
        val viewModel = launch(productionStore, galleryExporter = exporter)

        Intents.init()
        intentsStarted = true
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(
            android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()))
        compose.onNodeWithContentDescription("Open page 1 preview").performClick()
        compose.waitUntil(5_000) { viewModel.ui.value.screen == AppScreen.PREVIEW }
        compose.onNodeWithTag("share_page_original").performClick()
        compose.waitUntil(10_000) { getIntents().any { it.action == Intent.ACTION_CHOOSER } }
        intended(hasAction(Intent.ACTION_CHOOSER))

        val chooser = getIntents().last { it.action == Intent.ACTION_CHOOSER }
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            ?: error("The image share chooser did not contain an ACTION_SEND intent.")
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue((send.flags.toInt() and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: error("The image share intent did not include its content URI.")
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.sharefiles", uri.authority)
        assertEquals("image/png", context.contentResolver.getType(uri))
        assertArrayEquals("The share must contain the full-resolution source bytes.", original,
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(0, exporterCalls.get())
        assertTrue(File(page.path).isFile)
        assertArrayEquals(original, File(page.path).readBytes())
        assertEquals(AppScreen.PREVIEW, viewModel.ui.value.screen)
        assertFalse(viewModel.ui.value.isSavingGallery)

        File(context.cacheDir, "share-captures").listFiles()?.filter { it.name !in existingShareFiles }
            ?.forEach(File::delete)
        productionStore.deletePages(listOf(page))
        productionStore.saveSession(previous.pages, previous.host, previous.resultText,
            previous.sessionId, previous.interruptedRecognition)
        seededPages = emptyList()
        sessionSnapshot = null
    }

    private fun newStore(): SessionStore {
        val name = "scan-ux-${UUID.randomUUID()}"
        storeName = name
        return SessionStore(context, name).also { store = it }
    }

    private fun seedPages(testStore: SessionStore, count: Int, result: String = ""): List<CapturedPage> {
        var pages = emptyList<CapturedPage>()
        repeat(count) { index ->
            val color = Color.rgb(20 + index * 25, 80 + index * 15, 170 - index * 12)
            pages = pages + testStore.savePage(
                MirrorFrame(testPng(color), "image/png", 48, 64, System.currentTimeMillis()), pages)
        }
        testStore.saveSession(pages, null, result, "scan-ux-session-${UUID.randomUUID()}", false)
        seededPages = pages
        return pages
    }

    private fun launch(
        testStore: SessionStore,
        recognition: TestRecognition = TestRecognition(),
        galleryExporter: GalleryExportSource = object : GalleryExportSource {
            override suspend fun export(page: CapturedPage) =
                GalleryExportResult("unused.png", "Pictures/SuperScanner")
        },
    ): AppViewModel {
        val newVm = AppViewModel(context.applicationContext as android.app.Application,
            TestMirror(), recognition, testStore, autoConnect = false, galleryExporter = galleryExporter)
        vm = newVm
        val state = vmState ?: mutableStateOf(newVm).also { vmState = it }
        state.value = newVm
        compose.activity.viewModelStore.put("scan-ux-test-view-model", newVm)
        if (!composeContentSet) {
            compose.setContent { ScannerApp(vmState!!.value) }
            composeContentSet = true
        }
        return newVm
    }

    private fun testPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    /** Inject touchscreen frames and advance Compose's virtual clock without querying semantics mid-drag. */
    private fun injectHeldDrag(rowBounds: androidx.compose.ui.geometry.Rect, start: Offset, end: Offset) {
        val contentView = compose.activity.findViewById<View>(android.R.id.content)
        val contentOnScreen = IntArray(2)
        contentView.getLocationOnScreen(contentOnScreen)
        val startX = contentOnScreen[0] + rowBounds.left + start.x
        val startY = contentOnScreen[1] + rowBounds.top + start.y
        val endX = contentOnScreen[0] + rowBounds.left + end.x
        val endY = contentOnScreen[1] + rowBounds.top + end.y
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        var pointerReleased = false

        fun inject(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(automation.injectInputEvent(event, true)) { "Touch event injection was rejected." }
            } finally {
                event.recycle()
            }
        }

        inject(MotionEvent.ACTION_DOWN, startX, startY)
        try {
            // Compose pointer timeouts and the edge-scroll LaunchedEffect use the test frame clock.
            // Advancing only wall time does not guarantee that either coroutine receives frames.
            SystemClock.sleep(700)
            compose.mainClock.advanceTimeBy(700)
            inject(MotionEvent.ACTION_MOVE, endX, endY)
            // Keep the physical pointer down while advancing real and Compose frames at the edge.
            repeat(30) {
                SystemClock.sleep(60)
                compose.mainClock.advanceTimeBy(60)
                inject(MotionEvent.ACTION_MOVE, endX, endY)
            }
            inject(MotionEvent.ACTION_UP, endX, endY)
            pointerReleased = true
        } finally {
            if (!pointerReleased) runCatching { inject(MotionEvent.ACTION_CANCEL, endX, endY) }
        }
    }

    private class TestMirror : MirrorSource {
        override suspend fun discover(manualHost: String?, cachedHost: String?) = emptyList<String>()
        override fun frames(host: String): Flow<MirrorFrame> = emptyFlow()
        fun disconnect() = Unit
    }

    private class TestRecognition(private val hasConfiguredApiKey: Boolean = false) : RecognitionSource {
        @Volatile var receivedIds: List<String> = emptyList()
        var action: suspend (List<CapturedPage>, ScanSettings, (String) -> Unit) -> String =
            { _, _, _ -> "synthetic recognition result" }

        override fun hasApiKey() = hasConfiguredApiKey
        override fun setApiKey(value: String) = Unit
        override fun subscriptionStatus() = ""
        override fun isChatGptConnected() = false
        override suspend fun beginChatGptSignIn(openBrowser: (Uri) -> Unit) = throw UnsupportedOperationException()
        override fun cancelSignIn() = Unit
        override suspend fun disconnectChatGpt() = Unit
        override suspend fun listModels(provider: String) = listOf(ModelChoice("fixture", "Fixture model"))
        override suspend fun recognize(
            pages: List<CapturedPage>,
            settings: ScanSettings,
            onDelta: (String) -> Unit,
        ): String = action(pages, settings, onDelta)
    }
}
