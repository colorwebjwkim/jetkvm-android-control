package kr.co.nexacore.jetkvm
import org.junit.Assert.*
import org.junit.Test
class DeviceAddressTest {
    @Test fun handlesHostPortAndIpv6() {
        assertEquals("http://192.168.1.20:8080/",DeviceAddress.normalize("192.168.1.20","8080"))
        assertEquals("https://jet.example.ts.net/",DeviceAddress.normalize("https://jet.example.ts.net",""))
        assertEquals("http://[fd7a:115c:a1e0::1]:80/",DeviceAddress.normalize("[fd7a:115c:a1e0::1]","80"))
    }
    @Test fun rejectsUnsafeAddresses() {
        listOf("javascript:alert(1)","http://user:password@host","http://host/path","http://host/?token=secret").forEach {
            assertTrue(runCatching { DeviceAddress.normalize(it,"") }.isFailure)
        }
        assertTrue(runCatching { DeviceAddress.normalize("host","65536") }.isFailure)
        assertTrue(runCatching { DeviceAddress.normalize("host","0") }.isFailure)
    }
    @Test fun restrictsNavigationOrigin() {
        assertTrue(DeviceAddress.sameOrigin("http://host/","http://host:80/login"))
        assertFalse(DeviceAddress.sameOrigin("https://host/","http://host/"))
        assertFalse(DeviceAddress.sameOrigin("http://host/","http://host.evil/"))
        assertFalse(DeviceAddress.sameOrigin("http://host/","http://host:8080/"))
    }
}
