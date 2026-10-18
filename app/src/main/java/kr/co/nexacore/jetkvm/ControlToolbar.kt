package kr.co.nexacore.jetkvm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

enum class ConnectionPhase { CONNECTING, CONNECTED, INTERRUPTED, DISCONNECTED }

/** Five 48dp touch targets fit even a 320dp phone without scrolling. */
class ControlToolbar(
    context: Context, onList: () -> Unit, onReconnect: () -> Unit, onDisconnect: () -> Unit,
    onPower: (View) -> Unit, onMore: (View) -> Unit,
    private val remoteSessionControls: Boolean = true
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    val list = icon(R.drawable.ic_devices,AppText.get(R.string.device_list)) { onList() }
    val reconnect = icon(R.drawable.ic_refresh,AppText.get(R.string.reconnect)) { onReconnect() }
    val disconnect = icon(R.drawable.ic_disconnect,AppText.get(R.string.disconnect)) { onDisconnect() }
    val power: ImageButton
    val more: ImageButton
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4),dp(4),dp(4),dp(4)); setBackgroundColor(Color.WHITE)
        val center = LinearLayout(context).apply { gravity = Gravity.CENTER }
        center.addView(ImageView(context).apply {
            setImageResource(R.drawable.nexacore_logo); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "NexaCore"
        },LayoutParams(dp(26),dp(26)))
        addView(center,LayoutParams(0,dp(48),1f))
        power = icon(R.drawable.ic_power,AppText.get(R.string.power_control),onPower)
        more = icon(R.drawable.ic_more,AppText.get(R.string.more_features),onMore)
        update(false,ConnectionPhase.DISCONNECTED,false)
    }
    private fun icon(resource: Int, label: String, action: (View) -> Unit): ImageButton = ImageButton(context).also {
        it.setImageResource(resource); it.contentDescription = label; it.tooltipText = label
        it.setPadding(dp(12),dp(12),dp(12),dp(12))
        it.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#225EC4AF")),null,null)
        it.imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
            intArrayOf(Color.parseColor("#B8C1CC"),Color.parseColor("#435A9A")))
        it.setOnClickListener(action); addView(it,LayoutParams(dp(48),dp(48)))
    }
    fun update(hasDevice: Boolean, phase: ConnectionPhase, powerReady: Boolean) {
        val active = remoteSessionControls && hasDevice && phase != ConnectionPhase.DISCONNECTED
        list.isEnabled = active
        reconnect.isEnabled = active && phase != ConnectionPhase.CONNECTING
        disconnect.isEnabled = active
        power.isEnabled = hasDevice && phase == ConnectionPhase.CONNECTED && powerReady
    }
}
