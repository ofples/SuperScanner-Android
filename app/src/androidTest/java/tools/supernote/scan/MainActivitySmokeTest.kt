package tools.supernote.scan

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionActivityLaunchesWithCaptureDisabledUntilMirrorIsLive() {
        assertEquals("com.yukti.superscanner", compose.activity.packageName)
        assertEquals("SuperScanner", compose.activity.applicationInfo.loadLabel(
            compose.activity.packageManager).toString())
        val provider = compose.activity.packageManager.resolveContentProvider(
            "com.yukti.superscanner.sharefiles", 0)
        assertEquals("com.yukti.superscanner", provider?.applicationInfo?.packageName)
        assertEquals(false, provider?.exported)
        val packageInfo = compose.activity.packageManager.getPackageInfo(
            compose.activity.packageName,
            android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        assertEquals(false, packageInfo.services.orEmpty().any {
            it.name.endsWith("AuthCallbackService")
        })
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertEquals(false, "android.permission.FOREGROUND_SERVICE" in requestedPermissions)
        assertEquals(false, "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in requestedPermissions)
        val sampleActivity = compose.activity.packageManager.getActivityInfo(
            ComponentName(compose.activity, SampleScanActivity::class.java), 0)
        assertEquals("com.yukti.superscanner", sampleActivity.packageName)
        assertEquals(false, sampleActivity.exported)
        compose.onNodeWithTag("snap_button").assertIsNotEnabled()
        compose.onNodeWithTag("open_settings").assertExists()
    }
}
