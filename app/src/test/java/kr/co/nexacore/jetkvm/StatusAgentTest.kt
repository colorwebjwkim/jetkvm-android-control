package kr.co.nexacore.jetkvm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class StatusAgentTest {
    private val fixture = """{"schemaVersion":1,"agent":{"version":"0.1.0","collectorConnected":true,"lastError":""},"device":{"online":{"value":true,"freshness":"fresh","retained":false},"extension":"unknown","extensionVerified":false},"power":{"atx":{"value":true,"freshness":"unknown","retained":true,"receivedAt":"2026-10-18T12:00:00Z"},"dc":{"value":null},"usableForControl":false},"sessions":{"value":0,"freshness":"stale"}}"""
    @Test fun preservesUnknownRetainedAndStale() {
        val state = AgentStatus.parse(fixture)
        assertEquals("unknown",state.extension)
        assertEquals(true,state.atx.value)
        assertTrue(state.atx.retained)
        assertEquals("unknown",state.atx.freshness)
        assertNull(state.dc.value)
        assertEquals(0,state.sessions.value)
        assertEquals("stale",state.sessions.freshness)
        assertEquals(true,state.online.value)
    }
    @Test fun malformedOrUnsupportedSchemaIsRejected() {
        listOf("{}",fixture.replace("schemaVersion\":1","schemaVersion\":2"),"not json").forEach {
            assertTrue(runCatching { AgentStatus.parse(it) }.isFailure)
        }
        val state = AgentStatus.parse(fixture.replace("\"value\":true","\"value\":\"true\"").replace("\"value\":0","\"value\":-1"))
        assertNull(state.atx.value); assertNull(state.sessions.value)
    }
    @Test fun requiresExplicitHttpsOrigin() {
        listOf("http://localhost:8080","https://example.com/path","https://user:pass@example.com","https://example.com/?a=b").forEach {
            assertTrue(runCatching { StatusAgentClient.normalize(it) }.isFailure)
        }
        assertEquals("https://example.com:8443/",StatusAgentClient.normalize("https://example.com:8443"))
    }
    private fun tls(test: (MockWebServer,StatusAgentClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCert.sslSocketFactory(),false); server.start()
            StatusAgentClient(OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager).build()).use { client -> test(server,client) }
        }
    }
    @Test fun authenticatedTlsReadUsesExactEndpointAndNoRedirect() = tls { server,client ->
        server.enqueue(MockResponse().setBody(fixture))
        assertEquals("0.1.0",client.fetch(server.url("/").toString(),"fixture-token").version)
        val request = server.takeRequest(2,TimeUnit.SECONDS)!!
        assertEquals("/api/v1/status",request.path)
        assertEquals("GET",request.method)
        assertEquals("Bearer fixture-token",request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/secret")))
        assertTrue(runCatching { client.fetch(server.url("/").toString(),"fixture-token") }.isFailure)
        server.takeRequest(); assertEquals(2,server.requestCount)
    }
    @Test fun closeCancelsInFlightRequest() = tls { server,client ->
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { runCatching { client.fetch(server.url("/").toString(),"fixture-token") }.isFailure }
            check(server.takeRequest(2,TimeUnit.SECONDS) != null)
            client.close()
            assertTrue(result.get(2,TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
    @Test fun untrustedCertificateIsRejected() = tls { server,_ ->
        server.enqueue(MockResponse().setBody(fixture))
        StatusAgentClient().use { client ->
            val failure = runCatching { client.fetch(server.url("/").toString(),"fixture-token") }.exceptionOrNull()
            assertTrue(failure is javax.net.ssl.SSLException)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun authAndOversizeAndClosedRequestsFail() = tls { server,client ->
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { client.fetch(server.url("/").toString(),"fixture-token") }.exceptionOrNull() is AgentAuthException)
        server.enqueue(MockResponse().setBody(" ".repeat(65537)))
        assertTrue(runCatching { client.fetch(server.url("/").toString(),"fixture-token") }.isFailure)
        client.close()
        assertTrue(runCatching { client.fetch(server.url("/").toString(),"fixture-token") }.isFailure)
        assertEquals(2,server.requestCount)
    }
}
