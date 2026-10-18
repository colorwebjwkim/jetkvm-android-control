package kr.co.nexacore.jetkvm

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import okhttp3.mockwebserver.MockWebServer

/** Local fixtures only. Proves list visits never initiate JetKVM HTTP/WebRTC. */
object DeviceLifecycleSmoke {
    fun run(i: Instrumentation) {
        val server = MockWebServer().apply { start() }
        val activity = i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val device = MainActivity.Device("agent-ui-fixture","Agent fixture",server.url("/").toString())
        val prefs = i.targetContext.getSharedPreferences("devices",0)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)!!
        fun invoke(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            i.runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
        }
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        var dialog: AlertDialog? = null
        try {
            main {
                @Suppress("UNCHECKED_CAST") val devices = field("devices") as MutableList<MainActivity.Device>
                devices.clear(); devices.add(device); invoke("home")
            }
            Thread.sleep(5500)
            main {
                @Suppress("UNCHECKED_CAST") val rows = field("deviceActions") as Map<String,DeviceActionRow>
                val row = rows.getValue(device.id)
                check(row.connect.isEnabled)
                check(listOf(row.powerOn,row.powerOff,row.reboot).all { it.visibility == View.GONE })
                check((field("agentRequests") as Map<*,*>).isEmpty())
                check((field("agentStates") as Map<*,*>).isEmpty())
                dialog = MainActivity::class.java.getDeclaredMethod("configureAgent",MainActivity.Device::class.java).apply { isAccessible = true }.invoke(activity,device) as AlertDialog
            }
            i.waitForIdleSync()
            main {
                val fields = all(dialog!!.window!!.decorView).filterIsInstance<EditText>()
                check(fields.size == 2)
                fields[0].setText("http://127.0.0.1:8080")
                fields[1].setText("fixture-token")
                dialog!!.getButton(-1).performClick()
                check(fields[0].error != null && dialog!!.isShowing)
                fields[0].setText("https://127.0.0.1:9")
                dialog!!.getButton(-1).performClick()
            }
            val savedUntil = System.currentTimeMillis()+8000
            while (System.currentTimeMillis()<savedUntil && !prefs.contains("agent.${device.id}")) Thread.sleep(50)
            check(prefs.getString("agent.${device.id}",null) == "https://127.0.0.1:9/")
            i.waitForIdleSync()
            val until = System.currentTimeMillis()+10000
            while (System.currentTimeMillis()<until) {
                var empty = false
                main { empty = (field("agentRequests") as Map<*,*>).isEmpty() }
                if (empty) break
                Thread.sleep(50)
            }
            main {
                check((field("agentStates") as Map<*,*>).isEmpty())
                @Suppress("UNCHECKED_CAST") val labels = field("powerLabels") as Map<String,TextView>
                check(labels.getValue(device.id).text == AppText.get(R.string.agent_unavailable))
                i.callActivityOnPause(activity); i.callActivityOnStop(activity)
                check((field("agentRequests") as Map<*,*>).isEmpty())
                check((field("agentStates") as Map<*,*>).isEmpty())
                i.callActivityOnRestart(activity); i.callActivityOnStart(activity); i.callActivityOnResume(activity)
            }
            check(PasswordStore(i.targetContext).read("agent.${device.id}",device.url) == "fixture-token")
            check(server.requestCount == 0) { "List initiated JetKVM HTTP/WebRTC" }
        } finally {
            main { dialog?.dismiss(); activity.finish() }; i.waitForIdleSync()
            prefs.edit().remove("agent.${device.id}").commit()
            PasswordStore(i.targetContext).remove("agent.${device.id}")
            server.close()
        }
    }
}
