package kr.co.nexacore.jetkvm

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.webrtc.SurfaceViewRenderer
import kotlin.math.min

class NativeSessionView(
    context: Context, url: String, private val onList: () -> Unit = {}, onReconnect: () -> Unit = {}, private val onLanguage: () -> Unit = {},
    private val onDisconnect: () -> Unit = onList, private val monitor: MonitorSessionReporter? = null
) : LinearLayout(context) {
    private val status = TextView(context)
    private val renderer = SurfaceViewRenderer(context)
    private val viewport = VideoViewport(context,renderer)
    private var frameWidth = 0
    private var frameHeight = 0
    private var ready = false
    private var disposed = false
    private var phase = ConnectionPhase.DISCONNECTED
    private var lastX = 0
    private var lastY = 0
    private var pressed = false
    private var message = AppText.get(R.string.waiting_connection)
    private val client: NativeJetKvmClient
    val toolbar = ControlToolbar(context,onList,onReconnect,{ disconnect() },{},{ showMore(it) })
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        addView(toolbar,LayoutParams(-1,dp(56)))
        status.apply {
            textSize = 12f; setTextColor(Color.rgb(71,85,105)); setBackgroundColor(Color.rgb(244,247,250))
            setPadding(dp(12),dp(5),dp(12),dp(5)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showDetails() }
        }
        addView(status,LayoutParams(-1,-2))
        // An opaque View background would hide the separately composed video surface.
        addView(viewport,LayoutParams(-1,0,1f))
        client = NativeJetKvmClient(context,url,renderer,
            { if (!disposed) { message = it; renderState() } },
            {},
            { if (!disposed) { ready = it; renderState() } },
            { w,h -> frameWidth = w; frameHeight = h; viewport.setVideoSize(w,h) },
            { if (!disposed) {
                phase = it
                if (it == ConnectionPhase.CONNECTED) monitor?.start()
                if (it == ConnectionPhase.DISCONNECTED) monitor?.close()
                renderState()
            } },powerEnabled = false)
        renderer.setOnTouchListener { _, event ->
            if (!ready || frameWidth == 0 || frameHeight == 0) return@setOnTouchListener false
            val scale = min(renderer.width.toFloat()/frameWidth, renderer.height.toFloat()/frameHeight)
            if (scale <= 0) return@setOnTouchListener false
            val left = (renderer.width-frameWidth*scale)/2; val top = (renderer.height-frameHeight*scale)/2
            val x = (event.x-left)/(frameWidth*scale); val y = (event.y-top)/(frameHeight*scale)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (x !in 0f..1f || y !in 0f..1f) return@setOnTouchListener false
                    pressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = false
                MotionEvent.ACTION_MOVE -> Unit
                else -> return@setOnTouchListener true
            }
            lastX = (x.coerceIn(0f,1f)*32767).toInt(); lastY = (y.coerceIn(0f,1f)*32767).toInt()
            client.pointer(lastX,lastY,if (pressed) 1 else 0)
            true
        }
        renderState()
    }
    private fun renderState() {
        toolbar.update(true,phase,false)
        status.text = message
        status.setTextColor(Color.parseColor(when {
            phase == ConnectionPhase.DISCONNECTED -> "#7C4B45"
            ready -> "#386329"
            else -> "#725B18"
        }))
    }
    private fun showDetails() {
        AlertDialog.Builder(context).setTitle(AppText.get(R.string.connection_status)).setMessage(AppText.get(R.string.connection_details, message, AppText.get(R.string.agent_read_only), frameWidth, frameHeight))
            .setPositiveButton(AppText.get(R.string.ok),null).show()
    }
    private fun showMore(anchor: View) {
        val keys = listOf("Esc" to 41,"Tab" to 43,"Enter" to 40,"Backspace" to 42,"Ctrl+Alt+Del" to 76)
        PopupMenu(context,anchor).apply {
            menu.add(0,0,0,AppText.get(R.string.text_input_menu)).isEnabled = ready
            keys.forEachIndexed { i,(title,_) -> menu.add(0,i+1,0,title).isEnabled = ready }
            menu.add(0,6,0,AppText.get(R.string.right_click)).isEnabled = ready
            menu.add(0,7,0,AppText.get(R.string.connection_status_menu))
            menu.add(0,8,0,AppText.get(R.string.language_settings))
            setOnMenuItemClickListener { item ->
                when {
                    item.itemId == 8 -> onLanguage()
                    item.itemId == 7 -> showDetails()
                    !ready || disposed -> Unit
                    item.itemId == 0 -> showTextInput()
                    item.itemId == 6 -> { client.pointer(lastX,lastY,2); client.pointer(lastX,lastY,0) }
                    else -> { val index = item.itemId-1; client.key(keys[index].second,if (index == 4) 5 else 0) }
                }; true
            }
            show()
        }
    }
    private fun showTextInput() {
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val text = EditText(context).apply { hint = AppText.get(R.string.text_input_hint); isSingleLine = true; isSaveEnabled = false; box.addView(this) }
        val error = TextView(context).apply { setTextColor(Color.parseColor("#A0433B")); box.addView(this) }
        val dialog = AlertDialog.Builder(context).setTitle(AppText.get(R.string.text_input_title)).setView(ScrollView(context).apply { addView(box) })
            .setNegativeButton(AppText.get(R.string.cancel),null).setPositiveButton(AppText.get(R.string.send),null).create()
        dialog.setOnDismissListener { text.text.clear() }
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if (!ready || disposed) { error.text = AppText.get(R.string.connection_ended); return@setOnClickListener }
            if (client.text(text.text.toString())) dialog.dismiss() else error.text = AppText.get(R.string.ascii_only)
        } }
        dialog.show()
        dialog.window?.setLayout(dp((resources.configuration.screenWidthDp-32).coerceIn(240,480)),LayoutParams.WRAP_CONTENT)
        EditorActions.bind(listOf(text),android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { dialog.getButton(-1).performClick() }
        text.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
    fun start(password: String) {
        if (disposed) return
        phase = ConnectionPhase.CONNECTING; message = AppText.get(R.string.authenticating); renderState(); client.start(password)
    }
    private fun disconnect() {
        if (disposed) return
        message = AppText.get(R.string.disconnected_reconnect)
        close()
        onDisconnect()
    }
    fun close() {
        if (disposed) return
        disposed = true; pressed = false; ready = false; phase = ConnectionPhase.DISCONNECTED
        monitor?.close()
        renderState(); renderer.clearImage(); client.close()
    }
    fun pauseInput() {
        if (disposed) return
        message = AppText.get(R.string.background_disconnected)
        close()
    }
}
