package tools.supernote.scan

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Use a browser-owned Custom Tab and its foreground lifecycle instead of a dataSync service. */
internal object SignInBrowser {
    suspend fun launch(activity: Activity, uri: Uri, stillRequested: () -> Boolean) {
        require(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.userInfo == null &&
            uri.port in listOf(-1, 443)) { "The sign-in address could not be verified." }
        val context = activity.applicationContext
        val supportedBrowsers = context.packageManager.queryIntentServices(
            Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0,
        ).mapNotNull { it.serviceInfo?.packageName }.distinct()
        val browserPackage = CustomTabsClient.getPackageName(context, supportedBrowsers)
            ?: error("Use a browser with Custom Tabs to sign in, or choose OpenAI API key.")
        var connection: CustomTabsServiceConnection? = null
        var bound = false
        try {
            val session = withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine<CustomTabsSession?> { continuation ->
                    val browserConnection = object : CustomTabsServiceConnection() {
                        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                            if (continuation.isActive) continuation.resume(client.newSession(null))
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    connection = browserConnection
                    bound = CustomTabsClient.bindCustomTabsService(context, browserPackage, browserConnection)
                    if (!bound && continuation.isActive) continuation.resume(null)
                }
            } ?: error("Could not open the sign-in tab. Try again or use an API key.")
            if (stillRequested()) {
                CustomTabsIntent.Builder(session)
                    .setShowTitle(true)
                    .build()
                    .launchUrl(activity, uri)
            }
        } finally {
            if (bound) connection?.let(context::unbindService)
        }
    }
}
