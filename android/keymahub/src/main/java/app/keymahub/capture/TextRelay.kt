package app.keymahub.capture

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import app.keymahub.core.Hub

/**
 * Types into a phone app's text field with the on-screen keyboard while a target has the mouse.
 *
 * Pointer capture needs the capture window to keep focus, and a text field needs its own window
 * to have focus before the on-screen keyboard types into it: both cannot have it. So when a text
 * field is touched on the phone, the keyboard is shown for a hidden field in the capture window
 * instead, starting from the touched field's text and cursor, and every change is copied to the
 * touched field through accessibility (set text, set selection, enter). The app's own changes
 * (a sent message cleared) come back the same way. Not every app tells of its changes, so before
 * each copy the touched field's text is read again: when it is not what was last copied, the
 * keyboard's change goes onto that text instead ([rebaseTyping]), never the hidden field's old text.
 * Touching the field again reads its text and cursor again too. The mouse stays captured throughout.
 *
 * Fields that ignore accessibility's set text (some web pages, games, terminals) do not take the
 * text. A password field starts empty: its text cannot be read, so typing replaces it.
 *
 * Main thread only.
 */
class TextRelay(context: Context, parent: ViewGroup) {
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val field = Field(context)
    /** The touched field typed into, while typing. */
    private var node: AccessibilityNodeInfo? = null
    /** Texts sent to [node] lately: its change events for them arrive later, and are not the app's own changes. */
    private val sent = ArrayDeque<String>()
    /** What [node]'s text should be now: what was read from it or last copied to it. */
    private var expected = ""
    /** Setting [field] from [node]: not a change to copy. */
    private var muted = false
    private var refusedLogged = false

