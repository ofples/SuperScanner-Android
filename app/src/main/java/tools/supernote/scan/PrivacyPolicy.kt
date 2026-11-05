package tools.supernote.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"
internal const val SUPPORT_EMAIL = "plesserofer@gmail.com"

@Composable
internal fun PrivacyPolicyDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("SuperScanner privacy policy") },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PolicySection("On your phone",
                    "SuperScanner stores captures, editable text and settings on this device. " +
                        "Your API key and ChatGPT connection are protected by Android Keystore. " +
                        "There are no ads, analytics or SuperScanner cloud accounts.")
                PolicySection("Recognition",
                    "When you choose to recognize, SuperScanner sends the selected images and your recognition instructions " +
                        "to OpenAI over HTTPS. OpenAI applies its own data and retention policies. " +
                        "Capture, viewing, export and the sample scan do not require OpenAI.")
                PolicySection("ChatGPT sign-in",
                    "The browser handles sign-in with OpenAI. With your permission, eligible requests use your ChatGPT plan. " +
                        "SuperScanner saves the authorization and account identity locally, and does not access your chats.")
                PolicySection("Local Wi-Fi and sharing",
                    "The Supernote mirror and native Linking traffic are not encrypted. Use trusted Wi-Fi. " +
                        "Opening Clipboard may fill the draft from your phone's text clipboard. " +
                        "Clipboard and keyboard actions send data to your paired Supernote when you use them. " +
                        "Gallery copies and items you share are stored outside the app.")
                PolicySection("Delete or disconnect",
                    "Clear pages or remove the saved API key in the app. Disconnect ChatGPT in Settings; " +
                        "you can also revoke its access in ChatGPT settings. Android Clear storage removes local app data. " +
                        "Delete exported and shared copies separately.")
                PolicySection("Contact", "For privacy or support questions: $SUPPORT_EMAIL.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun PolicySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
