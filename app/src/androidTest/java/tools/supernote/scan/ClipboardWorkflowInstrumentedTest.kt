package tools.supernote.scan

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.superscanner.clipboard.ClipboardPairInfo
import tools.superscanner.clipboard.PairStatus

@RunWith(AndroidJUnit4::class)
class ClipboardWorkflowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var scanVm: AppViewModel
    private lateinit var clipboardVm: ClipboardViewModel
    private lateinit var transport: FixtureTransport
    private lateinit var store: SessionStore
    private lateinit var storeName: String

    @Before
    fun launchInjectedApp() {
        val app = compose.activity.application
        storeName = "clipboard-ui-${UUID.randomUUID()}"
        store = SessionStore(app, storeName)
        scanVm = AppViewModel(app, EmptyMirror(), EmptyRecognition(), store, autoConnect = false)
        transport = FixtureTransport()
        clipboardVm = ClipboardViewModel(app) { transport }
        compose.activity.viewModelStore.put("clipboard-scan-vm", scanVm)
        compose.activity.viewModelStore.put("clipboard-vm", clipboardVm)
        compose.setContent { ScannerApp(scanVm, clipboardVm) }
    }

    @After
    fun clearVms() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.viewModelStore.clear()
        }
        File(compose.activity.application.filesDir, storeName).deleteRecursively()
    }

    @Test
    fun explicitPairSendReceiveAndCopyPreserveScanSettingsAndRequireUserActions() {
        scanVm.openSettings()
        val settingsBefore = scanVm.ui.value.settings
        compose.onNodeWithTag("open_clipboard").performClick()
        compose.onNodeWithTag("clipboard_pairing_code").assertExists()
        compose.onNodeWithTag("clipboard_receive").assertDoesNotExist()
        compose.onNodeWithTag("clipboard_send").assertDoesNotExist()
        assertEquals(0, transport.pairRequests.get())
        assertEquals(0, transport.receives.get())
        transport.onBeforePairRequest = {
            assertEquals(FixtureTransport.APPROVAL_CODE, clipboardVm.ui.value.approvalCode)
        }
        val osClipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        osClipboard.setPrimaryClip(android.content.ClipData.newPlainText("existing", "keep this clipboard text"))

        input("clipboard_pairing_code", pairCode())
        tap("clipboard_pair")
        awaitPaired()
        assertEquals(FixtureTransport.APPROVAL_CODE, clipboardVm.ui.value.approvalCode)
        assertEquals(1, transport.approvalCodeCalls.get())

        tap("clipboard_paste_phone")
        assertEquals("keep this clipboard text", clipboardVm.ui.value.sendText)
        assertEquals("keep this clipboard text",
            osClipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        val sendField = compose.onNodeWithTag("clipboard_send_text").performScrollTo()
        sendField.performTextClearance()
        sendField.performTextInput("Synthetic clipboard text")
        tap("clipboard_send")
        compose.waitUntil(5_000) { transport.sentText == "Synthetic clipboard text" &&
            clipboardVm.ui.value.operation == ClipboardOperation.NONE }
        assertEquals(1, transport.sends.get())

        tap("clipboard_receive")
        compose.waitUntil(5_000) { clipboardVm.ui.value.receivedText == FixtureTransport.RECEIVED }
        assertEquals(1, transport.receives.get())
        assertEquals("keep this clipboard text",
            osClipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        tap("clipboard_copy_received")
        assertEquals(FixtureTransport.RECEIVED,
            osClipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())

        tap("clipboard_forget")
        compose.waitUntil(5_000) { !clipboardVm.ui.value.isPaired && clipboardVm.ui.value.receivedText.isEmpty() }
        assertEquals(FixtureTransport.RECEIVED,
            osClipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(5_000) { scanVm.ui.value.screen == AppScreen.SETTINGS }
        assertEquals(settingsBefore, scanVm.ui.value.settings)
        assertEquals(settingsBefore, store.loadSettings())
    }

    @Test
    fun pairingCancellationClosesActiveRequestAndLeavesNoApproval() {
        transport.awaitPairCancellation = true
        scanVm.openClipboard()
        input("clipboard_pairing_code", pairCode())
        tap("clipboard_pair")
        compose.waitUntil(5_000) { transport.pairRequests.get() == 1 &&
            clipboardVm.ui.value.operation == ClipboardOperation.PAIRING }
        tap("clipboard_cancel")
        compose.waitUntil(5_000) { clipboardVm.ui.value.operation == ClipboardOperation.NONE && transport.closed.get() > 0 }
        assertFalse(clipboardVm.ui.value.isPaired)
        assertEquals(PairStatus.UNPAIRED, clipboardVm.ui.value.pairingStatus)
    }

    @Test
    fun backCancelsTransferButKeepsApprovedPairingAndScannerScreen() {
        scanVm.openClipboard()
        input("clipboard_pairing_code", pairCode())
        tap("clipboard_pair")
        awaitPaired()
        transport.awaitSendCancellation = true
        clipboardVm.updateSendText("Keep this draft")
        clipboardVm.send()
        compose.waitUntil(5_000) { transport.sends.get() == 1 &&
            clipboardVm.ui.value.operation == ClipboardOperation.SENDING }
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(5_000) { scanVm.ui.value.screen == AppScreen.SCAN &&
            clipboardVm.ui.value.operation == ClipboardOperation.NONE }
        assertTrue(clipboardVm.ui.value.isPaired)
        assertEquals("Keep this draft", clipboardVm.ui.value.sendText)
        assertTrue(transport.closed.get() > 0)
    }

    @Test
    fun oversizedUtf8TextIsRejectedWholeWithoutSendingOrTruncating() {
        scanVm.openClipboard()
        input("clipboard_pairing_code", pairCode())
        tap("clipboard_pair")
        awaitPaired()
        val tooLarge = "€".repeat(21_846) // Three UTF-8 bytes each; exceeds the byte limit by two.
        clipboardVm.updateSendText(tooLarge)
        clipboardVm.send()
        compose.waitUntil(5_000) { clipboardVm.ui.value.error.contains("64 KiB") &&
            clipboardVm.ui.value.operation == ClipboardOperation.NONE }
        assertEquals(0, transport.sends.get())
        assertEquals(tooLarge, clipboardVm.ui.value.sendText)
    }

    @Test
    fun lateApprovalCodeFromCancelledPairCannotReturnAfterForget() {
        val delayedTransport = FixtureTransport(blockApprovalCode = true)
        val replacementTransport = FixtureTransport()
        var factoryCalls = 0
        val delayedVm = ClipboardViewModel(compose.activity.application) {
            if (factoryCalls++ == 0) delayedTransport else replacementTransport
        }
        compose.activity.viewModelStore.put("delayed-approval-vm", delayedVm)
        try {
            delayedVm.updatePairingCode(pairCode())
            compose.runOnIdle { delayedVm.pair() }
            compose.waitUntil(5_000) { delayedTransport.approvalCodeCalls.get() == 1 &&
                delayedVm.ui.value.operation == ClipboardOperation.PAIRING }
            compose.runOnIdle { delayedVm.forget() }
            assertEquals(null, delayedVm.ui.value.approvalCode)
            assertEquals("", delayedVm.ui.value.pairingCode)
            delayedTransport.approvalGate.countDown()
            compose.waitUntil(5_000) { delayedTransport.approvalReturned.count == 0L }
            Thread.sleep(150)
            assertEquals(null, delayedVm.ui.value.approvalCode)
            assertEquals(0, delayedTransport.pairRequests.get())
        } finally {
            delayedTransport.approvalGate.countDown()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun input(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(value)
    }

    private fun pairCode() = ClipboardPairInfo("192.168.1.23", 48291, "a".repeat(64)).encode()

    private fun awaitPaired() {
        try {
            compose.waitUntil(5_000) { clipboardVm.ui.value.isPaired }
        } catch (error: Throwable) {
            val state = clipboardVm.ui.value
            throw AssertionError(
                "Pairing timed out: status=${state.pairingStatus}, operation=${state.operation}, " +
                    "pairingCodeLength=${state.pairingCode.length}, approvalCodePresent=${state.approvalCode != null}, " +
                    "error=${state.error}, message=${state.message}, " +
                    "approvalCodeCalls=${transport.approvalCodeCalls.get()}, " +
                    "pairRequests=${transport.pairRequests.get()}, pairStatusCalls=${transport.pairStatusCalls.get()}",
                error,
            )
        }
    }

    private class EmptyMirror : MirrorSource {
        override suspend fun discover(manualHost: String?, cachedHost: String?) = emptyList<String>()
        override fun frames(host: String): Flow<MirrorFrame> = emptyFlow()
    }

    private class EmptyRecognition : RecognitionSource {
        override fun hasApiKey() = false
        override fun setApiKey(value: String) = Unit
        override fun subscriptionStatus() = ""
        override fun isChatGptConnected() = false
        override suspend fun beginChatGptSignIn(openBrowser: (android.net.Uri) -> Unit) = Unit
        override fun cancelSignIn() = Unit
        override suspend fun disconnectChatGpt() = Unit
        override suspend fun listModels(provider: String) = emptyList<ModelChoice>()
        override suspend fun recognize(pages: List<CapturedPage>, settings: ScanSettings, onDelta: (String) -> Unit) = ""
    }

    private class FixtureTransport(private val blockApprovalCode: Boolean = false) : ClipboardTransport {
        val pairRequests = AtomicInteger()
        val approvalCodeCalls = AtomicInteger()
        val sends = AtomicInteger()
        val receives = AtomicInteger()
        val closed = AtomicInteger()
        val pairStatusCalls = AtomicInteger()
        val approvalGate = CountDownLatch(1)
        val approvalReturned = CountDownLatch(1)
        @Volatile var onBeforePairRequest: (() -> Unit)? = null
        var awaitPairCancellation = false
        var awaitSendCancellation = false
        @Volatile var fixturePairStatus = PairStatus.APPROVED
        @Volatile var sentText: String? = null

        override fun approvalCode(info: ClipboardPairInfo): String {
            approvalCodeCalls.incrementAndGet()
            try {
                if (blockApprovalCode) approvalGate.await(5, TimeUnit.SECONDS)
            } finally {
                approvalReturned.countDown()
            }
            return APPROVAL_CODE
        }
        override suspend fun requestPair(info: ClipboardPairInfo): PairStatus {
            pairRequests.incrementAndGet()
            onBeforePairRequest?.invoke()
            if (awaitPairCancellation) awaitCancellation()
            return fixturePairStatus
        }
        override suspend fun pairStatus(info: ClipboardPairInfo): PairStatus {
            pairStatusCalls.incrementAndGet()
            return fixturePairStatus
        }
        override suspend fun receive(info: ClipboardPairInfo): String {
            receives.incrementAndGet()
            return RECEIVED
        }
        override suspend fun send(info: ClipboardPairInfo, text: String) {
            sends.incrementAndGet()
            if (awaitSendCancellation) awaitCancellation()
            sentText = text
        }
        override fun close() { closed.incrementAndGet() }

        companion object {
            const val APPROVAL_CODE = "ABCDEF012345"
            const val RECEIVED = "Synthetic text from helper"
        }
    }
}
