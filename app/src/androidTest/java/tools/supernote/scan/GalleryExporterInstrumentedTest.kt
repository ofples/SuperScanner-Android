package tools.supernote.scan

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryExporterInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportsSyntheticPngThroughPlatformMediaStoreAndPublishesIt() = runBlocking {
        org.junit.Assume.assumeTrue("MediaStore integration requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val pagesDirectory = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val pageFile = File(pagesDirectory, "gallery-platform-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.rgb(21, 87, 143))
                drawPoint(1f, 1f, android.graphics.Paint().apply { color = Color.WHITE; strokeWidth = 1f })
            }
        }
        try {
            pageFile.outputStream().use { output ->
                assertTrue("Synthetic PNG should compress", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        val original = pageFile.readBytes()
        var exportedUri: Uri? = null
        try {
            val result = GalleryExporter(context).export(
                CapturedPage("platform-gallery-${UUID.randomUUID()}", pageFile.absolutePath, 3, 2, System.currentTimeMillis()),
            )

            val projection = buildList {
                add(MediaStore.Images.Media._ID)
                add(MediaStore.Images.Media.DISPLAY_NAME)
                add(MediaStore.Images.Media.MIME_TYPE)
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            }.toTypedArray()
            val matches = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(result.displayName),
                null,
            ) ?: error("MediaStore query returned no cursor")
            matches.use { cursor ->
                assertTrue("Expected exactly one row for this unique export", cursor.moveToFirst())
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                exportedUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                assertEquals(result.displayName, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)))
                assertEquals("image/png", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)))
                assertEquals("Pictures/SuperScanner/", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)))
                assertTrue("Only the uniquely named export should match", !cursor.moveToNext())
            }

            val uri = requireNotNull(exportedUri)
            val exported = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read the exported MediaStore item")
            val decoded = BitmapFactory.decodeByteArray(exported, 0, exported.size)
            assertTrue("Export should contain a decodable PNG", decoded != null)
            decoded?.recycle()
            assertArrayEquals(original, exported)
            assertArrayEquals("The private captured page must remain unchanged", original, pageFile.readBytes())
        } finally {
            exportedUri?.let { context.contentResolver.delete(it, null, null) }
            pageFile.delete()
        }
    }

    @Test fun exportsExactOriginalPngAndJpegBytesWithDetectedMimeAndDoesNotChangeCapture() = runBlocking {
        val pagesDirectory = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 4, 5)
        val jpegBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
        val pngFile = File(pagesDirectory, "gallery-test-${UUID.randomUUID()}.jpg").apply { writeBytes(pngBytes) }
        val jpegFile = File(pagesDirectory, "gallery-test-${UUID.randomUUID()}.png").apply { writeBytes(jpegBytes) }
        try {
            val pngStore = FakeGalleryMediaStore()
            val pngResult = GalleryExporter(context, pngStore, sdkInt = 35).export(
                CapturedPage("png-original", pngFile.absolutePath, 2, 2, 1_700_000_000_000L))
            assertEquals("image/png", pngStore.inserted?.mimeType)
            assertTrue(pngResult.displayName.endsWith(".png"))
            assertEquals("Pictures/SuperScanner", pngStore.inserted?.relativePath)
            assertEquals(null, pngStore.inserted?.legacyPath)
            assertTrue(pngStore.inserted?.pending == true)
            assertArrayEquals(pngBytes, pngStore.outputBytes())
            assertEquals(pngStore.uri, pngStore.publishedUri)
            assertArrayEquals(pngBytes, pngFile.readBytes())

            val jpegStore = FakeGalleryMediaStore()
            val jpegResult = GalleryExporter(context, jpegStore, sdkInt = 35).export(
                CapturedPage("jpeg-original", jpegFile.absolutePath, 2, 2, 1_700_000_000_001L))
            assertEquals("image/jpeg", jpegStore.inserted?.mimeType)
            assertTrue(jpegResult.displayName.endsWith(".jpg"))
            assertArrayEquals(jpegBytes, jpegStore.outputBytes())
            assertArrayEquals(jpegBytes, jpegFile.readBytes())
        } finally {
            pngFile.delete()
            jpegFile.delete()
        }
    }

    @Test fun legacyAndroidUsesPicturesPathAndFailedWriteDeletesOnlyItsOwnInsert() = runBlocking {
        val pagesDirectory = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7)
        val pageFile = File(pagesDirectory, "gallery-legacy-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
        try {
            val store = FakeGalleryMediaStore(failWrites = true)
            val failure = runCatching {
                GalleryExporter(context, store, sdkInt = 28).export(
                    CapturedPage("legacy-id", pageFile.absolutePath, 1, 1, 1_700_000_000_000L))
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(null, store.inserted?.relativePath)
            assertTrue(store.inserted?.legacyPath.orEmpty().contains("Pictures${File.separator}SuperScanner"))
            assertEquals(listOf(store.uri), store.deleted)
            assertEquals(null, store.publishedUri)
            assertArrayEquals(bytes, pageFile.readBytes())
        } finally {
            pageFile.delete()
        }
    }

    @Test fun repeatedExportsOfSameCaptureUseDistinctGalleryNamesAndLegacyPaths() = runBlocking {
        val pagesDirectory = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 9, 8, 7)
        val pageFile = File(pagesDirectory, "gallery-repeat-${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
        try {
            val page = CapturedPage("same-original-page", pageFile.absolutePath, 1, 1, 1_700_000_000_000L)
            val firstStore = FakeGalleryMediaStore()
            val first = GalleryExporter(context, firstStore, sdkInt = 28).export(page)
            val secondStore = FakeGalleryMediaStore()
            val second = GalleryExporter(context, secondStore, sdkInt = 28).export(page)

            assertTrue(first.displayName != second.displayName)
            assertTrue(firstStore.inserted?.legacyPath != secondStore.inserted?.legacyPath)
            assertArrayEquals(bytes, pageFile.readBytes())
        } finally {
            pageFile.delete()
        }
    }

    @Test fun cancellationDuringWriteRemovesOnlyThePendingInsert() = runBlocking {
        val pagesDirectory = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val bytes = ByteArray(64 * 1024) { 0x55 }
        bytes[0] = 0x89.toByte(); bytes[1] = 0x50; bytes[2] = 0x4e; bytes[3] = 0x47
        bytes[4] = 0x0d; bytes[5] = 0x0a; bytes[6] = 0x1a; bytes[7] = 0x0a
        val pageFile = File(pagesDirectory, "gallery-cancel-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
        val jobRef = AtomicReference<Job?>()
        val store = FakeGalleryMediaStore(onWrite = { jobRef.get()?.cancel() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            GalleryExporter(context, store, sdkInt = 35).export(
                CapturedPage("cancel-id", pageFile.absolutePath, 1, 1, 1_700_000_000_000L))
        }
        jobRef.set(job)
        try {
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf(store.uri), store.deleted)
            assertEquals(null, store.publishedUri)
            assertArrayEquals(bytes, pageFile.readBytes())
        } finally {
            scope.cancel()
            pageFile.delete()
        }
    }

    private class FakeGalleryMediaStore(
        private val failWrites: Boolean = false,
        private val onWrite: () -> Unit = {},
    ) : GalleryMediaStore {
        val uri: Uri = Uri.parse("content://gallery/${UUID.randomUUID()}")
        var inserted: GalleryInsert? = null
        var publishedUri: Uri? = null
        val deleted = mutableListOf<Uri>()
        private val bytes = ByteArrayOutputStream()

        override fun insert(values: GalleryInsert): Uri {
            inserted = values
            return uri
        }

        override fun openOutput(uri: Uri): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                onWrite()
                if (failWrites) throw IOException("fixture write failed")
                bytes.write(value)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                onWrite()
                if (failWrites) throw IOException("fixture write failed")
                bytes.write(buffer, offset, length)
            }
        }

        override fun publish(uri: Uri) { publishedUri = uri }
        override fun delete(uri: Uri) { deleted += uri }
        fun outputBytes(): ByteArray = bytes.toByteArray()
    }
}
