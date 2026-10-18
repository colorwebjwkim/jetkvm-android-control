package kr.co.nexacore.jetkvm

import android.app.Instrumentation
import android.view.View

object ToolbarSmoke {
    fun run(instrumentation: Instrumentation) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                var lists = 0; var reconnects = 0; var disconnects = 0; var powers = 0
                val bar = ControlToolbar(instrumentation.targetContext,{ lists++ },{ reconnects++ },{ disconnects++ },{ powers++ },{})
                check(!bar.list.isEnabled && !bar.reconnect.isEnabled && !bar.disconnect.isEnabled && !bar.power.isEnabled)
                bar.update(true,ConnectionPhase.CONNECTING,false)
                check(bar.list.isEnabled && !bar.reconnect.isEnabled && bar.disconnect.isEnabled && !bar.power.isEnabled)
                bar.disconnect.performClick(); check(disconnects == 1)
                bar.update(true,ConnectionPhase.CONNECTED,true)
                check(bar.reconnect.isEnabled && bar.disconnect.isEnabled && bar.power.isEnabled)
                bar.list.performClick(); bar.reconnect.performClick(); bar.power.performClick()
                check(lists == 1 && reconnects == 1 && powers == 1)
                bar.update(true,ConnectionPhase.CONNECTED,false); check(!bar.power.isEnabled)
                bar.update(true,ConnectionPhase.INTERRUPTED,true)
                check(bar.reconnect.isEnabled && bar.disconnect.isEnabled && !bar.power.isEnabled)
                bar.update(true,ConnectionPhase.DISCONNECTED,true)
                check(!bar.list.isEnabled && !bar.reconnect.isEnabled && !bar.disconnect.isEnabled && !bar.power.isEnabled)
                val density = bar.resources.displayMetrics.density
                listOf(320,360,600,840).forEach { width ->
                    bar.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((56*density).toInt(),View.MeasureSpec.EXACTLY))
                    bar.layout(0,0,bar.measuredWidth,bar.measuredHeight)
                    val buttons = listOf(bar.list,bar.reconnect,bar.disconnect,bar.power,bar.more)
                    check(buttons.all { it.width >= (48*density).toInt() && it.left >= 0 && it.right <= bar.width }) { "Toolbar clipped at ${width}dp" }
                    check(buttons.zipWithNext().all { (left,right) -> left.right <= right.left }) { "Toolbar buttons overlap" }
                }
                val row = DeviceActionRow(instrumentation.targetContext,{}, {}, {}, {})
                check(row.childCount == 6 && !row.powerOn.isEnabled && !row.powerOff.isEnabled && !row.reboot.isEnabled)
                row.update(PowerSnapshot("atx-power",true,100),now = 101)
                check(!row.powerOn.isEnabled && !row.powerOff.isEnabled && !row.reboot.isEnabled)
                check(listOf(row.powerOn,row.powerOff,row.reboot).all { it.visibility == View.GONE })
                listOf(288,328,568,808).forEach { width ->
                    row.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((48*density).toInt(),View.MeasureSpec.EXACTLY))
                    row.layout(0,0,row.measuredWidth,row.measuredHeight)
                    check((0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility != View.GONE }.all { it.width >= (48*density).toInt() && it.top == 0 && it.right <= row.width }) { "Device icons wrap/clip at ${width}dp" }
                }
            } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw IllegalStateException("Toolbar regression failed",it) }
    }
}