    init {
        parent.addView(field, ViewGroup.LayoutParams(1, 1))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!muted) push(s.toString())
            }
        })
        field.setOnEditorActionListener { _, _, _ ->
            // Enter on a single-line field: the app's own action (send, search, next).
            val n = node
            if (n != null && Build.VERSION.SDK_INT >= 30) {
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                syncLater() // sent: the app may clear it
            }
            true
        }
    }

    /** An accessibility event (type) from a phone app, with its source node. */
    fun onEvent(type: Int, source: AccessibilityNodeInfo) {
        val current = node
        when (type) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> when {
                // Touched again, it is read again: the app may have changed it without saying so.
                source.isEditable -> begin(source)
                // Touched something in another window (another app, a dialog): the keyboard goes away.
                // In the same window it stays, as it does for a send button.
                type == AccessibilityEvent.TYPE_VIEW_CLICKED && current != null && source.windowId != current.windowId -> end()
                // A send button: the app may clear the field, without saying so.
                type == AccessibilityEvent.TYPE_VIEW_CLICKED && current != null -> syncLater()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (source == current) fromApp(source)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (current != null && !current.refresh()) end() // its screen is gone
        }
    }

    private fun begin(target: AccessibilityNodeInfo) {
        val again = target == node
        node = target
        sent.clear()
        if (!again) refusedLogged = false
        val text = textOf(target)
        expected = text
        var type = target.inputType.takeIf { it != InputType.TYPE_NULL } ?: InputType.TYPE_CLASS_TEXT
        if (target.isMultiLine && type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        /** A selection end of the touched field within [text]; none (negative): the end of the text. */
        fun at(i: Int) = if (i < 0) text.length else minOf(i, text.length)
        quietly {
            field.inputType = type
            field.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                (if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE)
            field.setText(text)
            field.setSelection(at(target.textSelectionStart), at(target.textSelectionEnd))
        }
        if (!again) Hub.log("typing into ${target.packageName}/${target.className?.toString()?.substringAfterLast('.')} with the on-screen keyboard")
        show()
    }

    /** Changes [field] without copying the change to the touched field. */
    private inline fun quietly(change: () -> Unit) {
        muted = true
        try {
            change()
        } finally {
            muted = false
        }
    }

    private fun show() {
        field.requestFocus()
        imm?.restartInput(field)
        imm?.showSoftInput(field, 0)
    }

    /** Stops typing into the touched field: the keyboard goes away, focus back to the capture window. */
    fun end() {
        if (node == null) return
        node = null
        field.removeCallbacks(sync)
        sent.clear()
        imm?.hideSoftInputFromWindow(field.windowToken, 0)
        (field.parent as? ViewGroup)?.requestFocus()
    }

    /** [field] changed (the keyboard typed): copy it to the touched field. */
    private fun push(typed: String) {
        val n = node ?: return
        var text = typed
        // A password's text cannot be read: nothing to compare with.
        if (!n.isPassword && n.refresh()) {
            val actual = textOf(n)
            if (actual != expected) {
                text = rebaseTyping(expected, typed, actual)
                Hub.log("text field changed by its app unseen, typing onto its text")
                quietly {
                    field.setText(text)
                    field.setSelection(text.length)
                }
                imm?.restartInput(field) // the keyboard knew the old text
            }
        }
        if (sent.lastOrNull() == text) return
        sent.addLast(text)
        while (sent.size > 16) sent.removeFirst()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            if (!refusedLogged) Hub.log("text field refused the text (${n.packageName})")
            refusedLogged = true
            return
        }
        expected = text
        pushSelection()
    }

    private fun pushSelection() {
        val n = node ?: return
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, field.selectionStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, field.selectionEnd)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    /**
     * Reads the touched field again a little later (after a send button or enter, when apps clear
     * it, some without telling): an unseen change goes into [field] before the next key, so the
     * keyboard starts on it (a Korean syllable then composes as usual).
     */
    private fun syncLater() {
        field.removeCallbacks(sync)
        for (ms in SYNC_MS) field.postDelayed(sync, ms)
    }

    private val sync = Runnable {
        val n = node ?: return@Runnable
        if (n.isPassword || !n.refresh()) return@Runnable
        val actual = textOf(n)
        if (actual == expected) return@Runnable
        expected = actual
        sent.clear()
        quietly {
            field.setText(actual)
            field.setSelection(actual.length)
        }
        imm?.restartInput(field)
    }

    /** The touched field changed: the app's own change (not one sent from here) goes into [field]. */
    private fun fromApp(source: AccessibilityNodeInfo) {
        val text = textOf(source)
        if (text == field.text.toString() || text in sent) return
        expected = text
        quietly {
            field.setText(text)
            field.setSelection(text.length)
        }
        sent.clear()
        imm?.restartInput(field) // drop the keyboard's composing state for the old text
    }

    companion object {
        /** When the touched field is read again after a send (see [syncLater]): apps clear it at once or after a moment. */
        private val SYNC_MS = listOf(150L, 600L)
    }

    private fun textOf(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText || n.isPassword) "" else n.text?.toString().orEmpty()

    /** The hidden field; the cursor moved by the keyboard (arrows, a tap in its suggestions) is copied too. */
    private inner class Field(context: Context) : EditText(context) {
        init {
            isFocusableInTouchMode = true
            alpha = 0f
            isSaveEnabled = false
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            // Called from EditText's constructor, before this class's fields are set.
            @Suppress("SENSELESS_COMPARISON")
            if (this@TextRelay.field != null && !muted) pushSelection()
        }
    }
}

/**
 * The keyboard changed [before] into [after], but the touched field holds [actual] (its app
 * changed it, e.g. cleared it after sending): the same change at the end of [actual]. What
 * was taken away from the end goes only if [actual] ends with it too.
 */
internal fun rebaseTyping(before: String, after: String, actual: String): String {
    var p = 0
    while (p < before.length && p < after.length && before[p] == after[p]) p++
    var q = 0
    while (q < before.length - p && q < after.length - p && before[before.length - 1 - q] == after[after.length - 1 - q]) q++
    val removed = before.substring(p, before.length - q)
    val added = after.substring(p, after.length - q)
    return actual.removeSuffix(removed) + added
}
