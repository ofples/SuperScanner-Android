package tools.supernote.scan

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** App-private, bounded storage for original page bytes and editable session text. */
internal class SessionStore(context: Context, directoryName: String = LIVE_DIRECTORY) {
    private val root = File(context.filesDir, directoryName).apply { mkdirs() }
    private val sharedLock = storeLock(root.canonicalPath)
    private val pagesDir = File(root, "pages").apply { mkdirs() }
    private val sessionFile = File(root, "session.json")
    private val settingsFile = File(root, "settings.json")

    companion object {
        const val LIVE_DIRECTORY = "scan-session"
        const val SAMPLE_DIRECTORY = "sample-scan"
        const val MAX_PAGES = 12
        const val MAX_PAGE_BYTES = 12 * 1024 * 1024
        const val MAX_SESSION_BYTES = 48L * 1024 * 1024
        private const val MAX_PENDING_DELETE_FILES = 128

        private val storeLocks = ConcurrentHashMap<String, Any>()
        private fun storeLock(path: String): Any = storeLocks.computeIfAbsent(path) { Any() }
    }

    data class SavedSession(
        val pages: List<CapturedPage>,
        val host: String?,
        val resultText: String,
        val interruptedRecognition: Boolean,
        val sessionId: String,
        val storageError: String? = null,
    )

    fun loadSession(): SavedSession {
        return synchronized(sharedLock) {
            if (!sessionFile.exists() && !File(sessionFile.path + ".bak").exists()) {
                return@synchronized SavedSession(emptyList(), null, "", false, UUID.randomUUID().toString())
                }
            var sessionJson: JSONObject? = null
            val saved = runCatching {
                val json = JSONObject(AtomicFile(sessionFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                sessionJson = json
                var unavailablePage = false
                val pages = buildList {
                    val array = json.optJSONArray("pages") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i)
                        if (item == null) {
                            unavailablePage = true
                            continue
                        }
                        val path = File(pagesDir, item.optString("file")).canonicalFile
                        if (path.parentFile != pagesDir.canonicalFile || !path.isFile) {
                            unavailablePage = true
                            continue
                        }
                        add(CapturedPage(item.optString("id"), path.absolutePath,
                            item.optInt("width"), item.optInt("height"), item.optLong("capturedAt")))
                    }
                }.take(MAX_PAGES)
                SavedSession(pages, json.optString("host").takeIf { it.isNotBlank() },
                    json.optString("resultText"), json.optBoolean("recognitionRunning"),
                    json.optString("sessionId").ifBlank { UUID.randomUUID().toString() },
                    storageError = if (unavailablePage) "Some saved captures are unavailable. The original scan metadata has been preserved." else null)
            }.getOrElse { error ->
                SavedSession(emptyList(), null, "", false, UUID.randomUUID().toString(),
                    "Saved scan state could not be read. Captured page files were preserved. ${error.message ?: ""}".trim())
            }
            // Preserve page bytes left by a crash between saving a page and committing its metadata.
            if (saved.storageError != null) return@synchronized saved
            val knownFiles = saved.pages.map { File(it.path).name }.toSet()
            val pending = safePendingDeleteNames(sessionJson?.optJSONArray("pendingDeleteFiles"))
                .filterNot { it in knownFiles }
            val remaining = pending.filterNot(::deletePendingPageFileLocked)
            if (remaining != pending) {
                val json = sessionJson
                if (json != null) runCatching { writePendingDeleteNamesLocked(json, remaining) }
            }
            val accountedFiles = knownFiles + pending
            val hasUncommittedFiles = pagesDir.listFiles()?.any { it.isFile && it.name !in accountedFiles } == true
            if (hasUncommittedFiles) saved.copy(storageError =
                "Some saved page files were not committed to the session and have been preserved. Start a fresh scan to clear them.")
            else saved
        }
    }

