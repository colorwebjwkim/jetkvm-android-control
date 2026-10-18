package kr.co.nexacore.jetkvm

import android.app.Instrumentation
import android.util.Base64
import org.json.JSONObject
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MonitorSignalSmoke {
    fun run(instrumentation: Instrumentation) {
        val key=ByteArray(32) { (it+1).toByte() }
        val hex=key.joinToString("") { "%02x".format(it.toInt() and 255) }
        ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=8000
            val code="nexacore-monitor://127.0.0.1:${server.localPort}/127.0.0.1?key=$hex"
            val events=java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
            val active=CountDownLatch(1); val ended=CountDownLatch(1); val failure=AtomicReference<Throwable>()
            val thread=Thread {
                try {
                    repeat(3) {
                        server.accept().use { socket ->
                            socket.soTimeout=3000
                            val outer=JSONObject(socket.getInputStream().bufferedReader().readLine())
                            val body=Base64.decode(outer.getString("body"),Base64.NO_WRAP)
                            val expected=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key,"HmacSHA256")) }.doFinal(body)
                            check(MessageDigest.isEqual(expected,Base64.decode(outer.getString("mac"),Base64.NO_WRAP)))
                            val event=JSONObject(String(body,Charsets.UTF_8)); events.add(event)
                            socket.getOutputStream().write("OK\n".toByteArray())
                            if (event.getBoolean("viewing")) active.countDown()
                            if (events.size==3) ended.countDown()
                        }
                    }
                } catch (error: Throwable) { failure.set(error); active.countDown(); ended.countDown() }
            }.apply { start() }
            check(MonitorLink.parse(code).key.contentEquals(key))
            check(runCatching { MonitorLink.parse(code.replace("key=","token=")) }.isFailure)
            val store=PasswordStore(instrumentation.targetContext)
            try {
                store.write("monitor.smoke","test://monitor",code)
                check(store.read("monitor.smoke","test://monitor")==code)
            } finally { store.remove("monitor.smoke") }
            MonitorSessionReporter(instrumentation.targetContext,code).close() // Device list/power-only presence is idle.
            val viewer=MonitorSessionReporter(instrumentation.targetContext,code)
            viewer.start()
            try {
                check(active.await(8,TimeUnit.SECONDS))
            } finally { viewer.close() }
            check(ended.await(8,TimeUnit.SECONDS)); thread.join(1000)
            failure.get()?.let { throw it }
            check(events.count { it.getBoolean("viewing") }==1)
            check(!events.last().getBoolean("viewing"))
            check(events.map { it.getLong("seq") }.distinct().size==3)
            check(events.map { it.getString("sender") }.distinct().size==1)
            check(events.all { it.getString("device")=="127.0.0.1" && !it.has("key") })
        }
    }
}
