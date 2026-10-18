package kr.co.nexacore.jetkvm

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MonitorLink(val host: String,val port: Int,val device: String,val key: ByteArray) {
    companion object {
        fun parse(value: String): MonitorLink {
            val uri=URI(value.trim())
            require(uri.scheme=="nexacore-monitor" && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.rawUserInfo==null && uri.fragment==null)
            val hex=uri.rawQuery?.takeIf { it.startsWith("key=") }?.removePrefix("key=") ?: ""
            require(hex.matches(Regex("[0-9a-fA-F]{64}")))
            val device=uri.path.removePrefix("/")
            require(device.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && device.split('.').all { it.toInt() in 0..255 })
            return MonitorLink(uri.host,uri.port,device,hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
    }
}

/** Signed viewer snapshots only. Power-only WebRTC sessions never set viewing=true. */
class MonitorSessionReporter(context: Context,code: String,private val onError: () -> Unit = {}) {
    private val link=MonitorLink.parse(code)
    private val sender=context.getSharedPreferences("monitor_sender",Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("id",null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("id",it).commit()) }
    }
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private val closed=AtomicBoolean(false)
    private val started=AtomicBoolean(false)
    private val warned=AtomicBoolean(false)
    private val completed=java.util.concurrent.CountDownLatch(1)
    @Volatile private var endDelivered=false
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>?=null
    @Synchronized fun start() {
        if (closed.get() || !started.compareAndSet(false,true)) return
        heartbeat=executor.scheduleWithFixedDelay({
            val seq=synchronized(this) { if (closed.get()) null else sequence.incrementAndGet() }
            if (seq!=null) send(true,seq)
        },0,5,TimeUnit.SECONDS)
    }
    @Synchronized fun close() {
        if (!closed.compareAndSet(false,true)) return
        heartbeat?.cancel(false)
        val seq=sequence.incrementAndGet()
        executor.execute {
            try { for (attempt in 0..2) { if (send(false,seq)) { endDelivered=true; break } } }
            finally { completed.countDown(); executor.shutdown() }
        }
    }
    internal fun awaitClosed(seconds: Long = 8) = completed.await(seconds,TimeUnit.SECONDS) && endDelivered
    private fun send(viewing: Boolean,seq: Long): Boolean {
        return runCatching {
            val body=JSONObject().put("v",1).put("device",link.device).put("sender",sender)
                .put("epoch",epoch).put("seq",seq).put("time",System.currentTimeMillis()/1000).put("viewing",viewing).toString().toByteArray(Charsets.UTF_8)
            val mac=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(link.key,"HmacSHA256")) }.doFinal(body)
            val envelope=JSONObject().put("body",Base64.encodeToString(body,Base64.NO_WRAP)).put("mac",Base64.encodeToString(mac,Base64.NO_WRAP)).toString()+"\n"
            Socket().use { socket ->
                socket.connect(InetSocketAddress(link.host,link.port),2000); socket.soTimeout=2000
                socket.getOutputStream().write(envelope.toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush()
                check(socket.getInputStream().read()==79 && socket.getInputStream().read()==75 && socket.getInputStream().read()==10)
            }
        }.onFailure { if (warned.compareAndSet(false,true)) android.os.Handler(android.os.Looper.getMainLooper()).post { onError() } }.isSuccess
    }
    companion object {
        private val epoch=System.currentTimeMillis()
        private val sequence=AtomicLong()
    }
}