    fun savePage(frame: MirrorFrame, currentPages: List<CapturedPage>): CapturedPage {
        return synchronized(sharedLock) {
            require(currentPages.size < MAX_PAGES) { "A scan can contain up to $MAX_PAGES pages." }
            require(frame.bytes.isNotEmpty() && frame.bytes.size <= MAX_PAGE_BYTES) {
                "This image is larger than the ${MAX_PAGE_BYTES / (1024 * 1024)} MB page limit."
            }
            val used = pagesDir.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
            require(used + frame.bytes.size <= MAX_SESSION_BYTES) { "The scan has reached its storage limit." }
            val id = UUID.randomUUID().toString()
            val extension = if (frame.mimeType.equals("image/png", ignoreCase = true)) "png" else "jpg"
            val target = File(pagesDir, "$id.$extension")
            val temp = File(pagesDir, "$id.tmp")
            try {
                FileOutputStream(temp).use { stream -> stream.write(frame.bytes); stream.fd.sync() }
                check(temp.renameTo(target)) { "Could not save the captured page." }
            } finally {
                temp.delete()
            }
            CapturedPage(id, target.absolutePath, frame.width, frame.height, frame.receivedAt)
        }
    }

    fun saveSession(pages: List<CapturedPage>, host: String?, resultText: String,
                    sessionId: String, recognitionRunning: Boolean) {
        synchronized(sharedLock) {
            val referenced = pages.map { File(it.path).name }.toSet()
            writeSessionLocked(pages, host, resultText, sessionId, recognitionRunning,
                readPendingDeleteNamesLocked().filterNot { it in referenced })
        }
    }

    /** Commit a new page list and text before retiring any original image files. */
    fun saveSessionAndRetirePages(pages: List<CapturedPage>, host: String?, resultText: String,
                                  sessionId: String, recognitionRunning: Boolean,
                                  retiredPages: List<CapturedPage>): Boolean {
        synchronized(sharedLock) {
            val referenced = pages.map { pageFileName(it.path) }.toSet()
            val retired = retiredPages.map { pageFileName(it.path) }.distinct()
                .filterNot { it in referenced }
            val pending = (readPendingDeleteNamesLocked() + retired).distinct()
                .filterNot { it in referenced }
            require(pending.size <= MAX_PENDING_DELETE_FILES) { "Too many saved pages are waiting for cleanup." }

            // AtomicFile makes the new text/page references durable before old images can be deleted.
            writeSessionLocked(pages, host, resultText, sessionId, recognitionRunning, pending)

            // Deletion is best-effort after the commit. Any failures stay explicitly recorded for
            // retry on a later load; never restore deleted page references or roll back the text.
            val remaining = pending.filterNot(::deletePendingPageFileLocked)
            if (remaining != pending) runCatching { writeSessionLocked(pages, host, resultText,
                sessionId, recognitionRunning, remaining) }
            return remaining.isNotEmpty()
        }
    }

    private fun writeSessionLocked(pages: List<CapturedPage>, host: String?, resultText: String,
                                   sessionId: String, recognitionRunning: Boolean,
                                   pendingDeleteFiles: List<String>) {
            val json = JSONObject()
                .put("version", 1)
                .put("host", host ?: "")
                .put("resultText", resultText)
                .put("sessionId", sessionId)
                .put("recognitionRunning", recognitionRunning)
            val array = JSONArray()
            require(pages.size <= MAX_PAGES) { "A scan can contain up to $MAX_PAGES pages." }
            pages.forEach { page ->
                val file = File(page.path)
                check(file.isFile && file.canonicalFile.parentFile == pagesDir.canonicalFile) {
                    "A saved capture is unavailable. The previous scan state was preserved."
                }
                // Never turn a temporarily unavailable capture into an intentional removal.
                array.put(JSONObject().put("id", page.id).put("file", file.name)
                    .put("width", page.width).put("height", page.height).put("capturedAt", page.capturedAt))
            }
            json.put("pages", array)
            json.put("pendingDeleteFiles", JSONArray().also { pending ->
                pendingDeleteFiles.filter(::isSafePageFileName).forEach(pending::put)
            })
            atomicText(sessionFile, json.toString())
    }

