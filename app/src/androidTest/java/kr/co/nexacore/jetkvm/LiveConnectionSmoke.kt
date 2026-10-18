package kr.co.nexacore.jetkvm

import android.app.Instrumentation
import android.content.Intent
import org.json.JSONArray
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request

/** Optional read-only session probe. Enrolled monitor checks may use the app's saved encrypted login; no secrets are logged. */
object LiveConnectionSmoke {
    fun run(instrumentation: Instrumentation,monitorProbe: Boolean = false,powerOnlyProbe: Boolean = false): String {
        val data = JSONArray(instrumentation.targetContext.getSharedPreferences("devices",0).getString("list","[]"))
        if (data.length() == 0) return "NO_SAVED_DEVICE"
        val url = data.getJSONObject(0).getString("url")
        val deviceId=data.getJSONObject(0).getString("id")
        val monitorCode=if (monitorProbe || powerOnlyProbe) PasswordStore(instrumentation.targetContext).read("monitor.$deviceId",url) ?: return "MONITOR_NOT_PAIRED" else null
        var password=""
        JetKvmHttp(url).use { http ->
            http.client.newCall(Request.Builder().url(http.baseUrl.resolve("/device")!!).build()).execute().use {
                if (it.code == 401 || it.code == 403) {
                    if (monitorProbe || powerOnlyProbe) password=PasswordStore(instrumentation.targetContext).read(deviceId,url) ?: return "SAVED_LOGIN_UNAVAILABLE"
                    else return "AUTH_REQUIRED_NOT_ATTEMPTED"
                } else if (!it.isSuccessful) return "HTTP_${it.code}"
            }
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ready = AtomicBoolean(false); val video = AtomicBoolean(false); val complete = CountDownLatch(1)
        val last = AtomicReference("START")
        val powerReady=AtomicBoolean(false)
        val monitor=if (monitorProbe) MonitorSessionReporter(instrumentation.targetContext,monitorCode!!) else null
        lateinit var client: NativeJetKvmClient
        instrumentation.runOnMainSync {
            val renderer = SurfaceViewRenderer(activity)
            activity.setContentView(renderer)
            client = NativeJetKvmClient(activity,url,renderer,
                { message -> last.set(message); if (message.contains("실패") || message.contains("시간 초과") || message.contains("종료되었습니다")) complete.countDown() },
                { state -> if (powerOnlyProbe && state.usable(android.os.SystemClock.elapsedRealtime())) { powerReady.set(true); complete.countDown() } },
                { value -> ready.set(value); if (ready.get() && video.get()) complete.countDown() },
                { _,_ -> video.set(true); if (ready.get()) complete.countDown() },
                { phase -> if (phase==ConnectionPhase.CONNECTED) monitor?.start(); if (phase==ConnectionPhase.DISCONNECTED) monitor?.close() },controlOnly=powerOnlyProbe)
            client.start(password)
        }
        complete.await(40,TimeUnit.SECONDS)
        if ((monitorProbe && ready.get() && video.get()) || (powerOnlyProbe && powerReady.get())) Thread.sleep(15000)
        monitor?.close()
        if (monitor!=null) check(monitor.awaitClosed()) { "Monitor disconnect acknowledgement missing" }
        instrumentation.runOnMainSync { client.close(); activity.finish() }
        return if (powerOnlyProbe && powerReady.get()) "POWER_CONNECTED_NO_VIEWER" else if (ready.get() && video.get()) "VIDEO_AND_HID_CONNECTED" else "ready=${ready.get()},video=${video.get()},stage=${last.get()}"
    }
}
