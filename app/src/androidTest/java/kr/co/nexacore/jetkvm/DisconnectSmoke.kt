package kr.co.nexacore.jetkvm

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.ViewGroup

/** Tests manual session exit without logging into a device or sending power commands. */
object DisconnectSmoke {
    fun run(instrumentation: Instrumentation, persistDisconnect: Boolean = false) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("devices",Context.MODE_PRIVATE)
        val hadPreference = prefs.contains("powerRestoreEnabled")
        val original = prefs.getBoolean("powerRestoreEnabled",true)
        val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                try {
                    org.webrtc.PeerConnectionFactory.initialize(org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                    check(prefs.edit().putBoolean("powerRestoreEnabled",true).commit())
                    var returned = false
                    val remote = NativeSessionView(activity,"http://127.0.0.1:9/",onDisconnect = {
                        activity.disconnectToDeviceList(); returned = true
                    })
                    activity.setContentView(remote)
                    remote.toolbar.update(true,ConnectionPhase.CONNECTED,false)
                    remote.toolbar.disconnect.performClick()
                    check(returned) { "Disconnect did not return to device list" }
                    check(MainActivity::class.java.getDeclaredField("agentRequests").apply { isAccessible = true }.get(activity).let { it is Map<*,*> && it.isEmpty() })
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                    val toolbar = root.getChildAt(0) as ControlToolbar
                    check(!toolbar.reconnect.isEnabled && !toolbar.disconnect.isEnabled)
                    // Repeated delivery is harmless and cannot restart a session.
                    remote.toolbar.disconnect.performClick()
                    check(!toolbar.disconnect.isEnabled)
                } catch (error: Throwable) { failure = error }
            }
            instrumentation.waitForIdleSync()
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            if (!persistDisconnect) {
                val editor = prefs.edit()
                if (hadPreference) editor.putBoolean("powerRestoreEnabled",original) else editor.remove("powerRestoreEnabled")
                check(editor.commit())
            }
        }
    }
}