    private fun readPendingDeleteNamesLocked(): List<String> = runCatching {
        if (!sessionFile.exists() && !File(sessionFile.path + ".bak").exists()) return@runCatching emptyList()
        val json = JSONObject(AtomicFile(sessionFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        safePendingDeleteNames(json.optJSONArray("pendingDeleteFiles"))
    }.getOrDefault(emptyList())

    private fun safePendingDeleteNames(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val name = array.optString(i)
            if (isSafePageFileName(name) && name !in this) add(name)
            if (size >= MAX_PENDING_DELETE_FILES) break
        }
    }

    private fun pageFileName(path: String): String {
        val file = File(path)
        require(file.canonicalFile.parentFile == pagesDir.canonicalFile && isSafePageFileName(file.name)) {
            "A page to retire is outside the app's saved-page directory."
        }
        return file.name
    }

    private fun isSafePageFileName(name: String): Boolean =
        name == File(name).name && !name.contains('\\') &&
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(png|jpg)").matches(name)

    private fun deletePendingPageFileLocked(name: String): Boolean {
        return runCatching {
            if (!isSafePageFileName(name)) return@runCatching false
            val file = File(pagesDir, name)
            if (file.canonicalFile.parentFile != pagesDir.canonicalFile) return@runCatching false
            !file.exists() || file.delete()
        }.getOrDefault(false)
    }

    private fun writePendingDeleteNamesLocked(json: JSONObject, names: List<String>) {
        json.put("pendingDeleteFiles", JSONArray().also { pending -> names.forEach(pending::put) })
        atomicText(sessionFile, json.toString())
    }

    fun loadSettings(): ScanSettings = synchronized(sharedLock) {
        runCatching {
            if (!settingsFile.exists() && !File(settingsFile.path + ".bak").exists()) return@synchronized ScanSettings()
            val json = JSONObject(AtomicFile(settingsFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            ScanSettings(provider = json.optString("provider", "api"),
                model = json.optString("model", "gpt-6.1-sol"),
                manualIp = json.optString("manualIp"), glossary = json.optString("glossary"),
                instructions = json.optString("instructions"),
                prompt = (json.opt("prompt") as? String)?.takeIf(String::isNotBlank) ?: DEFAULT_RECOGNITION_PROMPT)
        }.getOrDefault(ScanSettings())
    }

    fun saveSettings(settings: ScanSettings) {
        synchronized(sharedLock) {
            // Credentials are deliberately never part of this file.
            val json = JSONObject().put("provider", settings.provider).put("model", settings.model)
                .put("manualIp", settings.manualIp).put("glossary", settings.glossary)
                .put("instructions", settings.instructions)
                .put("prompt", settings.prompt.takeIf { it.isNotBlank() } ?: DEFAULT_RECOGNITION_PROMPT)
            atomicText(settingsFile, json.toString())
        }
    }

    fun deletePages(pages: List<CapturedPage>) {
        synchronized(sharedLock) {
            pages.forEach { page ->
                val file = File(page.path)
                if (file.parentFile?.canonicalFile == pagesDir.canonicalFile) file.delete()
            }
        }
    }

    fun clearSavedPages() {
        synchronized(sharedLock) { pagesDir.listFiles()?.filter(File::isFile)?.forEach(File::delete) }
    }

    /** Commit a fresh session before deleting any damaged or orphaned captures. */
    fun resetSavedSession(host: String?, sessionId: String): Boolean = synchronized(sharedLock) {
        val pagesPath = pagesDir.canonicalFile
        val files = pagesDir.listFiles().orEmpty().filter {
            it.isFile && it.canonicalFile.parentFile == pagesPath
        }
        val pending = files.map(File::getName).filter(::isSafePageFileName).take(MAX_PENDING_DELETE_FILES)
        writeSessionLocked(emptyList(), host, "", sessionId, false, pending)
        val cleanupPending = files.anyLeftAfterDeletion()
        val remaining = pending.filter { File(pagesDir, it).exists() }
        if (remaining != pending) runCatching {
            writeSessionLocked(emptyList(), host, "", sessionId, false, remaining)
        }
        cleanupPending
    }

    private fun List<File>.anyLeftAfterDeletion(): Boolean {
        var remaining = false
        for (file in this) if (!runCatching { !file.exists() || file.delete() }.getOrDefault(false)) remaining = true
        return remaining
    }

    private fun atomicText(target: File, content: String) {
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
            val persisted = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(persisted == content) { "Could not verify the saved session data." }
        } catch (error: Throwable) {
            stream?.let { runCatching { atomic.failWrite(it) } }
            throw error
        }
    }

}
