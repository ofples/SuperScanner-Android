package tools.supernote.scan

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Drives the real scanner UI only with the bundled fictitious sample pages and offline OCR. */
@RunWith(AndroidJUnit4::class)
class SampleScanExperienceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<SampleScanActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = SessionStore(context, SessionStore.SAMPLE_DIRECTORY)
    private val syntheticGalleryUris = mutableListOf<Uri>()

    @Test
    fun capturesPreviewsZoomsSharesSavesAndRecognizesOfflineSample() {
        compose.onNodeWithText("Offline sample").assertExists()
        compose.onNodeWithText("No account or network needed.").assertExists()
        compose.onNodeWithContentDescription("Mirror connection status: Sample mirror").assertExists()
        compose.onNodeWithTag("snap_button").assertExists()
        compose.onNodeWithContentDescription("Snap current page").assertExists()

        // Capture two original, reproducible handwritten-looking sample pages and verify the exact
        // encoded originals stored by the production ViewModel.
        compose.onNodeWithTag("snap_button").performClick()
        compose.waitUntil(15_000) { store.loadSession().pages.size == 1 }
        val first = store.loadSession().pages.single()
        assertArrayEquals(SampleNotes.pages[0].image, File(first.path).readBytes())
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("capture_feedback").assertTextEquals("Page 1 saved.") }.isSuccess
        }

        // Wait out the short save confirmation, then wait for pixels in the live preview to
        // change before snapping. The sample source switches frames asynchronously.
        SystemClock.sleep(1_250)
        compose.waitForIdle()
        val previousLiveImage = captureLatestFrame()
        try {
            compose.onNodeWithTag("next_sample_page").performScrollTo()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("next_sample_page").assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithTag("next_sample_page").performClick()
            compose.waitUntil(10_000) {
                captureLatestFrame().useBitmap { !previousLiveImage.sameAs(it) }
            }
        } finally {
            previousLiveImage.recycle()
        }
        compose.onNodeWithTag("snap_button").assertIsEnabled()
        compose.onNodeWithTag("snap_button").performClick()
        compose.waitUntil(15_000) { store.loadSession().pages.size == 2 }
        val capturedPages = store.loadSession().pages
        assertArrayEquals(SampleNotes.pages[0].image, File(capturedPages[0].path).readBytes())
        assertArrayEquals(SampleNotes.pages[1].image, File(capturedPages[1].path).readBytes())
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("capture_feedback").assertTextEquals("Page 2 saved.") }.isSuccess
        }
        compose.onNodeWithTag("page_thumbnail_row").assertExists()
        compose.onNodeWithContentDescription("Open page 2 preview").performScrollTo().performClick()
        compose.onNodeWithTag("page_zoom_viewer").assertExists()
        compose.onNodeWithContentDescription("Captured page image").assertExists()
        compose.onNodeWithContentDescription("Reset page zoom").assertExists()
        captureEvidence("sample-preview-fit")

        repeat(3) { compose.onNodeWithTag("preview_zoom_in").performClick() }
        compose.onNodeWithContentDescription("Page zoom 195 percent").assertExists()
        val beforePan = captureZoomableImage()
        try {
            compose.onNodeWithContentDescription("Page image. Pinch to zoom, drag to pan, double tap to reset.")
                .performTouchInput { swipeRight() }
            compose.waitUntil(3_000) { captureZoomableImage().useBitmap { !beforePan.sameAs(it) } }
        } finally {
            beforePan.recycle()
        }
        compose.onNodeWithContentDescription("Page zoom 195 percent").assertExists()
        captureEvidence("sample-preview-zoomed")
        compose.onNodeWithTag("preview_zoom_reset").performClick()
        compose.onNodeWithContentDescription("Page zoom 100 percent").assertExists()

        // Exercise the chooser without handing the synthetic image to another application/person.
        Intents.init()
        try {
            intending(anyIntent()).respondWith(android.app.Instrumentation.ActivityResult(
                android.app.Activity.RESULT_OK, android.content.Intent()))
            compose.onNodeWithTag("share_page_original").performClick()
            intended(hasAction(android.content.Intent.ACTION_CHOOSER))
        } finally {
            Intents.release()
        }

        compose.onNodeWithTag("save_page_to_gallery").performClick()
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithText("Saved a copy to Pictures/SuperScanner.").assertExists() }.isSuccess
        }
        assertOwnSyntheticGalleryExport(capturedPages[1])
        captureEvidence("sample-preview-saved")

        compose.onNodeWithText("Back to scan").performClick()
        compose.onNodeWithTag("recognize_button").performScrollTo().performClick()
        val expectedText = SampleNotes.pages.joinToString("\n\n") { it.transcript }
        compose.waitUntil(20_000) { store.loadSession().pages.isEmpty() && store.loadSession().resultText == expectedText }
        compose.onNodeWithTag("recognition_result_text").assertExists()
        compose.onNodeWithContentDescription("Editable recognized text").assertExists()
        compose.onNodeWithText("A slower morning", substring = true).assertExists()
        compose.onNodeWithText("The weekend list", substring = true).assertExists()
        captureEvidence("sample-result-offline")

        compose.onNodeWithTag("back_to_scan").performScrollTo().performClick()
        compose.onNodeWithTag("view_existing_text").assertExists()
        compose.onNodeWithTag("view_existing_text").performClick()
        compose.onNodeWithTag("recognition_result_text").assertExists()
        assertEquals(expectedText, store.loadSession().resultText)
    }

    @After
    fun cleanOnlySampleArtifacts() {
        runCatching { compose.activity.finish() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        syntheticGalleryUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        runCatching {
            store.saveSession(emptyList(), null, "", UUID.randomUUID().toString(), recognitionRunning = false)
            store.clearSavedPages()
        }
    }

    private fun assertOwnSyntheticGalleryExport(page: CapturedPage) {
        val formatted = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(page.capturedAt))
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("SupernoteScan_${formatted}_%.png"),
            null,
        ) ?: error("Could not inspect this uniquely timestamped synthetic Gallery export.")
        cursor.use {
            assertTrue("Expected this sample page to appear in Pictures/SuperScanner", it.moveToFirst())
            assertTrue(it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                .startsWith("SupernoteScan_${formatted}_"))
            assertEquals("Pictures/SuperScanner/", it.getString(
                it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)))
            syntheticGalleryUris += Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)).toString())
            assertTrue("The selected sample timestamp must identify one export", !it.moveToNext())
        }
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not capture synthetic sample screen: $name")
        val folder = File(context.filesDir, "synthetic-evidence").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { output ->
            assertTrue("Synthetic evidence screenshot should be written", image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        image.recycle()
    }

    private fun captureLatestFrame(): Bitmap {
        val nodeBounds = compose.onNodeWithContentDescription("Latest mirror frame").fetchSemanticsNode().boundsInRoot
        val content = compose.activity.findViewById<View>(android.R.id.content)
        val location = IntArray(2).also(content::getLocationOnScreen)
        val density = compose.activity.resources.displayMetrics.density
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not inspect the synthetic mirror frame.")
        val left = (location[0] + nodeBounds.left * density).toInt().coerceIn(0, screen.width - 1)
        val top = (location[1] + nodeBounds.top * density).toInt().coerceIn(0, screen.height - 1)
        val right = (location[0] + nodeBounds.right * density).toInt().coerceIn(left + 1, screen.width)
        val bottom = (location[1] + nodeBounds.bottom * density).toInt().coerceIn(top + 1, screen.height)
        return try { Bitmap.createBitmap(screen, left, top, right - left, bottom - top) }
        finally { screen.recycle() }
    }

    private fun captureZoomableImage(): Bitmap {
        val nodeBounds = compose.onNodeWithContentDescription(
            "Page image. Pinch to zoom, drag to pan, double tap to reset.").fetchSemanticsNode().boundsInRoot
        val content = compose.activity.findViewById<View>(android.R.id.content)
        val location = IntArray(2).also(content::getLocationOnScreen)
        val density = compose.activity.resources.displayMetrics.density
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not inspect the synthetic page preview.")
        val left = (location[0] + nodeBounds.left * density).toInt().coerceIn(0, screen.width - 1)
        val top = (location[1] + nodeBounds.top * density).toInt().coerceIn(0, screen.height - 1)
        val right = (location[0] + nodeBounds.right * density).toInt().coerceIn(left + 1, screen.width)
        val bottom = (location[1] + nodeBounds.bottom * density).toInt().coerceIn(top + 1, screen.height)
        return try { Bitmap.createBitmap(screen, left, top, right - left, bottom - top) }
        finally { screen.recycle() }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }

    companion object {
        @JvmStatic
        @BeforeClass
        fun resetOnlyBundledSampleSession() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = SessionStore(context, SessionStore.SAMPLE_DIRECTORY)
            store.saveSession(emptyList(), null, "", UUID.randomUUID().toString(), recognitionRunning = false)
            store.clearSavedPages()
        }
    }
}
