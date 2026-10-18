package kr.co.nexacore.jetkvm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.widget.ImageButton
import android.widget.LinearLayout

class DeviceActionRow(context: Context,onConnect: () -> Unit,onEdit: () -> Unit,onDelete: () -> Unit,onPower: (String) -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    val connect = icon(R.drawable.ic_connect,AppText.get(R.string.connect),"#435A9A",onConnect)
    val edit = icon(R.drawable.ic_edit,AppText.get(R.string.edit),"#435A9A",onEdit)
    val delete = icon(R.drawable.ic_delete,AppText.get(R.string.delete),"#A0433B",onDelete)
    val powerOn = icon(R.drawable.ic_power_on,AppText.get(R.string.power_on),"#386329") { onPower("power-on") }
    val powerOff = icon(R.drawable.ic_power_off,AppText.get(R.string.power_off),"#9B433A") { onPower("power-off") }
    val reboot = icon(R.drawable.ic_reboot,AppText.get(R.string.reboot),"#725B18") { onPower("reboot") }
    init { powerOn.visibility = GONE; powerOff.visibility = GONE; reboot.visibility = GONE; orientation = HORIZONTAL; minimumWidth = dp(144); update(PowerSnapshot()) }
    private fun icon(resource: Int,label: String,color: String,action: () -> Unit) = ImageButton(context).also {
        it.setImageResource(resource); it.contentDescription = label; it.tooltipText = label
        it.setPadding(dp(12),dp(12),dp(12),dp(12))
        it.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#225EC4AF")),null,null)
        it.imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.parseColor("#BAC3CC"),Color.parseColor(color)))
        it.setOnClickListener { action() }; addView(it,LayoutParams(dp(48),dp(48),1f))
    }
    fun update(state: PowerSnapshot,busy: Boolean = false,now: Long = SystemClock.elapsedRealtime()) {
        powerOn.isEnabled = false
        powerOff.isEnabled = false
        reboot.isEnabled = false
        reboot.tooltipText = if (state.extension == "dc-power") AppText.get(R.string.dc_reboot_unsupported) else AppText.get(R.string.reboot)
    }
}
