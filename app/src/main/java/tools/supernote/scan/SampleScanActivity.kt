package tools.supernote.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** A deliberately offline workspace; no live sources, credentials or saved scan are opened. */
class SampleScanActivity : ComponentActivity() {
    private val scanner by viewModels<AppViewModel> {
        viewModelFactory {
            initializer {
                AppViewModel(
                    application, SampleMirrorSource(), SampleRecognitionSource(),
                    SessionStore(application, SessionStore.SAMPLE_DIRECTORY), isSample = true,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScannerApp(scanner, exitSample = ::finish, nextSamplePage = scanner::advanceSamplePage)
        }
    }

    override fun onStart() {
        super.onStart()
        scanner.resumeMirror()
    }

    override fun onStop() {
        scanner.pauseMirror()
        super.onStop()
    }
}

internal class SampleMirrorSource : MirrorSource {
    private val index = MutableStateFlow(0)
    fun nextPage() = index.update { (it + 1) % SampleNotes.pages.size }
    fun isCurrent(frame: MirrorFrame) = frame.samplePageNumber == index.value + 1

    override suspend fun discover(manualHost: String?, cachedHost: String?) = listOf("sample")

    override fun frames(host: String) = channelFlow {
        index.collectLatest { selected ->
            val image = withContext(Dispatchers.Default) { SampleNotes.pages[selected].image }
            while (isActive) {
                send(MirrorFrame(image, "image/png", SampleNotes.WIDTH, SampleNotes.HEIGHT,
                    System.currentTimeMillis(), samplePageNumber = selected + 1))
                // Keep the current page fresh without allocating or decoding another image.
                delay(2_000)
            }
        }
    }
}

private class SampleRecognitionSource : RecognitionSource {
    override fun hasApiKey() = false
    override fun setApiKey(value: String) = Unit
    override fun subscriptionStatus() = ""
    override fun isChatGptConnected() = false
    override suspend fun beginChatGptSignIn(openBrowser: (Uri) -> Unit) = Unit
    override fun cancelSignIn() = Unit
    override suspend fun disconnectChatGpt() = Unit
    override suspend fun listModels(provider: String) = emptyList<ModelChoice>()

    override suspend fun recognize(
        pages: List<CapturedPage>,
        settings: ScanSettings,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        pages.joinToString("\n\n") { capture ->
            val file = File(capture.path)
            require(file.isFile && file.length() in 1..SessionStore.MAX_PAGE_BYTES.toLong()) {
                "This sample page is no longer available. Capture it again to continue."
            }
            val bytes = file.readBytes()
            SampleNotes.pages.firstOrNull { it.image.contentEquals(bytes) }?.transcript
                ?: error("This page does not match a bundled sample.")
        }
    }
}

/** Original, fictitious notes for the demo and for release photography. */
internal object SampleNotes {
    const val WIDTH = 1000
    const val HEIGHT = 1320

    val pages = listOf(
        Note("A slower morning", "Field notes / 01",
            listOf("Open the window before the phone.",
                "Make tea and notice the first light.", "Write down one thing worth keeping.",
                "", "The sky was gray, but the room", "turned warm when the sun came through.",
                "A small reminder to look up more often."), false),
        Note("The weekend list", "Field notes / 02",
            listOf("• Bring the sketchbook.",
                "• Take the long way to the lake.", "• Find a quiet spot and draw for a while.",
                "", "If it rains, stay in and finish the book.", "No need to fill the whole day."), true),
    )

    class Note(
        private val heading: String,
        private val label: String,
        private val lines: List<String>,
        private val lake: Boolean,
    ) {
        val image: ByteArray by lazy { draw(heading, label, lines, lake) }
        val transcript: String
            get() = if (lake) "$heading\n\n" + lines.take(3).joinToString("\n") + "\n\n" + lines.drop(4).joinToString(" ")
            else "$heading\n\n" + lines.take(3).joinToString(" ") + "\n\n" + lines.drop(4).joinToString(" ")
    }

    private fun draw(heading: String, label: String, lines: List<String>, lake: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(250, 249, 243))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
            paint.color = Color.rgb(219, 220, 211)
            for (row in 290..1202 step 76) canvas.drawLine(72f, row.toFloat(), 928f, row.toFloat(), paint)
            paint.color = Color.rgb(174, 181, 172)
            canvas.drawLine(72f, 102f, 928f, 102f, paint)
            paint.color = Color.rgb(69, 86, 76)
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 26f
            paint.letterSpacedLabel(canvas, label.uppercase(java.util.Locale.ROOT), 92f, 80f)
            paint.typeface = Typeface.create("casual", Typeface.NORMAL)
            paint.color = Color.rgb(42, 53, 47)
            paint.textSize = 68f
            canvas.drawText(heading, 91f, 211f, paint)
            paint.color = Color.rgb(92, 119, 105)
            paint.strokeWidth = 4f
            canvas.drawLine(94f, 231f, 474f, 227f, paint)
            paint.color = Color.rgb(42, 53, 47)
            paint.textSize = 39f
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, 97f + (i % 3) * 2f, 279f + i * 76f, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(85, 105, 94)
            paint.strokeWidth = 4.5f
            paint.strokeCap = Paint.Cap.ROUND
            val drawing = Path().apply {
                if (lake) {
                    moveTo(132f, 986f); quadTo(262f, 964f, 367f, 986f)
                    quadTo(468f, 1007f, 565f, 981f); quadTo(676f, 952f, 851f, 980f)
                    moveTo(141f, 972f); lineTo(304f, 839f); lineTo(411f, 931f)
                    lineTo(534f, 818f); lineTo(695f, 952f)
                    moveTo(275f, 1047f); quadTo(365f, 1031f, 445f, 1048f)
                    moveTo(567f, 1098f); quadTo(660f, 1084f, 747f, 1097f)
                } else {
                    moveTo(356f, 893f); quadTo(352f, 1033f, 428f, 1039f)
                    lineTo(554f, 1039f); quadTo(633f, 1033f, 633f, 893f); close()
                    moveTo(633f, 921f); cubicTo(747f, 900f, 730f, 999f, 614f, 1000f)
                    moveTo(308f, 1072f); quadTo(492f, 1102f, 683f, 1069f)
                    moveTo(448f, 862f); cubicTo(391f, 801f, 497f, 797f, 452f, 748f)
                    moveTo(531f, 858f); cubicTo(495f, 808f, 573f, 792f, 534f, 748f)
                }
            }
            canvas.drawPath(drawing, paint)
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun Paint.letterSpacedLabel(canvas: Canvas, label: String, start: Float, y: Float) {
        var x = start
        for (letter in label) {
            val text = letter.toString()
            canvas.drawText(text, x, y, this)
            x += measureText(text) + 2.3f
        }
    }
}
