package kr.co.nexacore.jetkvm

import java.net.URI

object DeviceAddress {
    fun normalize(address: String, port: String): String {
        val raw = address.trim()
        require(raw.isNotBlank()) { AppText.fallback(R.string.address_required, "주소를 입력하세요.") }
        val uri = URI(if (raw.contains("://")) raw else "http://$raw")
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { AppText.fallback(R.string.address_invalid, "HTTP/HTTPS 주소를 확인하세요. IPv6는 대괄호를 사용하세요.") }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")) { AppText.fallback(R.string.address_device_only, "사용자 정보나 경로 없이 장비 주소만 입력하세요.") }
        val selectedPort = if (port.isBlank()) uri.port else port.trim().toIntOrNull() ?: throw IllegalArgumentException(AppText.fallback(R.string.port_number, "포트는 숫자로 입력하세요."))
        require(selectedPort == -1 || selectedPort in 1..65535) { AppText.fallback(R.string.port_range, "포트 범위는 1~65535입니다.") }
        return URI(uri.scheme, null, uri.host, selectedPort, "/", null, null).toASCIIString()
    }
    fun sameOrigin(base: String, target: String): Boolean = runCatching {
        val a = URI(base); val b = URI(target)
        fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
        a.scheme == b.scheme && a.host.equals(b.host, true) && port(a) == port(b) && b.rawUserInfo == null
    }.getOrDefault(false)
}
