package kr.co.nexacore.jetkvm

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/** Keep Enter inside the editor; never let the dialog's default focus activate Cancel. */
object EditorActions {
    fun bind(fields: List<EditText>, action: Int = EditorInfo.IME_ACTION_DONE, submit: () -> Unit) {
        fields.forEachIndexed { index, field ->
            val next = fields.getOrNull(index + 1)
            field.imeOptions = (if (next != null) EditorInfo.IME_ACTION_NEXT else action) or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            fun advance() { if (next != null) next.requestFocus() else submit() }
            fun enter(code: Int) = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            field.setOnKeyListener { _, code, event ->
                if (!enter(code)) false else {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) advance()
                    true // Consume down, repeats and up so a button never receives them.
                }
            }
            field.setOnEditorActionListener { _, id, event ->
                if (event != null && enter(event.keyCode)) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) advance()
                    true
                } else if (event == null && id in setOf(EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_UNSPECIFIED)) {
                    advance(); true
                } else false
            }
        }
    }
}
