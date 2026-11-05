package tools.supernote.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tools.superscanner.clipboard.PairStatus

@Composable
internal fun ClipboardScreen(
    vm: ClipboardViewModel,
    context: Context,
    recognizedText: String,
    onReadPairingCodeFromMirror: () -> Unit,
) {
    val state by vm.ui.collectAsState()
    val isBusy = state.operation != ClipboardOperation.NONE
    val sendBytes = remember(state.sendText) { state.sendText.toByteArray(Charsets.UTF_8).size }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Supernote helper clipboard", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Experimental", fontWeight = FontWeight.SemiBold)
                Text("This sends text to a separate Supernote helper. Native Notes clipboard behavior is still being verified.")
                Text("Pair only while both screens are visible. Match the short phone code below with the helper and approve there.")
            }
        }

        Text("Pairing comparison code", fontWeight = FontWeight.SemiBold)
        Text(state.approvalCode ?: "Enter the helper code and tap Pair to prepare a comparison code.",
            style = if (state.approvalCode != null) MaterialTheme.typography.headlineMedium
                else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = state.approvalCode?.let { "Pairing comparison code: $it" }
                    ?: "Pairing comparison code will appear after Pair"
            }.testTag("clipboard_phone_code"))

        OutlinedTextField(
            value = state.pairingCode,
            onValueChange = vm::updatePairingCode,
            label = { Text("Paste helper pairing code") },
            placeholder = { Text("superscanner-clipboard://pair…") },
            minLines = 2,
            maxLines = 4,
            enabled = !isBusy && !state.isPaired,
            modifier = Modifier.fillMaxWidth().testTag("clipboard_pairing_code"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onReadPairingCodeFromMirror, enabled = !isBusy && !state.isPaired,
                modifier = Modifier.weight(1f).height(52.dp).testTag("clipboard_read_pair_qr")) {
                Text("Read QR from mirror")
            }
            Button(onClick = vm::pair, enabled = !isBusy && !state.isPaired && state.pairingCode.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp).testTag("clipboard_pair")) {
                Text("Pair")
            }
        }
        Text(when {
            state.isPaired -> "Approved by helper"
            state.pairingStatus == PairStatus.PENDING -> "Waiting for approval on helper…"
            state.pairingStatus == PairStatus.REJECTED -> "Pairing rejected"
            else -> "Not paired"
        }, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("clipboard_pair_status"))

        if (isBusy) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(when (state.operation) {
                    ClipboardOperation.PAIRING -> if (state.approvalCode == null)
                        "Preparing comparison code…" else "Waiting for helper approval…"
                    ClipboardOperation.SENDING -> "Sending text…"
                    ClipboardOperation.RECEIVING -> "Receiving text…"
                    ClipboardOperation.NONE -> ""
                }, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cancelOperation, modifier = Modifier.testTag("clipboard_cancel")) {
                    Text("Cancel")
                }
            }
        }

        if (state.isPaired) {
            Text("Send to helper", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                vm.pastePhoneClipboard(text)
            }, enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("clipboard_paste_phone")) {
                Text("Paste phone clipboard")
            }
            OutlinedTextField(
                value = state.sendText,
                onValueChange = vm::updateSendText,
                label = { Text("Text to send") },
                minLines = 4,
                maxLines = 9,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().testTag("clipboard_send_text"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            Text("$sendBytes / ${ClipboardViewModel.MAX_TEXT_BYTES} UTF-8 bytes")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recognizedText.isNotBlank()) {
                    OutlinedButton(onClick = { vm.useRecognizedText(recognizedText) }, enabled = !isBusy,
                        modifier = Modifier.weight(1f).height(52.dp).testTag("clipboard_use_recognized")) {
                        Text("Use recognized text")
                    }
                }
                Button(onClick = vm::send, enabled = !isBusy && sendBytes <= ClipboardViewModel.MAX_TEXT_BYTES,
                    modifier = Modifier.weight(1f).height(52.dp).testTag("clipboard_send")) {
                    Text("Send")
                }
            }

            Text("Receive from helper", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = vm::receive, enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("clipboard_receive")) {
                Text("Receive text")
            }
            if (state.receivedText.isNotEmpty()) {
                OutlinedTextField(value = state.receivedText, onValueChange = {}, readOnly = true,
                    label = { Text("Received text") }, minLines = 3, maxLines = 10,
                    modifier = Modifier.fillMaxWidth().testTag("clipboard_received_text"))
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Supernote text", state.receivedText))
                    vm.showCopiedMessage()
                }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("clipboard_copy_received")) {
                    Text("Copy received text")
                }
            }
        }

        TextButton(onClick = vm::forget,
            modifier = Modifier.fillMaxWidth().testTag("clipboard_forget")) {
            Text("Forget pairing and clear local text")
        }

        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = state.error }.testTag("clipboard_error"))
        if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = state.message }.testTag("clipboard_message"))
        Spacer(Modifier.height(10.dp))
    }
}
