package tools.supernote.scan

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.view.View
import kotlinx.coroutines.delay
import tools.superscanner.nativelink.NativeKeyboardVisibility

@Composable
internal fun NativeLinkScreen(vm: NativeLinkViewModel) {
    val state by vm.ui.collectAsState()
    val busy = state.operation != NativeLinkOperation.NONE
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var windowFocused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    var activityResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var keyboardField by remember { mutableStateOf<KeyboardBufferEditText?>(null) }
    var selectedPane by remember { mutableStateOf("clipboard") }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> windowFocused = hasFocus }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            else if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { owner, _ ->
            activityResumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(selectedPane, state.isPaired, windowFocused, activityResumed) {
        if (selectedPane == "clipboard" && state.isPaired) {
            vm.enterClipboardPane(windowFocused = windowFocused && activityResumed)
        }
    }
    LaunchedEffect(state.isPaired, state.keyboardStarted) {
        if (!state.isPaired) {
            selectedPane = "clipboard"
            keyboardField?.clearFocus()
            keyboardField = null
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text("Experimental native Link. Clipboard and keyboard data travel as plaintext over Wi-Fi. Don’t send sensitive text; check each action on Supernote.",
                Modifier.fillMaxWidth().padding(14.dp))
        }

        if (state.isPaired) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Connected", style = MaterialTheme.typography.labelMedium)
                    Text(state.pairedName, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("native_paired_status"))
                }
                OutlinedButton(onClick = vm::disconnect, enabled = !busy,
                    modifier = Modifier.height(48.dp).testTag("native_disconnect")) { Text("Disconnect") }
            }
            fun selectPane(pane: String) {
                if (pane == "keyboard" && selectedPane != "keyboard") vm.cancelClipboardReceive()
                if (selectedPane == "keyboard" && pane != "keyboard") {
                    keyboardField?.clearFocus()
                    vm.stopKeyboardSharing()
                }
                selectedPane = pane
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val clipboardModifier = Modifier.weight(1f).height(56.dp).testTag("native_tab_clipboard")
                if (selectedPane == "clipboard") {
                    Button(onClick = { selectPane("clipboard") }, modifier = clipboardModifier) { Text("Clipboard") }
                } else {
                    OutlinedButton(onClick = { selectPane("clipboard") }, modifier = clipboardModifier) { Text("Clipboard") }
                }
                val keyboardModifier = Modifier.weight(1f).height(56.dp).testTag("native_tab_keyboard")
                if (selectedPane == "keyboard") {
                    Button(onClick = { selectPane("keyboard") }, modifier = keyboardModifier) { Text("Keyboard") }
                } else {
                    OutlinedButton(onClick = { selectPane("keyboard") }, modifier = keyboardModifier) { Text("Keyboard") }
                }
            }
            if (selectedPane == "clipboard") {
                ClipboardActions(vm, state)
            } else {
                KeyboardActions(vm, state, keyboardField, onInputReady = { keyboardField = it })
            }
        } else {
            Text("Available Supernotes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.devices.isEmpty() && !busy) {
                Text("Keep Supernote Linking open on the tablet and connect both devices to the same Wi-Fi.")
            }
            state.devices.forEachIndexed { index, device ->
                val selected = device.identifier == state.selectedDeviceId
                OutlinedButton(
                    onClick = { vm.selectDevice(device) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("native_device_$index"),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text(device.ipv4, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::discover, enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp).testTag("native_discover")) {
                    Text("Search Wi-Fi")
                }
                Button(onClick = vm::pair, enabled = !busy && state.selectedDeviceId != null,
                    modifier = Modifier.weight(1f).height(52.dp).testTag("native_pair")) { Text("Pair") }
            }
            if (state.operation == NativeLinkOperation.DISCOVERING) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Searching nearby Supernotes…")
                }
            }
            if (state.comparisonCode != null || state.operation == NativeLinkOperation.PAIRING) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Match this code on your Supernote", fontWeight = FontWeight.SemiBold)
                        state.pairDisplayName?.let { name ->
                            Text("Device shown on tablet: $name", textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("native_pair_display_name"))
                        }
                        Text(state.comparisonCode ?: "Preparing…", style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("native_pair_code"))
                        Text("Approve on the tablet only after the displayed code matches.", textAlign = TextAlign.Center)
                        if (busy) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator()
                                Text("Waiting for the Supernote…")
                                TextButton(onClick = vm::cancelPairing, modifier = Modifier.testTag("native_cancel_pair")) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = state.error }.testTag("native_error"))
        if (state.message.isNotBlank() && state.operation != NativeLinkOperation.RECEIVING_CLIPBOARD) Text(state.message, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = state.message }.testTag("native_message"))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ClipboardActions(vm: NativeLinkViewModel, state: NativeLinkUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Clipboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Edit the text below, then send it. Receive waits for a new copy on Supernote and places it here and on your phone clipboard.")
        OutlinedTextField(value = state.clipboardDraft,
            onValueChange = { vm.updateClipboardDraft(it) },
            enabled = state.operation == NativeLinkOperation.NONE && state.isPaired,
            label = { Text("Text to send") }, minLines = 4, maxLines = 10,
            modifier = Modifier.fillMaxWidth().testTag("native_clipboard_draft"))
        Text("${state.clipboardDraft.toByteArray(Charsets.UTF_8).size} / ${NativeLinkViewModel.MAX_CLIPBOARD_BYTES} bytes",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::sendClipboard,
                enabled = state.operation == NativeLinkOperation.NONE && state.isPaired && state.clipboardDraft.isNotEmpty(),
                modifier = Modifier.weight(1f).height(56.dp).testTag("native_send_clipboard")) {
                Text("Send")
            }
            OutlinedButton(onClick = vm::clearClipboardDraft,
                enabled = state.operation == NativeLinkOperation.NONE && state.isPaired,
                modifier = Modifier.weight(1f).height(56.dp).testTag("native_clear_clipboard_draft")) {
                Text("Clear")
            }
        }
        if (state.operation == NativeLinkOperation.RECEIVING_CLIPBOARD) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().testTag("native_clipboard_receiving")) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Copy text on your Supernote…", modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::cancelClipboardReceive,
                        modifier = Modifier.testTag("native_cancel_clipboard_receive")) { Text("Cancel") }
                }
            }
        } else {
            Button(onClick = vm::receiveClipboard,
                enabled = state.operation == NativeLinkOperation.NONE && state.isPaired,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("native_receive_clipboard")) {
                Text("Receive")
            }
        }
    }
}

