package kr.co.nexacore.jetkvm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.webrtc.PeerConnectionFactory

object WebRtcNetworkPolicy {
    fun vpnAvailable(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
    /** Avoid binding ICE sockets to the underlying Wi-Fi network when Android routes through a VPN. */
    fun options(vpn: Boolean) = PeerConnectionFactory.Options().apply { disableNetworkMonitor = vpn }
}
