package tools.supernote.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.superscanner.nativelink.NativeDevice
import tools.superscanner.nativelink.NativeKeyboardVisibility

@RunWith(AndroidJUnit4::class)
class NativeLinkWorkflowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var scanVm: AppViewModel
    private lateinit var linkVm: NativeLinkViewModel
    private lateinit var source: FakeNativeLinkSource
    private lateinit var storeName: String

    @Before
    fun launchInjectedApp() {
        val app = compose.activity.application
        storeName = "native-link-ui-${UUID.randomUUID()}"
        scanVm = AppViewModel(app, EmptyMirror(), EmptyRecognition(), SessionStore(app, storeName), autoConnect = false)
        source = FakeNativeLinkSource()
        linkVm = NativeLinkViewModel(app, source)
        source.attempt.uiCode = { linkVm.ui.value.comparisonCode }
        source.attempt.uiDisplayName = { linkVm.ui.value.pairDisplayName }
        compose.activity.viewModelStore.put("native-scan-vm", scanVm)
        compose.activity.viewModelStore.put("native-link-vm", linkVm)
        compose.setContent { ScannerApp(scanVm, nativeLinkVm = linkVm) }
    }

    @After
    fun clearVms() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        File(compose.activity.application.filesDir, storeName).deleteRecursively()
    }

    @Test
    fun discoveryAndPairShowCodeBeforeRequestThenClipboardActionsRemainExplicit() {
        compose.onNodeWithTag("open_native_link").performClick()
        compose.waitUntil(5_000) { source.discoveries.get() == 1 && linkVm.ui.value.devices.isNotEmpty() }
        assertEquals(0, source.attempt.awaitCalls.get())
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "old phone draft"))
        tap("native_pair")
        compose.waitUntil(5_000) { linkVm.ui.value.isPaired }
        compose.onNodeWithTag("native_paired_status").assertExists()
        assertTrue(source.attempt.codeWasVisibleBeforeAwait)
        assertTrue(source.attempt.displayNameWasVisibleBeforeAwait)
        compose.waitForIdle()
        captureSyntheticUi("paired")
        assertEquals(0, source.session.clipboardSends.get())
        assertEquals(0, source.session.clipboardReceives.get())
        assertEquals(0, source.session.keyboardOpens.get())

        assertEquals("old phone draft", linkVm.ui.value.clipboardDraft)
        clipboard.clearPrimaryClip()
        tap("native_tab_keyboard")
        tap("native_tab_clipboard")
        compose.waitUntil(5_000) { linkVm.ui.value.clipboardDraft.isEmpty() }
        compose.onNodeWithTag("native_send_clipboard").assertIsNotEnabled()
        assertEquals(0, source.session.clipboardSends.get())

        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "phone text"))
        tap("native_tab_keyboard")
        tap("native_tab_clipboard")
        compose.waitUntil(5_000) { linkVm.ui.value.clipboardDraft == "phone text" }
        assertEquals("phone text", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        tap("native_clear_clipboard_draft")
        compose.waitUntil(5_000) { linkVm.ui.value.clipboardDraft.isEmpty() }
        compose.waitForIdle()
        assertEquals("phone text", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        assertEquals(0, source.session.clipboardSends.get())
        assertTrue(linkVm.updateClipboardDraft("edited phone draft"))
        tap("native_send_clipboard")
        compose.waitUntil(5_000) { source.session.clipboardSends.get() == 1 && linkVm.ui.value.operation == NativeLinkOperation.NONE }
        assertEquals("edited phone draft", source.session.lastClipboardSend)
        assertEquals("phone text", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())

        assertFalse(linkVm.updateClipboardDraft("x".repeat(NativeLinkViewModel.MAX_CLIPBOARD_BYTES + 1)))
        compose.waitUntil(5_000) { linkVm.ui.value.error.contains("64 KiB") }
        assertEquals(1, source.session.clipboardSends.get())

        tap("native_receive_clipboard")
        compose.waitUntil(5_000) { source.session.clipboardPreparations.get() == 1 && source.session.clipboardReceives.get() == 1 && linkVm.ui.value.receivedText == FakeNativeLinkSource.RECEIVED }
        assertFalse(linkVm.ui.value.keyboardStarted)
        assertEquals(0, source.session.keyboardOpens.get())
        assertEquals(FakeNativeLinkSource.RECEIVED,
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        tap("native_tab_keyboard")
        tap("native_keyboard_start")
        compose.waitUntil(5_000) {
            linkVm.ui.value.keyboardStarted && linkVm.ui.value.keyboardVisibility == NativeKeyboardVisibility.VISIBLE &&
                linkVm.ui.value.operation == NativeLinkOperation.NONE
        }
        compose.onNodeWithTag("native_keyboard_input").assertExists()
        compose.waitForIdle()
        captureSyntheticUi("keyboard")
        tap("native_tab_clipboard")
        compose.waitUntil(5_000) { !linkVm.ui.value.keyboardStarted && linkVm.ui.value.operation == NativeLinkOperation.NONE }
        assertTrue(linkVm.ui.value.isPaired)
        assertTrue(source.session.keyboardCloses.get() > 0)
        assertEquals(FakeNativeLinkSource.RECEIVED,
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
    }

    @Test
    fun cancelledPairCannotAcceptLateSession() {
        val lateResult = CompletableDeferred<NativePairOutcome>()
        source.attempt.result = lateResult
        compose.onNodeWithTag("open_native_link").performClick()
        compose.waitUntil(5_000) { source.discoveries.get() == 1 && linkVm.ui.value.devices.isNotEmpty() }
        tap("native_pair")
        compose.waitUntil(5_000) { linkVm.ui.value.comparisonCode == FakeNativeLinkSource.CODE && source.attempt.awaitCalls.get() == 1 }
        tap("native_cancel_pair")
        compose.waitUntil(5_000) { linkVm.ui.value.operation == NativeLinkOperation.NONE && source.attempt.cancelled.get() > 0 }
        lateResult.complete(NativePairOutcome.Approved(source.session))
        compose.waitUntil(5_000) { !linkVm.ui.value.isPaired }
        Thread.sleep(150)
        assertFalse(linkVm.ui.value.isPaired)
        assertTrue(source.attempt.cancelled.get() > 0)
    }

    @Test
    fun leavingClipboardReceiveDoesNotCopyLateResult() {
        compose.onNodeWithTag("open_native_link").performClick()
        compose.waitUntil(5_000) { linkVm.ui.value.devices.isNotEmpty() }
        tap("native_pair")
        compose.waitUntil(5_000) { linkVm.ui.value.isPaired }
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "leave this untouched"))
        source.session.receiveGate = CompletableDeferred()

        tap("native_receive_clipboard")
        compose.waitUntil(5_000) {
            source.session.clipboardPreparations.get() == 1 && source.session.clipboardReceives.get() == 1
        }
        tap("native_tab_keyboard")
        compose.waitUntil(5_000) { linkVm.ui.value.operation == NativeLinkOperation.NONE }
        source.session.receiveGate?.complete(FakeNativeLinkSource.RECEIVED)
        Thread.sleep(250)

        assertTrue(linkVm.ui.value.isPaired)
        assertEquals(null, linkVm.ui.value.receivedText)
        assertEquals("leave this untouched", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        assertEquals(0, source.session.keyboardOpens.get())
    }

    @Test
    fun cancelClipboardReceiveKeepsDraftAndPhoneClipboardUntouched() {
        compose.onNodeWithTag("open_native_link").performClick()
        compose.waitUntil(5_000) { linkVm.ui.value.devices.isNotEmpty() }
        tap("native_pair")
        compose.waitUntil(5_000) { linkVm.ui.value.isPaired }
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "phone clipboard stays"))
        linkVm.updateClipboardDraft("unsent draft")
        source.session.receiveGate = CompletableDeferred()

        tap("native_receive_clipboard")
        compose.waitUntil(5_000) {
            source.session.clipboardPreparations.get() == 1 && source.session.clipboardReceives.get() == 1
        }
        tap("native_cancel_clipboard_receive")
        compose.waitUntil(5_000) { linkVm.ui.value.operation == NativeLinkOperation.NONE }
        source.session.receiveGate?.complete(FakeNativeLinkSource.RECEIVED)
        Thread.sleep(200)

        assertEquals("unsent draft", linkVm.ui.value.clipboardDraft)
        assertEquals(null, linkVm.ui.value.receivedText)
        assertEquals("phone clipboard stays", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity).toString())
        assertEquals(0, source.session.clipboardSends.get())
    }

    @Test
    fun leavingKeyboardTabStopsSharingWithoutDroppingPairOrReplayingKeys() {
        openPairedKeyboard()
        source.session.firstKeyDownGate = CompletableDeferred()
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello", committed = true)) }
        compose.waitUntil(5_000) { source.session.events.any { it.startsWith("down:") } }

        tap("native_tab_clipboard")
        compose.waitUntil(5_000) { !linkVm.ui.value.keyboardStarted && linkVm.ui.value.operation == NativeLinkOperation.NONE }
        val eventCountAfterStop = source.session.events.size
        source.session.firstKeyDownGate?.complete(Unit)
        Thread.sleep(350)

        assertTrue(linkVm.ui.value.isPaired)
        assertTrue(source.session.keyboardCloses.get() > 0)
        assertTrue(source.session.pressedKeys.isEmpty())
        assertEquals(eventCountAfterStop, source.session.events.size)
        assertEquals(1, source.session.events.count { it.startsWith("down:") })
    }

    @Test
    fun keyboardWaitsForCommittedAsciiSerializesFastEditsAndDoesNotReplayFailure() {
        openPairedKeyboard()
        source.session.firstKeyDownGate = CompletableDeferred()
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello", committed = false)) }
        Thread.sleep(350)
        assertTrue(source.session.events.isEmpty())

        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello", committed = true)) }
        compose.waitUntil(5_000) { source.session.events.any { it == "down:36:0" } }
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello world", committed = true)) }
        source.session.firstKeyDownGate?.complete(Unit)
        compose.waitUntil(10_000) { linkVm.ui.value.keyboardDraft.isEmpty() && linkVm.ui.value.operation == NativeLinkOperation.NONE }
        assertEquals("hello world", source.session.printableText())
        assertEquals(1, source.session.maxConcurrent.get())

        source.session.failNextKeyUp = true
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("x", committed = true)) }
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardHalted }
        val afterFailure = source.session.events.size
        compose.runOnIdle { assertFalse(linkVm.updateKeyboardDraft("y", committed = true)) }
        Thread.sleep(400)
        assertEquals(afterFailure, source.session.events.size)
        assertFalse(linkVm.ui.value.isPaired)
    }

    @Test
    fun phoneInputRemainsFocusedAcrossTransientSupernoteVisibilityChanges() {
        openPairedKeyboard()
        compose.onNodeWithTag("native_keyboard_input").assertExists().performScrollTo()
        compose.waitUntil(5_000) { findKeyboardInputOrNull() != null }
        val input = findKeyboardInput()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            input.requestFocus()
            input.showSoftInputOnFocus = true
            val manager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(5_000) { phoneImeVisible() }
        assertTrue(phoneImeVisible())

        compose.runOnIdle { source.session.setVisibility(NativeKeyboardVisibility.UNKNOWN) }
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardVisibility == NativeKeyboardVisibility.UNKNOWN }
        InstrumentationRegistry.getInstrumentation().sendStringSync("a")
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardDraft == "a" }
        assertTrue(source.session.events.isEmpty())

        compose.runOnIdle { source.session.setVisibility(NativeKeyboardVisibility.HIDDEN) }
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardVisibility == NativeKeyboardVisibility.HIDDEN }
        compose.onNodeWithTag("native_keyboard_input").assertExists()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertTrue(phoneImeVisible())

        compose.runOnIdle { source.session.setVisibility(NativeKeyboardVisibility.VISIBLE) }
        compose.waitUntil(5_000) {
            linkVm.ui.value.keyboardDraft.isEmpty() && source.session.printableText() == "a"
        }
        compose.onNodeWithTag("native_keyboard_input").assertExists()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertTrue(phoneImeVisible())
    }

    @Test
    fun typingHelpCanDismissAndFocusedInputStaysAboveImeWithControlsAboveIt() {
        assumeTrue("IME geometry requires API 30+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        openPairedKeyboard()
        val beforeHelp = source.session.events.toList()

        tap("native_keyboard_help")
        compose.onNodeWithTag("native_keyboard_help_dialog").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native_keyboard_help_dialog").fetchSemanticsNodes().isEmpty() }
        assertTrue(linkVm.ui.value.isPaired)
        assertTrue(linkVm.ui.value.keyboardStarted)
        assertEquals(beforeHelp, source.session.events.toList())

        compose.onNodeWithTag("native_keyboard_input").assertExists().performScrollTo()
        val selectAllBounds = compose.onNodeWithTag("native_keyboard_select_all").fetchSemanticsNode().boundsInRoot
        val doneBounds = compose.onNodeWithTag("native_keyboard_done").fetchSemanticsNode().boundsInRoot
        val inputBounds = compose.onNodeWithTag("native_keyboard_input").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Shortcut controls should precede the phone input: selectAll=$selectAllBounds done=$doneBounds input=$inputBounds",
            selectAllBounds.bottom <= inputBounds.top && doneBounds.bottom <= inputBounds.top,
        )

        compose.onNodeWithTag("native_keyboard_input").performClick()
        compose.waitUntil(5_000) { phoneImeVisible() }
        val input = findKeyboardInput()
        assertTrue(input.hasFocus())

        val viewBottomAndIme = IntArray(3)
        fun updateInputImeBounds() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val location = IntArray(2)
                input.getLocationOnScreen(location)
                val imeBottom = input.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                val displaySize = Point()
                @Suppress("DEPRECATION")
                compose.activity.windowManager.defaultDisplay.getRealSize(displaySize)
                viewBottomAndIme[0] = location[1] + input.height
                viewBottomAndIme[1] = displaySize.y - imeBottom
                viewBottomAndIme[2] = imeBottom
            }
        }
        compose.waitUntil(5_000) {
            updateInputImeBounds()
            viewBottomAndIme[2] > 0 && viewBottomAndIme[0] <= viewBottomAndIme[1]
        }
        assertTrue("IME inset should be active", viewBottomAndIme[2] > 0)
        assertTrue("Phone input must remain above the IME", viewBottomAndIme[0] <= viewBottomAndIme[1])
        val gapPx = viewBottomAndIme[1] - viewBottomAndIme[0]
        val gapDp = gapPx / input.resources.displayMetrics.density
        android.util.Log.i("ScanUiGeometry", "settledInputImeGap=${gapPx}px/${"%.1f".format(java.util.Locale.ROOT, gapDp)}dp")
        assertTrue(linkVm.ui.value.isPaired)
        assertTrue(linkVm.ui.value.keyboardStarted)
        assertEquals(beforeHelp, source.session.events.toList())
    }

    @Test
    fun composingTailSurvivesAcknowledgedPrefixAndWaitsForCommit() {
        openPairedKeyboard()
        source.session.firstKeyDownGate = CompletableDeferred()
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello", committed = true)) }
        compose.waitUntil(5_000) { source.session.events.contains("down:36:0") }
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello+", committed = false)) }
        source.session.firstKeyDownGate?.complete(Unit)
        compose.waitUntil(10_000) { linkVm.ui.value.keyboardDraft == "+" && linkVm.ui.value.operation == NativeLinkOperation.NONE }
        val sentAfterFirstChunk = source.session.events.size
        Thread.sleep(450)
        assertEquals("+", linkVm.ui.value.keyboardDraft)
        assertEquals(sentAfterFirstChunk, source.session.events.size)

        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("+", committed = true)) }
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardDraft.isEmpty() && linkVm.ui.value.operation == NativeLinkOperation.NONE }
        assertEquals("hello+", source.session.printableText())
    }

    @Test
    fun api33TextAttributeCompositionWaitsForFinishThenSendsExactlyOnce() {
        assumeTrue("Modern InputConnection overloads require API 33+", Build.VERSION.SDK_INT >= 33)
        openPairedKeyboard()
        compose.onNodeWithTag("native_keyboard_input").assertExists().performScrollTo()
        compose.waitUntil(5_000) { findKeyboardInputOrNull() != null }
        val input = findKeyboardInput()
        var created: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(input.requestFocus())
            created = input.onCreateInputConnection(EditorInfo())
        }
        val connection = checkNotNull(created)
        val attribute = TextAttribute.Builder().build()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(connection.setComposingText("hello", 1, attribute))
        }
        Thread.sleep(500L) // More than the 240 ms key-send debounce.
        assertTrue("Composing text reached remote keys: ${source.session.events}; draft=${linkVm.ui.value.keyboardDraft}", source.session.events.isEmpty())
        assertEquals("hello", linkVm.ui.value.keyboardDraft)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertTrue(connection.finishComposingText()) }
        compose.waitUntil(5_000) {
            linkVm.ui.value.keyboardDraft.isEmpty() && linkVm.ui.value.operation == NativeLinkOperation.NONE
        }
        assertEquals("hello", source.session.printableText())
        assertEquals(1, source.session.events.count { it == "down:36:0" })
        assertEquals(1, source.session.events.count { it == "up:36:0" })
    }

    @Test
    fun backspaceAndCtrlShortcutsPreserveOrderingAndRequireVisibleKeyboard() {
        openPairedKeyboard()
        assertTrue("state=${linkVm.ui.value}", linkVm.handlePhoneBackspace("", 0, 0, hasComposition = false, perform = true))
        compose.waitUntil(5_000) { linkVm.ui.value.operation == NativeLinkOperation.NONE && source.session.events.size == 2 }
        assertEquals(listOf("down:67:0", "up:67:0"), source.session.events.toList())

        source.session.setVisibility(NativeKeyboardVisibility.HIDDEN)
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardVisibility == NativeKeyboardVisibility.HIDDEN }
        val countWhenHidden = source.session.events.size
        linkVm.remoteBackspace()
        assertFalse(linkVm.handlePhoneBackspace("", 0, 0, hasComposition = false, perform = true))
        Thread.sleep(150)
        assertEquals(countWhenHidden, source.session.events.size)

        source.session.setVisibility(NativeKeyboardVisibility.VISIBLE)
        compose.waitUntil(5_000) { linkVm.ui.value.keyboardVisibility == NativeKeyboardVisibility.VISIBLE }
        source.session.firstKeyDownGate = CompletableDeferred()
        assertTrue(linkVm.updateKeyboardDraft("ab", committed = true))
        compose.waitUntil(5_000) { source.session.events.contains("down:29:0") }
        assertTrue(linkVm.handlePhoneBackspace("ab", 2, 2, hasComposition = false, perform = true))
        source.session.firstKeyDownGate?.complete(Unit)
        compose.waitUntil(10_000) {
            linkVm.ui.value.operation == NativeLinkOperation.NONE &&
                source.session.events.takeLast(2) == listOf("down:67:0", "up:67:0")
        }
        assertEquals("ab", source.session.printableText())

        tap("native_keyboard_select_all")
        compose.waitUntil(5_000) { source.session.events.takeLast(2) == listOf("down:29:4096", "up:29:4096") }
        val shortcuts = listOf(
            "native_keyboard_cut" to 52,
            "native_keyboard_copy" to 31,
            "native_keyboard_paste" to 50,
        )
        for ((tag, keyCode) in shortcuts) {
            val previousCount = source.session.events.size
            tap(tag)
            compose.waitUntil(5_000) {
                linkVm.ui.value.operation == NativeLinkOperation.NONE && source.session.events.size == previousCount + 2
            }
            assertEquals(listOf("down:$keyCode:4096", "up:$keyCode:4096"), source.session.events.takeLast(2))
        }
    }

    @Test
    fun editingInflightPrefixStopsWithoutSendingReplacement() {
        openPairedKeyboard()
        source.session.firstKeyDownGate = CompletableDeferred()
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("hello", committed = true)) }
        compose.waitUntil(5_000) { source.session.events.contains("down:36:0") }
        compose.runOnIdle { assertFalse(linkVm.updateKeyboardDraft("replacement", committed = true)) }
        assertTrue(linkVm.ui.value.keyboardHalted)
        val countAtHalt = source.session.events.size
        source.session.firstKeyDownGate?.complete(Unit)
        Thread.sleep(450)
        assertEquals(countAtHalt, source.session.events.size)
        assertFalse(linkVm.ui.value.isPaired)
    }

    @Test
    fun leavingDuringKeyWritePreventsLateKeyUpOrReplay() {
        openPairedKeyboard()
        source.session.firstKeyDownGate = CompletableDeferred()
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("a", committed = true)) }
        compose.waitUntil(5_000) { source.session.events.contains("down:29:0") }
        compose.runOnIdle { linkVm.leaveScreen() }
        source.session.firstKeyDownGate?.complete(Unit)
        Thread.sleep(350)
        assertFalse(linkVm.ui.value.isPaired)
        assertTrue(source.session.closed.get() > 0)
        assertFalse(source.session.events.contains("up:29:0"))
        assertTrue(source.session.events.contains("close-up:29:0"))
        assertEquals(1, source.session.events.count { it == "down:29:0" })
    }

    @Test
    fun nativeSessionClosureDropsPairedStateWithoutReplayingDraft() {
        openPairedKeyboard()
        compose.runOnIdle { assertTrue(linkVm.updateKeyboardDraft("unfinished", committed = false)) }
        compose.runOnIdle { source.session.networkClosed() }
        compose.waitUntil(5_000) { !linkVm.ui.value.isPaired }
        assertTrue(linkVm.ui.value.keyboardHalted)
        assertEquals("unfinished", linkVm.ui.value.keyboardDraft)
        assertTrue(linkVm.ui.value.message.contains("not replayed"))
        assertTrue(source.session.events.isEmpty())
    }

    @Test
    fun unsupportedUnicodeIsRejectedWithoutKeyEvents() {
        openPairedKeyboard()
        compose.runOnIdle { assertFalse(linkVm.updateKeyboardDraft("okन", committed = true)) }
        assertTrue(linkVm.ui.value.error.contains("ASCII"))
        assertTrue(source.session.events.isEmpty())
    }

    private fun openPairedKeyboard() {
        compose.onNodeWithTag("open_native_link").performClick()
        compose.waitUntil(5_000) { linkVm.ui.value.devices.isNotEmpty() }
        tap("native_pair")
        compose.waitUntil(5_000) { linkVm.ui.value.isPaired }
        tap("native_tab_keyboard")
        tap("native_keyboard_start")
        compose.waitUntil(5_000) {
            linkVm.ui.value.keyboardStarted && linkVm.ui.value.keyboardVisibility == NativeKeyboardVisibility.VISIBLE &&
                linkVm.ui.value.operation == NativeLinkOperation.NONE
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun findKeyboardInput(): KeyboardBufferEditText {
        return checkNotNull(findKeyboardInputOrNull()) { "Native keyboard EditText was not attached." }
    }

    private fun findKeyboardInputOrNull(): KeyboardBufferEditText? {
        fun find(view: View): KeyboardBufferEditText? {
            if (view is KeyboardBufferEditText) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return find(compose.activity.window.decorView)
    }

    private fun phoneImeVisible(): Boolean = compose.activity.window.decorView.rootWindowInsets
        ?.isVisible(WindowInsets.Type.ime()) == true

    private fun captureSyntheticUi(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.filesDir, "native-link-test-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
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

    private class FakeNativeLinkSource : NativeLinkSource {
        val session = FakeNativeSession()
        val discoveries = AtomicInteger()
        val device = NativeDevice("Synthetic Supernote", "192.168.1.24", "device-1")
        val attempt = FakeNativePair(device.name, session)
        override suspend fun discover(timeoutMillis: Long): List<NativeDevice> {
            discoveries.incrementAndGet()
            return listOf(device)
        }
        override fun preparePair(device: NativeDevice): NativePairPort = attempt

        companion object {
            const val CODE = "481206"
            const val RECEIVED = "Synthetic text from device"
        }
    }

    private class FakeNativePair(
        override val displayName: String,
        private val session: FakeNativeSession,
    ) : NativePairPort {
        override val comparisonCode = FakeNativeLinkSource.CODE
        val awaitCalls = AtomicInteger()
        val cancelled = AtomicInteger()
        var result: CompletableDeferred<NativePairOutcome>? = null
        lateinit var uiCode: () -> String?
        lateinit var uiDisplayName: () -> String?
        var codeWasVisibleBeforeAwait = false
        var displayNameWasVisibleBeforeAwait = false
        override suspend fun awaitApproval(timeoutMillis: Long): NativePairOutcome {
            awaitCalls.incrementAndGet()
            codeWasVisibleBeforeAwait = uiCode() == comparisonCode
            displayNameWasVisibleBeforeAwait = uiDisplayName() == displayName
            return result?.await() ?: NativePairOutcome.Approved(session)
        }
        override fun cancel() { cancelled.incrementAndGet() }
    }

    private class FakeNativeSession : NativeSessionPort {
        private val visibility = MutableStateFlow(NativeKeyboardVisibility.HIDDEN)
        override val keyboardVisibility: StateFlow<NativeKeyboardVisibility> = visibility
        private val closedFlow = MutableStateFlow(false)
        override val isClosed: StateFlow<Boolean> = closedFlow
        val events = CopyOnWriteArrayList<String>()
        val clipboardSends = AtomicInteger()
        val clipboardReceives = AtomicInteger()
        val keyboardOpens = AtomicInteger()
        val keyboardCloses = AtomicInteger()
        val clipboardPreparations = AtomicInteger()
        var receiveGate: CompletableDeferred<String?>? = null
        val pressedKeys = ConcurrentHashMap.newKeySet<String>()
        val closed = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()
        var lastClipboardSend: String? = null
        var firstKeyDownGate: CompletableDeferred<Unit>? = null
            set(value) { field = value; firstKeyDown.set(0) }
        @Volatile var failNextKeyUp = false
        private val firstKeyDown = AtomicInteger()

        override suspend fun openKeyboard() { keyboardOpens.incrementAndGet(); visibility.value = NativeKeyboardVisibility.VISIBLE }
        override suspend fun keyDown(keyCode: Int, metaState: Int) {
            inCall {
                events += "down:$keyCode:$metaState"
                pressedKeys += "$keyCode:$metaState"
                if (firstKeyDown.incrementAndGet() == 1) firstKeyDownGate?.await()
            }
        }
        override suspend fun keyUp(keyCode: Int, metaState: Int) {
            inCall {
                if (failNextKeyUp) { failNextKeyUp = false; error("synthetic socket failure") }
                events += "up:$keyCode:$metaState"
                pressedKeys -= "$keyCode:$metaState"
            }
        }
        override suspend fun closeKeyboard() {
            keyboardCloses.incrementAndGet()
            releasePressedKeys()
            visibility.value = NativeKeyboardVisibility.HIDDEN
        }
        override suspend fun prepareClipboardReceive() { clipboardPreparations.incrementAndGet(); visibility.value = NativeKeyboardVisibility.HIDDEN }
        override suspend fun sendClipboard(text: String) { clipboardSends.incrementAndGet(); lastClipboardSend = text }
        override suspend fun receiveClipboard(timeoutMillis: Long): String? {
            clipboardReceives.incrementAndGet()
            return receiveGate?.await() ?: FakeNativeLinkSource.RECEIVED
        }
        override fun close() {
            closed.incrementAndGet()
            releasePressedKeys()
            closedFlow.value = true
            visibility.value = NativeKeyboardVisibility.UNKNOWN
        }
        fun networkClosed() {
            closedFlow.value = true
            visibility.value = NativeKeyboardVisibility.UNKNOWN
        }

        fun setVisibility(value: NativeKeyboardVisibility) { visibility.value = value }

        private fun releasePressedKeys() {
            pressedKeys.toList().forEach { key ->
                events += "close-up:$key"
                pressedKeys.remove(key)
            }
        }

        private suspend inline fun inCall(crossinline block: suspend () -> Unit) {
            val current = active.incrementAndGet()
            maxConcurrent.updateAndGet { old -> maxOf(old, current) }
            try { block() } finally { active.decrementAndGet() }
        }

        fun printableText(): String = buildString {
            val down = events.filter { it.startsWith("down:") }
            for (event in down) {
                val parts = event.split(':')
                val key = parts[1].toInt()
                val shift = parts[2].toInt() != 0
                when {
                    key in 29..54 -> append(('a'.code + key - 29).toChar().let { if (shift) it.uppercaseChar() else it })
                    key == 62 -> append(' ')
                    key == 70 -> append(if (shift) '+' else '=')
                    else -> Unit
                }
            }
        }
    }
}
