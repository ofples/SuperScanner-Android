package tools.supernote.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionTransportTest {
    @Test fun smallJsonReplyIsReadToEof() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"))
            server.start()
            val reply = OkHttpRecognitionTransport().request(server.url("/models").toString(), "GET")
            assertEquals(200, reply.status)
            assertTrue(JSONObject(reply.body).getBoolean("ok"))
        }
    }

    @Test fun oversizedJsonReplyIsRejectedAfterBoundedRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("x".repeat(4 * 1024 * 1024 + 1)))
            server.start()
            var rejected = false
            try { OkHttpRecognitionTransport().request(server.url("/models").toString(), "GET") }
            catch (error: Exception) { rejected = error.message?.contains("size limit") == true }
            assertTrue("An oversized provider reply must be rejected", rejected)
        }
    }

    @Test fun chunkedEventStreamPreservesEventsAcrossNetworkChunks() = runBlocking {
        MockWebServer().use { server ->
            val body = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"Mā"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"lā"}

                event: response.completed
                data: {"type":"response.completed","response":{"status":"completed"}}

            """.trimIndent()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(body, 7))
            server.start()
            val events = mutableListOf<String>()
            OkHttpRecognitionTransport().stream(server.url("/responses").toString(), emptyMap(), "{}") {
                events += it.getString("type") + ":" + it.optString("delta")
            }
            assertEquals(listOf("response.output_text.delta:Mā", "response.output_text.delta:lā", "response.completed:"), events)
        }
    }

    @Test fun failedConnectionDoesNotAutomaticallyReplayPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            runCatching { OkHttpRecognitionTransport().request(server.url("/oauth/token").toString(), "POST", body = "grant_type=refresh_token") }
            assertEquals("POST", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        }
    }
}