@Composable
private fun KeyboardActions(
    vm: NativeLinkViewModel,
    state: NativeLinkUiState,
    field: KeyboardBufferEditText?,
    onInputReady: (KeyboardBufferEditText) -> Unit,
) {
    var previousModelDraft by remember { mutableStateOf(state.keyboardDraft) }
    var showKeyboardHelp by remember { mutableStateOf(false) }
    var editorFocused by remember(field) { mutableStateOf(field?.hasFocus() == true) }
    var previousEditorFocused by remember(field) { mutableStateOf(false) }
    val bringEditorIntoView = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeBottom = imeInsets.getBottom(density)
    var previousImeBottom by remember { mutableStateOf(imeBottom) }

    LaunchedEffect(editorFocused, imeBottom) {
        val focusGained = editorFocused && !previousEditorFocused
        val imeExpanded = imeBottom > previousImeBottom
        previousEditorFocused = editorFocused
        previousImeBottom = imeBottom
        if (editorFocused && imeBottom > 0 && focusGained) {
            bringEditorIntoView.bringIntoView()
        } else if (editorFocused && imeExpanded) {
            // IME insets animate over several frames; wait for the new size to settle before scrolling.
            delay(100)
            if (imeInsets.getBottom(density) >= imeBottom) bringEditorIntoView.bringIntoView()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Keyboard: ${when (state.keyboardVisibility) {
            NativeKeyboardVisibility.VISIBLE -> "visible on Supernote"
            NativeKeyboardVisibility.HIDDEN -> "hidden on Supernote"
            NativeKeyboardVisibility.UNKNOWN -> "not confirmed"
        }}", modifier = Modifier.testTag("native_keyboard_visibility"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Phone typing buffer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = { showKeyboardHelp = true },
                modifier = Modifier.height(48.dp).semantics { contentDescription = "Typing help" }.testTag("native_keyboard_help"),
            ) { Text("?") }
        }
        if (!state.keyboardStarted || state.keyboardVisibility != NativeKeyboardVisibility.VISIBLE) {
            Button(onClick = vm::startTyping, enabled = state.operation == NativeLinkOperation.NONE,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("native_keyboard_start")) {
                Text(if (state.keyboardStarted) "Open typing keyboard" else "Start typing")
            }
        }
        if (state.keyboardStarted) {
            val canEdit = (state.operation == NativeLinkOperation.NONE || state.operation == NativeLinkOperation.SENDING_KEYS) && !state.keyboardHalted
            Text("Shortcuts act on the active Supernote field.", style = MaterialTheme.typography.bodySmall)
            val controlsEnabled = state.operation == NativeLinkOperation.NONE &&
                state.keyboardVisibility == NativeKeyboardVisibility.VISIBLE && !state.keyboardHalted
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (commitPhoneComposition(field, vm)) vm.remoteSelectAll() },
                    enabled = controlsEnabled, modifier = Modifier.weight(1f).height(48.dp).testTag("native_keyboard_select_all")) { Text("Select all") }
                OutlinedButton(onClick = { if (commitPhoneComposition(field, vm)) vm.remoteCut() },
                    enabled = controlsEnabled, modifier = Modifier.weight(1f).height(48.dp).testTag("native_keyboard_cut")) { Text("Cut") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (commitPhoneComposition(field, vm)) vm.remoteCopy() },
                    enabled = controlsEnabled, modifier = Modifier.weight(1f).height(48.dp).testTag("native_keyboard_copy")) { Text("Copy") }
                OutlinedButton(onClick = { if (commitPhoneComposition(field, vm)) vm.remotePaste() },
                    enabled = controlsEnabled, modifier = Modifier.weight(1f).height(48.dp).testTag("native_keyboard_paste")) { Text("Paste") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (commitPhoneComposition(field, vm)) vm.remoteBackspace()
                }, enabled = controlsEnabled,
                    modifier = Modifier.weight(1f).height(52.dp).semantics { contentDescription = "Send Backspace to Supernote" }.testTag("native_keyboard_backspace")) {
                    Text("Backspace")
                }
                OutlinedButton(onClick = {
                    if (commitPhoneComposition(field, vm)) vm.remoteEnter()
                }, enabled = controlsEnabled,
                    modifier = Modifier.weight(1f).height(52.dp).semantics { contentDescription = "Send Enter to Supernote" }.testTag("native_keyboard_enter")) {
                    Text("Enter")
                }
                Button(onClick = {
                    if (commitPhoneComposition(field, vm)) vm.doneTyping()
                }, enabled = controlsEnabled,
                    modifier = Modifier.weight(1f).height(52.dp).semantics { contentDescription = "Finish typing on Supernote" }.testTag("native_keyboard_done")) {
                    Text("Done")
                }
            }
            Box(Modifier.fillMaxWidth().height(70.dp).bringIntoViewRequester(bringEditorIntoView)) {
                AndroidView(
                    factory = { context ->
                        KeyboardBufferEditText(context).also { input ->
                            input.onDraftChanged = { text, committed ->
                                if (!vm.updateKeyboardDraft(text, committed)) {
                                    val saved = vm.ui.value.keyboardDraft
                                    input.syncBuffer(saved, saved)
                                }
                            }
                            input.onPhoneBackspace = { text, start, end, composing, perform, count ->
                                vm.handlePhoneBackspace(text, start, end, composing, perform, count)
                            }
                            input.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> editorFocused = hasFocus }
                            onInputReady(input)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp).testTag("native_keyboard_input"),
                    update = { input ->
                        input.onDraftChanged = { text, committed ->
                            if (!vm.updateKeyboardDraft(text, committed)) {
                                val saved = vm.ui.value.keyboardDraft
                                input.syncBuffer(saved, saved)
                            }
                        }
                        input.onPhoneBackspace = { text, start, end, composing, perform, count ->
                            vm.handlePhoneBackspace(text, start, end, composing, perform, count)
                        }
                        input.isEnabled = canEdit
                        input.isFocusable = true
                        input.isFocusableInTouchMode = true
                        input.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> editorFocused = hasFocus }
                        input.syncBuffer(state.keyboardDraft, previousModelDraft)
                        previousModelDraft = state.keyboardDraft
                        onInputReady(input)
                    },
                )
            }
        }
    }

    if (showKeyboardHelp) {
        AlertDialog(
            onDismissRequest = { showKeyboardHelp = false },
            title = { Text("Typing on Supernote") },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Open a text field on Supernote and approve the native keyboard prompt. Tap Start typing, then tap the phone typing buffer.")
                    Text("Committed English/ASCII text is sent after a short pause. Phone Backspace edits unsent text first; when the buffer is empty it sends Backspace to Supernote.")
                    Text("Select all, Cut, Copy, Paste, and Enter act on the active Supernote field. Paste uses the Supernote clipboard. Use Done to finish typing.")
                }
            },
            confirmButton = { TextButton(onClick = { showKeyboardHelp = false }) { Text("Got it") } },
            modifier = Modifier.testTag("native_keyboard_help_dialog"),
        )
    }
}

private fun commitPhoneComposition(field: KeyboardBufferEditText?, vm: NativeLinkViewModel): Boolean {
    field?.finishComposition()
    return vm.updateKeyboardDraft(field?.text?.toString() ?: vm.ui.value.keyboardDraft, committed = true)
}
