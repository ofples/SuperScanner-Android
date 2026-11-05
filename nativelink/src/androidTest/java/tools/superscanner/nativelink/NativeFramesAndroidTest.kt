package tools.superscanner.nativelink

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFramesAndroidTest {
    @Test fun nativePairBodiesParseOnAndroidRegexEngine() {
        val approved = "HTTP/1.0 200 OK\n\n{code:200,msg:\"synthetic\"}\n"
        val refused = "HTTP/1.0 200 OK\r\n\r\n{code:201,msg:\"synthetic\"}\r\n"
        assertEquals(200, NativeFrames.pairResponse(approved.asInput()))
        assertEquals(201, NativeFrames.pairResponse(refused.asInput()))
    }

    private fun String.asInput() = ByteArrayInputStream(toByteArray(StandardCharsets.UTF_8))
}
