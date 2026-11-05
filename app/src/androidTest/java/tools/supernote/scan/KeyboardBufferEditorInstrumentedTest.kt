package tools.supernote.scan

import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardBufferEditorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var field: KeyboardBufferEditText

    @Before
    fun attachEditorWithoutOpeningSystemIme() {
        onMain {
            field = KeyboardBufferEditText(compose.activity)
            field.showSoftInputOnFocus = false
            compose.activity.setContentView(field)
            field.requestFocus()
        }
    }

    @Test
    fun localSurroundingDeleteEditsDraftAndEmptyBufferDeletesRouteOnce() {
        val remote = AtomicInteger()
        val callbacks = arrayListOf<Pair<String, Boolean>>()
        val connection = inputConnection()
        onMain {
            field.onDraftChanged = { text, committed -> callbacks += text to committed }
            field.onPhoneBackspace = { text, start, end, composing, perform, count ->
                val route = text.isEmpty() && start == end && !composing
                if (route && perform) remote.addAndGet(count)
                route
            }
            field.setText("draft")
            field.setSelection(field.text.length)
            callbacks.clear()
            assertTrue(connection.deleteSurroundingText(1, 0))
            assertEquals("draf", field.text.toString())
            assertEquals(0, remote.get())

            field.setText("")
            callbacks.clear()
            assertTrue(connection.deleteSurroundingText(1, 0))
            assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
            assertEquals(2, remote.get())
            assertEquals("", field.text.toString())
        }
    }

    @Test
    fun handledDeleteKeyUpIsConsumedEvenAfterRoutingStateChanges() {
        val remote = AtomicInteger()
        val route = AtomicReference(true)
        val connection = inputConnection()
        onMain {
            field.setText("")
            field.onPhoneBackspace = { _, _, _, _, perform, count ->
                if (perform) remote.addAndGet(count)
                route.get()
            }
            assertTrue(connection.sendKeyEvent(KeyEvent(1, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0)))
            route.set(false)
            assertTrue(connection.sendKeyEvent(KeyEvent(1, 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0)))
            assertEquals(1, remote.get())
        }
    }

    @Test
    fun compositionCallbacksFollowOldAndModernInputConnectionMutations() {
        assumeTrue("TextAttribute overloads require API 33", Build.VERSION.SDK_INT >= 33)
        val callbacks = arrayListOf<Pair<String, Boolean>>()
        val connection = inputConnection()
        val attribute = TextAttribute.Builder().build()
        onMain {
            field.onDraftChanged = { text, committed -> callbacks += text to committed }
            assertTrue(connection.setComposingText("old", 1))
            assertEquals(listOf("old" to false), callbacks.toList())
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("old" to false, "old" to true), callbacks.toList())

            field.setText("")
            callbacks.clear()
            assertTrue(connection.setComposingText("modern", 1, attribute))
            assertEquals(listOf("modern" to false), callbacks.toList())
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("modern" to false, "modern" to true), callbacks.toList())

            callbacks.clear()
            assertTrue(connection.commitText("done", 1, attribute))
            assertEquals(listOf("moderndone" to true), callbacks.toList())
        }
    }

    @Test
    fun modernComposingRegionAndReplaceTextUsePostMutationDispatch() {
        assumeTrue("TextAttribute overloads require API 34", Build.VERSION.SDK_INT >= 34)
        val callbacks = arrayListOf<Pair<String, Boolean>>()
        val connection = inputConnection()
        val attribute = TextAttribute.Builder().build()
        onMain {
            field.onDraftChanged = { text, committed -> callbacks += text to committed }
            field.setText("word")
            callbacks.clear()
            assertTrue(connection.setComposingRegion(0, 4, attribute))
            assertEquals(listOf("word" to false), callbacks.toList())
            assertTrue(connection.replaceText(0, 4, "next", 1, attribute))
            assertEquals("next", field.text.toString())
            assertEquals("next" to true, callbacks.last())
        }
    }

    @Test
    fun batchEditDefersCompositionDispatchUntilOuterEnd() {
        assumeTrue("TextAttribute overloads require API 33", Build.VERSION.SDK_INT >= 33)
        val callbacks = arrayListOf<Pair<String, Boolean>>()
        val connection = inputConnection()
        val attribute = TextAttribute.Builder().build()
        onMain {
            field.onDraftChanged = { text, committed -> callbacks += text to committed }
            assertTrue(connection.beginBatchEdit())
            assertTrue(connection.setComposingText("batch", 1, attribute))
            assertTrue(connection.finishComposingText())
            assertTrue(callbacks.isEmpty())
            connection.endBatchEdit()
            assertEquals(listOf("batch" to true), callbacks.toList())
        }
    }

    private fun inputConnection(): InputConnection {
        val result = AtomicReference<InputConnection?>()
        onMain { result.set(field.onCreateInputConnection(EditorInfo())) }
        return result.get().also { assertNotNull("Editor should provide an InputConnection", it) }!!
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
