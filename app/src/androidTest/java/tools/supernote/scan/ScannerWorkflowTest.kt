package tools.supernote.scan

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppViewModel
    private lateinit var vmState: MutableState<AppViewModel>
    private lateinit var mirror: FixtureMirror
    private lateinit var recognition: FixtureRecognition
    private lateinit var store: SessionStore
    private lateinit var storeName: String

    @Before
    fun launchInjectedScanner() {
        val application = compose.activity.application
        storeName = "scanner-test-${UUID.randomUUID()}"
        store = SessionStore(application, storeName)
        mirror = FixtureMirror(SampleNotes.pages)
        recognition = FixtureRecognition()
        vm = AppViewModel(application, mirror, recognition, store)
        compose.activity.viewModelStore.put("scanner-test-view-model", vm)
        vmState = mutableStateOf(vm)
        compose.setContent { ScannerApp(vmState.value) }
    }

    @After
    fun closeFixtureAndViewModel() {
        if (::mirror.isInitialized) mirror.disconnect()
        compose.activity.runOnUiThread { compose.activity.viewModelStore.clear() }
        runCatching { Intents.release() }
    }

    @Test
    fun capturesReordersDeletesAndRestoresPagesThenEditsCopiesAndSharesRecognition() {
        compose.waitUntil(10_000) { vm.ui.value.canSnap && vm.ui.value.livePreview != null }
        compose.onNodeWithTag("snap_button").performClick()
        compose.waitUntil(10_000) { vm.ui.value.pages.size == 1 && !vm.ui.value.isSavingCapture }
        val firstLiveFrame = vm.ui.value.livePreview ?: error("The sample frame was not decoded.")
        mirror.advanceTo(1)
        compose.waitUntil(10_000) { vm.ui.value.livePreview?.sameAs(firstLiveFrame) == false }
        compose.onNodeWithTag("snap_button").performClick()
        compose.waitUntil(10_000) { vm.ui.value.pages.size == 2 && !vm.ui.value.isSavingCapture }
        compose.onNodeWithContentDescription("Live Supernote screen preview").assertExists()
        val capturedIds = vm.ui.value.pages.map { it.id }
        assertEquals(2, capturedIds.size)
        captureEvidence("scanner-two-pages")

        // The visible arrow controls have been replaced by drag ordering and accessibility actions.
        // The dedicated regression below exercises the actual held-drag path across the row viewport.
        vm.movePage(capturedIds[1], -1)
        compose.waitUntil(5_000) { vm.ui.value.pages.map { it.id } == capturedIds.reversed() }
        compose.onNodeWithTag("remove_page_${capturedIds[1]}").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) { vm.ui.value.pages.size == 1 && !vm.ui.value.isSavingCapture }
        val retainedId = vm.ui.value.pages.single().id

        compose.activity.runOnUiThread {
            compose.activity.viewModelStore.clear()
            vm = AppViewModel(compose.activity.application, mirror, recognition,
                SessionStore(compose.activity.application, storeName))
            compose.activity.viewModelStore.put("scanner-test-view-model", vm)
            vmState.value = vm
        }
        compose.waitUntil(10_000) { vmState.value.ui.value.pages.map { it.id } == listOf(retainedId) }
        assertTrue(vm.ui.value.pages.single().path.contains(storeName))

        compose.onNodeWithTag("recognize_button").performClick()
        compose.waitUntil(10_000) { !vm.ui.value.isRecognizing && vm.ui.value.resultText == FixtureRecognition.RESULT }
        compose.onNodeWithContentDescription("Editable recognized text").assertExists()
        captureEvidence("result-synthetic-recognition")
        val textField = compose.onNodeWithContentDescription("Editable recognized text").performScrollTo()
        textField.performTextClearance()
        textField.performTextInput("Edited fixture transcript")
        compose.waitUntil(5_000) { vm.ui.value.resultText == "Edited fixture transcript" }

        compose.onNodeWithContentDescription("Copy recognized text").performScrollTo().performClick()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Edited fixture transcript", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())

        Intents.init()
        intending(anyIntent()).respondWith(android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, Intent()))
        compose.onNodeWithContentDescription("Share recognized text").performScrollTo().performClick()
        intended(hasAction(Intent.ACTION_CHOOSER))
        Intents.release()

        vm.openSettings()
        compose.waitUntil(5_000) { vm.ui.value.screen == AppScreen.SETTINGS }
        compose.onNodeWithContentDescription("OpenAI API key").assertExists()
        SystemClock.sleep(2_500) // Let Android's clipboard confirmation toast expire before the settings capture.
        captureEvidence("settings-synthetic")
        compose.onNodeWithContentDescription("OpenAI API key").performClick()
        compose.waitForIdle()
        captureEvidence("settings-keyboard-synthetic")
        vm.updateSettingsDraft { it.copy(model = "gpt-uncommitted") }
        vm.updateApiKeyDraft("not-a-key")
        vm.saveSettings()
        compose.waitUntil(5_000) { vm.ui.value.settingsError.isNotBlank() }
        assertEquals(ScanSettings(), SessionStore(compose.activity.application, storeName).loadSettings())

        vm.cancelSettings()
        compose.waitUntil(5_000) { vm.ui.value.screen == AppScreen.SCAN }
        mirror.disconnect()
        compose.waitUntil(5_000) { !vm.ui.value.isConnected && !vm.ui.value.canSnap }
        compose.onNodeWithTag("snap_button").assertIsNotEnabled()
    }

    @Test
    fun firstRecognitionDisclosureRequiresConfirmationBeforeFixtureRecognition() {
        val preferencesName = "disclosure-test-$storeName"
        val preferences = compose.activity.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        recognition = FixtureRecognition(initiallyHasKey = true)
        vm = AppViewModel(compose.activity.application, mirror, recognition, store,
            recognitionDisclosure = RecognitionDisclosure(compose.activity, preferencesName))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.viewModelStore.clear()
            compose.activity.viewModelStore.put("scanner-test-view-model", vm)
            vmState.value = vm
        }

        compose.waitUntil(10_000) { vm.ui.value.canSnap }
        compose.onNodeWithTag("snap_button").performClick()
        compose.waitUntil(10_000) { store.loadSession().pages.size == 1 }
        compose.onNodeWithTag("recognize_button").performScrollTo().performClick()
        compose.onNodeWithTag("recognition_disclosure_continue").assertExists()
        assertEquals("No recognition request may start before consent", 0, recognition.recognitionCalls.get())
        assertEquals(1, store.loadSession().pages.size)
        assertFalse(RecognitionDisclosure(compose.activity, preferencesName).acknowledged())

        compose.onNodeWithTag("recognition_disclosure_cancel").performClick()
        assertEquals(0, recognition.recognitionCalls.get())
        assertEquals(1, store.loadSession().pages.size)

        compose.onNodeWithTag("recognize_button").performScrollTo().performClick()
        compose.onNodeWithTag("recognition_disclosure_continue").performClick()
        val expected = SampleNotes.pages.first().transcript
        compose.waitUntil(10_000) {
            store.loadSession().pages.isEmpty() && store.loadSession().resultText == expected
        }
        assertEquals("Consent permits exactly one injected recognition request", 1,
            recognition.recognitionCalls.get())
        assertTrue(RecognitionDisclosure(compose.activity, preferencesName).acknowledged())
        assertEquals(expected, store.loadSession().resultText)
        preferences.edit().clear().commit()
    }

    private class FixtureMirror(private val pages: List<SampleNotes.Note>) : MirrorSource {
        private val connected = AtomicBoolean(true)
        private val pageIndex = AtomicInteger(0)
        override suspend fun discover(manualHost: String?, cachedHost: String?) = listOf("fixture-host")
        override fun frames(host: String): Flow<MirrorFrame> = flow {
            while (currentCoroutineContext().isActive && connected.get()) {
                val image = pages[pageIndex.get().coerceIn(pages.indices)].image
                emit(MirrorFrame(image, "image/png", SampleNotes.WIDTH, SampleNotes.HEIGHT,
                    System.currentTimeMillis()))
                delay(300)
            }
            throw IllegalStateException("Mirror fixture disconnected.")
        }
        fun advanceTo(index: Int) {
            require(index in pages.indices)
            pageIndex.set(index)
        }
        fun disconnect() { connected.set(false) }
    }

    private class FixtureRecognition(initiallyHasKey: Boolean = false) : RecognitionSource {
        private var hasKey = initiallyHasKey
        val recognitionCalls = AtomicInteger()
        override fun hasApiKey() = hasKey
        override fun setApiKey(value: String) { hasKey = value.isNotBlank() }
        override fun subscriptionStatus() = ""
        override fun isChatGptConnected() = false
        override suspend fun beginChatGptSignIn(openBrowser: (android.net.Uri) -> Unit) = throw UnsupportedOperationException()
        override fun cancelSignIn() = Unit
        override suspend fun disconnectChatGpt() = Unit
        override suspend fun listModels(provider: String) = listOf(ModelChoice("fixture", "Fixture model"))
        override suspend fun recognize(pages: List<CapturedPage>, settings: ScanSettings, onDelta: (String) -> Unit): String {
            recognitionCalls.incrementAndGet()
            assertTrue(pages.isNotEmpty())
            onDelta("Fixture OCR…")
            delay(150)
            return pages.joinToString("\n\n") { capture ->
                val bytes = File(capture.path).readBytes()
                SampleNotes.pages.firstOrNull { it.image.contentEquals(bytes) }?.transcript
                    ?: error("The captured fixture did not match a sample page.")
            }
        }
        companion object { val RESULT = SampleNotes.pages.first().transcript }
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not capture synthetic UI evidence: $name")
        val output = ByteArrayOutputStream()
        check(screenshot.compress(Bitmap.CompressFormat.JPEG, 82, output))
        screenshot.recycle()
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        val chunks = encoded.chunked(2_800)
        chunks.forEachIndexed { index, chunk -> Log.i("ScanImage", "$name|$index|${chunks.size}|$chunk") }
    }
}
