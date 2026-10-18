package kr.co.nexacore.jetkvm

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.TimeUnit

class AgentAuthException : Exception()

data class AgentReading(val value: Any?, val freshness: String, val retained: Boolean, val receivedAt: String?) {
    fun display(): String {
        val shown = when(value) {
            true -> AppText.get(R.string.state_on)
            false -> AppText.get(R.string.state_off)
            is Int -> value.toString()
            else -> AppText.get(R.string.state_unknown)
        }
        val quality = when {
            retained -> AppText.get(R.string.agent_retained)
            freshness == "fresh" -> AppText.get(R.string.agent_fresh)
            freshness == "stale" -> AppText.get(R.string.agent_stale)
            else -> AppText.get(R.string.state_unknown)
        }
        return "$shown ($quality)" + (receivedAt?.let { " 쨌 $it" } ?: "")
    }
}
data class AgentStatus(val version: String, val collectorConnected: Boolean?, val online: AgentReading,
    val extension: String, val atx: AgentReading, val dc: AgentReading, val sessions: AgentReading, val collectionError: Boolean) {
    fun display() = listOf(
        AppText.get(R.string.agent_summary,version,when (collectorConnected) { true -> AppText.get(R.string.agent_connected); false -> AppText.get(R.string.agent_disconnected); null -> AppText.get(R.string.state_unknown) }),
        AppText.get(R.string.agent_online,online.display()),
        AppText.get(R.string.agent_power,extension,atx.display(),dc.display()),
        AppText.get(R.string.agent_sessions,sessions.display()),
        if (collectionError) AppText.get(R.string.agent_collection_error) else AppText.get(R.string.agent_read_only)
    ).joinToString("\n")

    companion object {
        fun parse(body: String): AgentStatus {
            val root = JSONObject(body)
            require(root.opt("schemaVersion") == 1)
            val agent = root.getJSONObject("agent")
            val device = root.getJSONObject("device")
            val power = root.getJSONObject("power")
            fun reading(obj: JSONObject?, count: Boolean = false): AgentReading {
                val raw = obj?.opt("value")
                val value = if (count) (raw as? Int)?.takeIf { it >= 0 } else raw as? Boolean
                return AgentReading(value,obj?.optString("freshness")?.takeIf { it in setOf("fresh","stale") } ?: "unknown",
                    obj?.opt("retained") == true,obj?.opt("receivedAt") as? String)
            }
            return AgentStatus(agent.getString("version"),agent.opt("collectorConnected") as? Boolean,
                reading(device.optJSONObject("online")),device.optString("extension","unknown"),
                reading(power.optJSONObject("atx")),reading(power.optJSONObject("dc")),reading(root.optJSONObject("sessions"),true),
                agent.optString("lastError").isNotBlank())
        }
    }
}

/** Dedicated no-cookie client. Never discovers ports, follows redirects, or invokes command endpoints. */
class StatusAgentClient internal constructor(transport: OkHttpClient = OkHttpClient()) : Closeable {
    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(4,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS).callTimeout(6,TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    @Volatile private var call: okhttp3.Call? = null
    fun fetch(base: String,token: String): AgentStatus {
        val normalized = normalize(base)
        require(token.isNotBlank() && token.all { it.code in 33..126 })
        val pending = http.newCall(Request.Builder().url(normalized + "api/v1/status")
            .header("Authorization","Bearer $token").header("Cache-Control","no-store").build())
        synchronized(this) { check(!closed); call = pending }
        pending.execute().use { response ->
            if (response.code == 401 || response.code == 403) throw AgentAuthException()
            check(response.code == 200)
            val body = response.body ?: error("body")
            // Bound decompressed bytes, including chunked/gzip responses.
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65536)
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 65536)
            return AgentStatus.parse(bytes.toString(Charsets.UTF_8))
        }
    }
    override fun close() {
        synchronized(this) { closed = true; call?.cancel() }
        http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }
    companion object {
        fun normalize(address: String): String {
            val base = DeviceAddress.normalize(address,"")
            // Agent rejects non-loopback plaintext listeners; require its authenticated TLS API.
            require(base.startsWith("https://"))
            return base
        }
    }
}
