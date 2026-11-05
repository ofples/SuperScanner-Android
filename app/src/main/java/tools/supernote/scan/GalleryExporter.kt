package tools.supernote.scan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class GalleryInsert(
    val displayName: String,
    val mimeType: String,
    val relativePath: String?,
    val legacyPath: String?,
    val capturedAt: Long,
    val pending: Boolean,
)

internal interface GalleryMediaStore {
    fun insert(values: GalleryInsert): Uri?
    fun openOutput(uri: Uri): OutputStream?
    fun publish(uri: Uri)
    fun delete(uri: Uri)
}

internal data class GalleryExportResult(val displayName: String, val relativePath: String)

internal interface GalleryExportSource {
    suspend fun export(page: CapturedPage): GalleryExportResult
}

/** Copies a captured original to the public image library without decoding or recompressing it. */
internal class GalleryExporter(
    context: Context,
    private val mediaStore: GalleryMediaStore = AndroidGalleryMediaStore(context),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : GalleryExportSource {
    private val appContext = context.applicationContext
    private val pageRoots = listOf(SessionStore.LIVE_DIRECTORY, SessionStore.SAMPLE_DIRECTORY)
        .map { File(appContext.filesDir, "$it/pages").canonicalFile }

    override suspend fun export(page: CapturedPage): GalleryExportResult = withContext(Dispatchers.IO) {
        val source = File(page.path).canonicalFile
        require(source.parentFile in pageRoots && source.isFile) {
            "This captured page is no longer available in the scan."
        }
        val size = source.length()
        require(size in 1..SessionStore.MAX_PAGE_BYTES.toLong()) { "The captured image is empty or too large to export." }
        val mime = detectMime(source)
        val extension = if (mime == "image/png") "png" else "jpg"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(page.capturedAt))
        val displayName = "SupernoteScan_${timestamp}_${UUID.randomUUID().toString().take(8)}.$extension"
        val pending = sdkInt >= Build.VERSION_CODES.Q
        val values = GalleryInsert(
            displayName = displayName,
            mimeType = mime,
            relativePath = if (pending) "${Environment.DIRECTORY_PICTURES}/SuperScanner" else null,
            legacyPath = if (pending) null else File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "SuperScanner/$displayName",
            ).absolutePath,
            capturedAt = page.capturedAt,
            pending = pending,
        )

        var inserted: Uri? = null
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            inserted = mediaStore.insert(values)
                ?: throw IllegalStateException("Android could not create a Gallery image.")
            val destination = mediaStore.openOutput(inserted!!)
                ?: throw IllegalStateException("Android could not open the Gallery image for writing.")
            destination.use { output -> copyOriginal(source, output) }
            currentCoroutineContext().ensureActive()
            mediaStore.publish(inserted!!)
            published = true
            GalleryExportResult(displayName, "Pictures/SuperScanner")
        } catch (cancelled: CancellationException) {
            cleanupIncomplete(inserted, published)
            throw cancelled
        } catch (error: Throwable) {
            cleanupIncomplete(inserted, published)
            throw error
        }
    }

    private suspend fun copyOriginal(source: File, output: OutputStream) {
        val context = currentCoroutineContext()
        FileInputStream(source).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            context.ensureActive()
            output.flush()
        }
    }

    private suspend fun cleanupIncomplete(uri: Uri?, published: Boolean) {
        if (uri == null || published) return
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { mediaStore.delete(uri) }
        }
    }

    private fun detectMime(file: File): String {
        val header = ByteArray(8)
        val count = FileInputStream(file).use { it.read(header) }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (count >= png.size && header.contentEquals(png)) return "image/png"
        if (count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) return "image/jpeg"
        throw IllegalArgumentException("This captured page is not a supported PNG or JPEG image.")
    }
}

private class AndroidGalleryMediaStore(context: Context) : GalleryMediaStore {
    private val resolver = context.applicationContext.contentResolver

    override fun insert(values: GalleryInsert): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, values.displayName)
            put(MediaStore.Images.Media.TITLE, values.displayName.substringBeforeLast('.'))
            put(MediaStore.Images.Media.MIME_TYPE, values.mimeType)
            put(MediaStore.Images.Media.DATE_TAKEN, values.capturedAt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, values.relativePath)
                put(MediaStore.Images.Media.IS_PENDING, if (values.pending) 1 else 0)
            } else {
                val path = values.legacyPath ?: error("Legacy Gallery path is missing.")
                val directory = File(path).parentFile ?: error("Legacy Gallery folder is missing.")
                check(directory.isDirectory || directory.mkdirs()) { "Could not create the Pictures/SuperScanner folder." }
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, path)
            }
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    override fun openOutput(uri: Uri): OutputStream? = resolver.openOutputStream(uri, "w")

    override fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val count = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            check(count == 1) { "Android could not publish the Gallery image." }
        }
    }

    override fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }
}
