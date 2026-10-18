package kr.co.nexacore.jetkvm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.widget.ImageButton
import android.widget.PopupMenu

class DeviceOptionsButton(
    context: Context, deviceName: String, onCheck: () -> Unit,
    onDisconnect: () -> Unit, canDisconnect: () -> Boolean, onMonitor: () -> Unit = {}, onAgent: () -> Unit = {}
) : ImageButton(context) {
    init {
        val padding = (12*resources.displayMetrics.density).toInt()
        setPadding(padding,padding,padding,padding)
        setImageResource(R.drawable.ic_more_vertical)
        contentDescription = AppText.get(R.string.device_more, deviceName)
        tooltipText = AppText.get(R.string.more_power_check)
        imageTintList = ColorStateList.valueOf(Color.parseColor("#435A9A"))
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#225EC4AF")),null,null)
        setOnClickListener {
            PopupMenu(context,this).apply {
                menu.add(0,0,0,AppText.get(R.string.agent_refresh))
                menu.add(0,1,0,AppText.get(R.string.agent_cancel)).isEnabled = canDisconnect()
                menu.add(0,2,0,AppText.get(R.string.monitor_link))
                menu.add(0,3,0,AppText.get(R.string.agent_settings))
                setOnMenuItemClickListener {
                    when(it.itemId) { 0 -> onCheck(); 1 -> if (canDisconnect()) onDisconnect(); 2 -> onMonitor(); 3 -> onAgent() }
                    true
                }
                show()
            }
        }
    }
}
