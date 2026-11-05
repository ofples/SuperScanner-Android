package tools.supernote.scan

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Shares one selected original page through Android's system chooser. */
internal object CaptureSharer {
    private const val AUTHORITY_SUFFIX = ".sharefiles"
    private const val MAX_SHARE_FILES = 5
    private const val MAX_SHARE_BYTES = 5L * SessionStore.MAX_PAGE_BYTES
    private const val STALE_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    /** Copy off the UI thread, then open the chooser on the caller's main thread. */
    suspend fun share(
        context: Context,
        page: CapturedPage,
        canLaunch: () -> Boolean = { true },
    ) {
        val appContext = context.applicationContext
        val prepared = withContext(Dispatchers.IO) { prepare(appContext, page) }
        currentCoroutineContext().ensureActive()

        val uri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + AUTHORITY_SUFFIX,
            prepared.file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = prepared.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(appContext.contentResolver, prepared.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share page").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canLaunch()) throw CancellationException("Sharing was cancelled.")
        context.startActivity(chooser)
    }

    private suspend fun prepare(context: Context, page: CapturedPage): PreparedShare {
        val pageRoots = listOf(SessionStore.LIVE_DIRECTORY, SessionStore.SAMPLE_DIRECTORY)
            .map { File(context.filesDir, "$it/pages").canonicalFile }
        val source = File(page.path).canonicalFile
        require(source.parentFile in pageRoots && source.isFile) {
            "This captured page is no longer available in the scan."
        }
        val initialSize = source.length()
        require(initialSize in 1..SessionStore.MAX_PAGE_BYTES.toLong()) {
            "This captured image is empty or too large to share."
        }
        val mimeType = detectMime(source)
        val extension = if (mimeType == "image/png") "png" else "jpg"
        val directory = File(context.cacheDir, "share-captures").apply {
            check(isDirectory || mkdirs()) { "Could not prepare the page for sharing." }
        }.canonicalFile
        val partial = File(directory, "${UUID.randomUUID()}.$extension.tmp")
        val destination = File(directory, "${UUID.randomUUID()}.$extension")

        return try {
            copyBounded(source, partial)
            check(partial.length() == initialSize && source.length() == initialSize) {
                "The captured image changed while it was being prepared."
            }
            check(partial.renameTo(destination)) { "Could not finish preparing the page for sharing." }
            prune(directory, keep = destination)
            PreparedShare(destination, mimeType)
        } catch (cancelled: CancellationException) {
            partial.delete()
            destination.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            destination.delete()
            throw error
        }
    }

    private suspend fun copyBounded(source: File, destination: File) {
        var copied = 0L
        val coroutine = currentCoroutineContext()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= SessionStore.MAX_PAGE_BYTES) {
                        "This captured image is too large to share."
                    }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    private fun detectMime(file: File): String {
        val header = ByteArray(8)
        val count = FileInputStream(file).use { it.read(header) }
        if (count >= pngSignature.size && header.contentEquals(pngSignature)) return "image/png"
        if (count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) {
            return "image/jpeg"
        }
        throw IllegalArgumentException("This captured page is not a supported PNG or JPEG image.")
    }

    /** Keep only recent, bounded files in this feature's private cache directory. */
    private fun prune(directory: File, keep: File) {
        val now = System.currentTimeMillis()
        val files = directory.listFiles()?.filter { it.isFile && it != keep }.orEmpty()
            .sortedByDescending(File::lastModified)
        files.filter { now - it.lastModified() > STALE_AFTER_MILLIS }.forEach(File::delete)

        val retained = directory.listFiles()?.filter { it.isFile }.orEmpty()
            .sortedWith(compareByDescending<File> { it == keep }.thenByDescending(File::lastModified))
        var count = 0
        var bytes = 0L
        retained.forEach { file ->
            if (count >= MAX_SHARE_FILES || bytes + file.length() > MAX_SHARE_BYTES) {
                if (file != keep) file.delete()
            } else {
                count++
                bytes += file.length()
            }
        }
    }

    private data class PreparedShare(val file: File, val mimeType: String)
}
