package tools.supernote.scan

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SessionStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directoryName: String
    private lateinit var root: File
    private lateinit var store: SessionStore

    @Before fun setUp() {
        directoryName = "scan-store-test-${UUID.randomUUID()}"
        root = File(context.filesDir, directoryName)
        store = SessionStore(context, directoryName)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun missingFilesAreAValidFreshInstall() {
        val loaded = store.loadSession()
        assertTrue(loaded.pages.isEmpty())
        assertEquals(null, loaded.storageError)
        assertEquals(ScanSettings(), store.loadSettings())
    }

    @Test fun atomicFileRecoversThePreviousSessionAfterAnInterruptedWrite() {
        store.saveSession(emptyList(), "192.168.1.12", "kept text", "session-a", false)
        val target = File(root, "session.json")
        val stream = AtomicFile(target).startWrite()
        stream.write("{truncated".toByteArray())
        stream.close() // Simulate a process death before finishWrite/failWrite.

        val recovered = SessionStore(context, directoryName).loadSession()
        assertEquals("kept text", recovered.resultText)
        assertEquals("session-a", recovered.sessionId)
        assertEquals("192.168.1.12", recovered.host)
    }

    @Test fun malformedStateSurfacesAnErrorAndPreservesOriginalPageBytes() {
        val original = byteArrayOf(1, 2, 3, 4, 5, 6)
        val page = store.savePage(MirrorFrame(original, "image/png", 10, 20, 123L), emptyList())
        File(root, "session.json").writeText("not valid json")

        val loaded = store.loadSession()
        assertNotNull(loaded.storageError)
        assertTrue(File(page.path).exists())
        assertArrayEquals(original, File(page.path).readBytes())
    }

    @Test fun savingMissingRequestedPageFailsWithoutReplacingThePreviousSession() {
        val original = byteArrayOf(9, 8, 7, 6, 5, 4)
        val page = store.savePage(MirrorFrame(original, "image/png", 10, 20, 123L), emptyList())
        store.saveSession(listOf(page), null, "prior durable result", "before-missing-page", false)
        val sessionFile = File(root, "session.json")
        val sessionBefore = sessionFile.readBytes()
        val pageFile = File(page.path)

        assertTrue(pageFile.delete())
        try {
            val incomplete = SessionStore(context, directoryName).loadSession()
            assertNotNull("A missing referenced page must be surfaced as a storage error.", incomplete.storageError)
            assertEquals("prior durable result", incomplete.resultText)
            assertArrayEquals("A failed load must not rewrite the committed session.", sessionBefore,
                sessionFile.readBytes())

            val rejected = runCatching {
                store.saveSession(listOf(page), null, "must not commit", "missing-page-write", false)
            }
            assertTrue("A requested page missing from disk must reject the session write.", rejected.isFailure)
            assertArrayEquals("A rejected write must preserve the previous session bytes.", sessionBefore,
                sessionFile.readBytes())
        } finally {
            pageFile.writeBytes(original)
        }

        val restored = SessionStore(context, directoryName).loadSession()
        assertEquals(listOf(page.id), restored.pages.map(CapturedPage::id))
        assertEquals("prior durable result", restored.resultText)
        assertArrayEquals(original, pageFile.readBytes())
    }

    @Test fun retiredOriginalCleanupFailureKeepsCommittedTextAndRetriesOnlyRecordedBasename() {
        val page = store.savePage(MirrorFrame(byteArrayOf(1, 2, 3, 4), "image/png", 2, 3, 123L), emptyList())
        store.saveSession(listOf(page), null, "previous text", "before-retire", false)
        val image = File(page.path)
        assertTrue(image.delete())
        assertTrue(image.mkdirs())
        val sentinel = File(image, "fixture-only")
        sentinel.writeText("synthetic cleanup blocker")

        val cleanupPending = store.saveSessionAndRetirePages(emptyList(), null, "final synthetic result",
            "after-retire", recognitionRunning = false, retiredPages = listOf(page))
        assertTrue(cleanupPending)
        store.saveSession(emptyList(), null, "final synthetic result", "after-later-save", false)

        val committed = SessionStore(context, directoryName).loadSession()
        assertEquals(null, committed.storageError)
        assertTrue(committed.pages.isEmpty())
        assertEquals("final synthetic result", committed.resultText)
        assertTrue("Only this recorded synthetic basename remains for cleanup.", sentinel.isFile)

        assertTrue(sentinel.delete())
        assertTrue(image.delete())
        val recovered = SessionStore(context, directoryName).loadSession()
        assertEquals(null, recovered.storageError)
        assertTrue(recovered.pages.isEmpty())
        assertEquals("final synthetic result", recovered.resultText)
        assertTrue("The recorded original file should be removed on retry.", !image.exists())
    }

    @Test fun carriedCleanupEntryIsDroppedForAnActivePage() {
        val page = store.savePage(MirrorFrame(byteArrayOf(1, 2, 3, 4), "image/png", 2, 3, 123L), emptyList())
        store.saveSession(listOf(page), null, "retained result", "active-page", false)
        val sessionFile = File(root, "session.json")
        val json = JSONObject(sessionFile.readText())
        json.put("pendingDeleteFiles", JSONArray().put(File(page.path).name))
        sessionFile.writeText(json.toString())

        store.saveSession(listOf(page), null, "retained result", "active-page-again", false)
        val loaded = SessionStore(context, directoryName).loadSession()

        assertEquals(null, loaded.storageError)
        assertEquals(listOf(page.id), loaded.pages.map(CapturedPage::id))
        assertTrue(File(page.path).isFile)
        assertEquals(0, JSONObject(sessionFile.readText()).optJSONArray("pendingDeleteFiles")?.length() ?: 0)
    }

    @Test fun settingsDoNotContainOrChangeWhenCallerDoesNotSaveThem() {
        val saved = ScanSettings(model = "gpt-6-luna", glossary = "word",
            instructions = "existing additional guidance", prompt = "Custom base prompt.")
        store.saveSettings(saved)
        val bytesBefore = File(root, "settings.json").readBytes()

        assertEquals(saved, SessionStore(context, directoryName).loadSettings())
        assertArrayEquals(bytesBefore, File(root, "settings.json").readBytes())
        assertFalseContains(File(root, "settings.json").readText(), "sk-")
    }

    @Test fun legacyOrBlankPromptLoadsDefaultAndPreservesExistingAdditionalGuidance() {
        val target = File(root, "settings.json")
        val legacy = JSONObject().put("provider", "chatgpt").put("model", "gpt-6-luna")
            .put("instructions", "keep this existing guidance")
        val invalidPromptValues = listOf(null, "", JSONObject.NULL, 12)
        invalidPromptValues.forEach { promptValue ->
            val json = JSONObject(legacy.toString())
            if (promptValue != null) json.put("prompt", promptValue)
            target.writeText(json.toString())

            val loaded = store.loadSettings()
            assertEquals(DEFAULT_RECOGNITION_PROMPT, loaded.prompt)
            assertEquals("keep this existing guidance", loaded.instructions)
            assertEquals("gpt-6-luna", loaded.model)
        }
    }

    private fun assertFalseContains(value: String, text: String) {
        assertTrue("Expected settings not to contain credential marker", !value.contains(text))
    }
}
