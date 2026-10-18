package kr.co.nexacore.jetkvm

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** Each session owns its own memory-only cookie jar. No redirects or credential logging. */
class JetKvmHttp(base: String) : Closeable {
    val baseUrl = base.toHttpUrl()
    private val cookies = mutableListOf<Cookie>()
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
                values.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; cookies.add(c) }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
                if (url.host == baseUrl.host && url.port == baseUrl.port && url.scheme == baseUrl.scheme) cookies.filter { it.matches(url) } else emptyList()
            }
        }).build()

    fun authenticate(password: String, loginAllowed: Boolean = true) {
        val device = Request.Builder().url(baseUrl.resolve("/device")!!).build()
        client.newCall(device).execute().use { response ->
            if (response.isSuccessful) return
            check(response.code == 401 || response.code == 403) { AppText.fallback(R.string.device_check_failed, "장비 확인 실패 (HTTP %1\$s)", response.code) }
        }
        check(loginAllowed) { AppText.fallback(R.string.save_password_for_restore, "자동 연결하려면 기타(⋮)에서 비밀번호를 저장하세요") }
        val body = JSONObject().put("password", password).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(baseUrl.resolve("/auth/login-local")!!).post(body).build()).execute().use {
            check(it.isSuccessful) {
                when (it.code) {
                    401 -> AppText.fallback(R.string.password_incorrect, "비밀번호가 올바르지 않습니다.")
                    429 -> AppText.fallback(R.string.login_rate_limited, "로그인 시도가 제한되었습니다. 잠시 후 다시 연결하세요.")
                    else -> AppText.fallback(R.string.login_failed, "로그인 실패 (HTTP %1\$s)", it.code)
                }
            }
        }
        client.newCall(device).execute().use { check(it.isSuccessful) { AppText.fallback(R.string.session_check_failed, "로그인 세션 확인 실패 (HTTP %1\$s)", it.code) } }
    }
    override fun close() {
        client.dispatcher.cancelAll()
        synchronized(cookies) { cookies.clear() }
        client.connectionPool.evictAll()
    }
}
