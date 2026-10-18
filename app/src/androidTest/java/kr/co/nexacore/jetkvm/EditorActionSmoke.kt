package kr.co.nexacore.jetkvm

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

object EditorActionSmoke {
    fun run(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        lateinit var dialog: AlertDialog
        lateinit var fields: List<EditText>
        var submits = 0; var cancels = 0
        instrumentation.runOnMainSync {
            check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val home = content.getChildAt(0) as LinearLayout
            val toolbar = home.getChildAt(0) as ControlToolbar
            val position = IntArray(2); toolbar.getLocationOnScreen(position)
            check(position[1] >= (home.rootWindowInsets?.systemWindowInsetTop ?: 0)) { "Toolbar overlaps status bar" }
            check(home.childCount == 2 && !toolbar.list.isEnabled && !toolbar.reconnect.isEnabled && !toolbar.disconnect.isEnabled && !toolbar.power.isEnabled)
            // A live power-management connection must never enable remote-session actions on the list screen.
            ConnectionPhase.entries.forEach { phase ->
                toolbar.update(true,phase,false)
                check(!toolbar.list.isEnabled && !toolbar.reconnect.isEnabled && !toolbar.disconnect.isEnabled && toolbar.more.isEnabled) { "Device-list toolbar follows power connection state: $phase" }
            }
            toolbar.update(false,ConnectionPhase.DISCONNECTED,false)
            val scroll = home.getChildAt(1) as android.widget.ScrollView
            check(scroll.getChildAt(0).width == scroll.width-scroll.paddingLeft-scroll.paddingRight) { "Device list does not fill available width" }
            val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fields = List(3) { EditText(activity).apply { isSingleLine = true; box.addView(this) } }
            fields.last().inputType = android.text.InputType.TYPE_CLASS_NUMBER
            dialog = AlertDialog.Builder(activity).setView(box).setNegativeButton("취소") { _,_ -> cancels++ }.setPositiveButton("저장",null).create()
            dialog.show()
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener { submits++ } // Validation may keep a form open.
            EditorActions.bind(fields) { if (positive.isEnabled) positive.performClick() }
            fields.first().requestFocus()
        }
        instrumentation.waitForIdleSync() // Wait for dialog attachment and window focus before key dispatch.
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val (name,address,port) = fields
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                fun enter(code: Int = KeyEvent.KEYCODE_ENTER) {
                    dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
                    dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))
                }
                name.requestFocus(); enter()
                check(address.hasFocus() && submits == 0 && cancels == 0) { "Hardware Enter did not advance to address (focus=${dialog.currentFocus?.javaClass?.simpleName}, submits=$submits, cancels=$cancels)" }
                address.onEditorAction(EditorInfo.IME_ACTION_NEXT)
                check(port.hasFocus() && submits == 0) { "IME Next did not advance to port" }
                enter(KeyEvent.KEYCODE_NUMPAD_ENTER)
                check(submits == 1 && cancels == 0 && dialog.isShowing) { "Numpad Enter did not submit once" }
                port.onEditorAction(EditorInfo.IME_ACTION_DONE)
                check(submits == 2 && cancels == 0 && dialog.isShowing) { "IME Done canceled the form" }
                positive.isEnabled = false; enter()
                check(submits == 2 && cancels == 0) { "Busy form submitted again" }
                positive.isEnabled = true
                listOf(EditorInfo.IME_ACTION_GO,EditorInfo.IME_ACTION_SEND).forEach { action ->
                    EditorActions.bind(listOf(port),action) { positive.performClick() }
                    port.onEditorAction(action)
                }
                check(submits == 4 && cancels == 0)
                enter(); check(submits == 5 && cancels == 0) { "Single-field Enter canceled or submitted twice" }
            } catch (t: Throwable) { failure = t }
            finally { dialog.dismiss(); activity.finish() }
        }
        failure?.let { throw IllegalStateException("Editor dialog regression failed",it) }
    }
}
