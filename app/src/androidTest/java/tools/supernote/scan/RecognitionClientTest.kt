package tools.supernote.scan

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecognitionClientTest {
    @Test fun orderedImagesUsePrivatePathsAndOneCompletedResponsesRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureStore(context)
        val oldKey = runCatching { secure.read("api_key") }.getOrNull()
        val pagesDir = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val first = File(pagesDir, "auth-test-first.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x11)) }
        val second = File(pagesDir, "auth-test-second.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x22)) }
        try {
            val transport = CapturingTransport()
            val client = RecognitionClient(context, transport)
            client.setApiKey("sk-test-never-sent-to-network")
            val pages = listOf(
                CapturedPage("one", first.absolutePath, 1404, 1872, 1),
                CapturedPage("two", second.absolutePath, 1404, 1872, 2),
            )
            val customPrompt = "CUSTOM BASE PROMPT: reflow ordinary prose into paragraphs."
            val settings = ScanSettings(provider = "api", model = "gpt-6.1-sol",
                glossary = "ācārya → ācārya", instructions = "Keep these extra notes.", prompt = customPrompt)
            val drafts = mutableListOf<String>()
            val result = client.recognize(pages, settings) { drafts += it }

            assertEquals("first page\nsecond page", result)
            assertEquals(listOf("first page", "first page\nsecond page"), drafts.distinct())
            assertEquals("https://api.openai.com/v1/responses", transport.url)
            assertEquals("Bearer sk-test-never-sent-to-network", transport.headers["Authorization"])
            assertEquals(false, transport.requestJson?.getBoolean("store"))
            assertEquals(true, transport.requestJson?.getBoolean("stream"))
            assertEquals("gpt-6.1-sol", transport.requestJson?.getString("model"))
            assertTrue(transport.requestJson?.has("max_output_tokens") == true)
            val content = transport.requestJson?.getJSONArray("input")?.getJSONObject(0)?.getJSONArray("content")
            assertEquals("Page 1 of 2:", content?.getJSONObject(1)?.getString("text"))
            assertEquals("Page 2 of 2:", content?.getJSONObject(3)?.getString("text"))
            val apiPrompt = content?.getJSONObject(0)?.getString("text").orEmpty()
            assertTrue(apiPrompt.startsWith(customPrompt))
            assertTrue(apiPrompt.contains("Glossary (possible spellings only"))
            assertTrue(apiPrompt.contains("Additional recognition guidance:\nKeep these extra notes."))
            assertFalse(apiPrompt.contains("Preserve the author's wording, spelling, punctuation, line breaks"))
            assertFalse(apiPrompt.contains("--- Page 1 ---"))
            assertTrue(content?.getJSONObject(2)?.getString("image_url")?.endsWith("/9j/EQ==") == true)
            assertTrue(content?.getJSONObject(4)?.getString("image_url")?.endsWith("/9j/Ig==") == true)
            assertFalse(transport.requestJson?.has("background") == true)

            val chatPayload = client.buildResponsesPayload(pages, settings.copy(provider = "chatgpt"), apiProvider = false)
            val chatPrompt = chatPayload.getJSONArray("input").getJSONObject(0).getJSONArray("content")
                .getJSONObject(0).getString("text")
            assertEquals(apiPrompt, chatPrompt)
            assertFalse(chatPayload.has("max_output_tokens"))

            val oversized = runCatching {
                client.buildResponsesPayload(pages, settings.copy(prompt = "x".repeat(MAX_RECOGNITION_PROMPT_CHARS + 1)), true)
            }.exceptionOrNull()
            assertTrue(oversized?.message?.contains("Nothing was truncated") == true)
        } finally {
            secure.write("api_key", oldKey)
            first.delete(); second.delete()
        }
    }

    @Test fun streamWithoutCompletedTerminalNeverReturnsPartialText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureStore(context)
        val oldKey = runCatching { secure.read("api_key") }.getOrNull()
        val pagesDir = File(context.filesDir, "scan-session/pages").apply { mkdirs() }
        val pageFile = File(pagesDir, "auth-test-page.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) }
        try {
            val transport = CapturingTransport(emitCompletion = false)
            val client = RecognitionClient(context, transport)
            client.setApiKey("sk-test-never-sent-to-network")
            val failure = runCatching {
                client.recognize(listOf(CapturedPage("one", pageFile.absolutePath, 100, 100, 1)),
                    ScanSettings(provider = "api", model = "gpt-6.1-sol")) { }
            }.exceptionOrNull()
            assertTrue("A missing terminal event must fail", failure?.message?.contains("did not confirm completion") == true)
            assertFalse(transport.completed)
        } finally {
            secure.write("api_key", oldKey)
            pageFile.delete()
        }
    }

    private class CapturingTransport(private val emitCompletion: Boolean = true) : RecognitionTransport {
        var url = ""
        var headers = emptyMap<String, String>()
        var requestJson: JSONObject? = null
        var completed = false

        override suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): WireReply =
            WireReply(200, "{}")

        override suspend fun stream(url: String, headers: Map<String, String>, body: String, onEvent: (JSONObject) -> Unit) {
            this.url = url
            this.headers = headers
            requestJson = JSONObject(body)
            onEvent(JSONObject().put("type", "response.output_text.delta").put("delta", "first page"))
            if (emitCompletion) {
                onEvent(JSONObject().put("type", "response.output_text.delta").put("delta", "\nsecond page"))
                val output = JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray()
                    .put(JSONObject().put("type", "output_text").put("text", "first page\nsecond page"))))
                onEvent(JSONObject().put("type", "response.completed").put("response", JSONObject()
                    .put("status", "completed").put("output", output)))
                completed = true
            }
        }
    }
}
