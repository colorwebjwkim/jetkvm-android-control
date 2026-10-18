package kr.co.nexacore.jetkvm

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class JetKvmHttpTest {
    @Test fun loginCookieIsUsedForVerificationAndSignalingAndClearedOnClose() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setHeader("Set-Cookie","authToken=test-only; Path=/; HttpOnly").setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            val session = JetKvmHttp(server.url("/").toString())
            session.authenticate("test-password")
            assertEquals("/device",server.takeRequest().path)
            val login = server.takeRequest()
            assertEquals("/auth/login-local",login.path)
            assertEquals("test-password",JSONObject(login.body.readUtf8()).getString("password"))
            assertEquals("authToken=test-only",server.takeRequest().getHeader("Cookie"))
            assertEquals(1,session.client.cookieJar.loadForRequest(server.url("/webrtc/signaling/client")).size)
            assertTrue(session.client.cookieJar.loadForRequest(server.url("/").newBuilder().port(server.port+1).build()).isEmpty())
            session.close()
            assertTrue(session.client.cookieJar.loadForRequest(server.url("/")).isEmpty())
        }
    }
    @Test fun noPasswordDeviceSkipsLogin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            JetKvmHttp(server.url("/").toString()).use { it.authenticate("") }
            assertEquals(1,server.requestCount)
            assertEquals("GET",server.takeRequest().method)
        }
    }
    @Test fun automaticProbeWithoutSavedPasswordDoesNotAttemptLogin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            JetKvmHttp(server.url("/").toString()).use {
                assertTrue(runCatching { it.authenticate("",loginAllowed = false) }.isFailure)
            }
            assertEquals(1,server.requestCount)
            assertEquals("/device",server.takeRequest().path)
        }
    }
    @Test fun automaticProbeSupportsPasswordlessDevice() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            JetKvmHttp(server.url("/").toString()).use { it.authenticate("",loginAllowed = false) }
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun rateLimitIsNotRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(429))
            JetKvmHttp(server.url("/").toString()).use {
                val error = runCatching { it.authenticate("bad") }.exceptionOrNull()
                assertTrue(error?.message.orEmpty().contains("제한")); assertEquals(2,server.requestCount)
            }
        }
    }
    @Test fun redirectDoesNotForwardCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","http://127.0.0.1:1/"))
            JetKvmHttp(server.url("/").toString()).use {
                assertTrue(runCatching { it.authenticate("secret") }.isFailure)
                assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun successfulLoginWithoutValidSessionFails() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setResponseCode(401))
            JetKvmHttp(server.url("/").toString()).use { assertTrue(runCatching { it.authenticate("x") }.isFailure) }
        }
    }
}
