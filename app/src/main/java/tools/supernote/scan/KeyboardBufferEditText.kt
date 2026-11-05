package tools.supernote.scan

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import android.annotation.TargetApi

/** Stable phone editor: local text edits stay local; Backspace on an empty buffer targets the tablet. */
internal class KeyboardBufferEditText(context: Context) : EditText(context) {
    var onDraftChanged: ((String, Boolean) -> Unit)? = null
    var onPhoneBackspace: ((String, Int, Int, Boolean, Boolean, Int) -> Boolean)? = null

    private var synchronizing = false
    private var wrappedMutationDepth = 0
    private var inputBatchDepth = 0
    private var pendingDraftDispatch = false
    private var remoteDeleteKeyDown = false

    init {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE
        hint = "Type English/ASCII here"
        minHeight = (56 * resources.displayMetrics.density).toInt()
        setPadding((14 * resources.displayMetrics.density).toInt(), paddingTop,
            (14 * resources.displayMetrics.density).toInt(), paddingBottom)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!synchronizing && wrappedMutationDepth == 0) dispatchDraftChanged()
            }
        })
    }

    /** Synchronize acknowledged text without resetting focus, selection, or composing spans. */
    fun syncBuffer(modelText: String, previousModelText: String) {
        val editable = text ?: return
        val current = editable.toString()
        if (current == modelText) return

        val removedPrefix = previousModelText.takeIf { it.isNotEmpty() && it.endsWith(modelText) && it != modelText }
            ?.dropLast(modelText.length)
        if (removedPrefix != null && current.startsWith(removedPrefix) && current.removePrefix(removedPrefix) == modelText) {
            val start = selectionStart.coerceAtLeast(0)
            val end = selectionEnd.coerceAtLeast(0)
            synchronizing = true
            editable.delete(0, removedPrefix.length)
            if (modelText.isNotEmpty()) setSelection(
                (start - removedPrefix.length).coerceIn(0, modelText.length),
                (end - removedPrefix.length).coerceIn(0, modelText.length),
            )
            synchronizing = false
            return
        }

        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        synchronizing = true
        setText(modelText)
        setSelection(start.coerceAtMost(modelText.length), end.coerceAtMost(modelText.length))
        synchronizing = false
    }

    /** Finalize the phone IME composition before an explicit tablet key action. */
    fun finishComposition() {
        text?.let(BaseInputConnection::removeComposingSpans)
        dispatchDraftChanged()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, false) {
            override fun beginBatchEdit(): Boolean {
                val result = super.beginBatchEdit()
                if (result) inputBatchDepth++
                return result
            }

            override fun endBatchEdit(): Boolean {
                val result = super.endBatchEdit()
                if (inputBatchDepth > 0) inputBatchDepth--
                if (inputBatchDepth == 0 && pendingDraftDispatch) dispatchDraftChanged()
                return result
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                afterInputMutation { super.commitText(text, newCursorPosition) }

            @TargetApi(Build.VERSION_CODES.TIRAMISU)
            override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
                afterInputMutation { super.commitText(text, newCursorPosition, textAttribute) }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                afterInputMutation { super.setComposingText(text, newCursorPosition) }

            @TargetApi(Build.VERSION_CODES.TIRAMISU)
            override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
                afterInputMutation { super.setComposingText(text, newCursorPosition, textAttribute) }

            override fun finishComposingText(): Boolean =
                afterInputMutation { super.finishComposingText() }

            override fun setComposingRegion(start: Int, end: Int): Boolean =
                afterInputMutation { super.setComposingRegion(start, end) }

            @TargetApi(Build.VERSION_CODES.TIRAMISU)
            override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
                afterInputMutation { super.setComposingRegion(start, end, textAttribute) }

            @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int,
                                     textAttribute: TextAttribute?): Boolean =
                afterInputMutation { super.replaceText(start, end, text, newCursorPosition, textAttribute) }

            override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean =
                afterInputMutation { super.commitCompletion(text) }

            override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean =
                afterInputMutation { super.commitCorrection(correctionInfo) }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    // Once this wrapper handles DOWN, consume its matching UP even if VM state changes.
                    if (event.action == KeyEvent.ACTION_UP && remoteDeleteKeyDown) {
                        remoteDeleteKeyDown = false
                        return true
                    }
                    if (event.action == KeyEvent.ACTION_DOWN && shouldRouteRemoteBackspace(count = 1)) {
                        remoteDeleteKeyDown = true
                        notifyRemoteBackspace(count = 1)
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && shouldRouteRemoteBackspace(beforeLength)) {
                    notifyRemoteBackspace(beforeLength)
                    return true
                }
                return afterInputMutation { super.deleteSurroundingText(beforeLength, afterLength) }
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && shouldRouteRemoteBackspace(beforeLength)) {
                    notifyRemoteBackspace(beforeLength)
                    return true
                }
                return afterInputMutation { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
            }

            private inline fun afterInputMutation(block: () -> Boolean): Boolean {
                wrappedMutationDepth++
                return try {
                    block()
                } finally {
                    wrappedMutationDepth--
                    if (inputBatchDepth > 0) pendingDraftDispatch = true
                    else dispatchDraftChanged()
                }
            }
        }
    }

    private fun shouldRouteRemoteBackspace(count: Int): Boolean = onPhoneBackspace?.invoke(
        text?.toString().orEmpty(), selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0),
        hasComposition(text), false, count,
    ) == true

    private fun notifyRemoteBackspace(count: Int) {
        onPhoneBackspace?.invoke(text?.toString().orEmpty(), selectionStart.coerceAtLeast(0),
            selectionEnd.coerceAtLeast(0), hasComposition(text), true, count)
    }

    private fun dispatchDraftChanged() {
        if (inputBatchDepth > 0 || wrappedMutationDepth > 0) {
            pendingDraftDispatch = true
            return
        }
        pendingDraftDispatch = false
        val editable = text
        onDraftChanged?.invoke(editable?.toString().orEmpty(), !hasComposition(editable))
    }

    private fun hasComposition(editable: Editable?): Boolean {
        if (editable == null) return false
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        return start >= 0 && end > start
    }
}
