package kr.co.nexacore.jetkvm

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual native NetworkMonitor and offer creation without a JetKVM or credentials. */
class WebRtcSmokeInstrumentation : Instrumentation() {
    private var probe = false
    private var videoSurface = false
    private var disconnectAll = false
    private var pairingCode: String? = null
    private var monitorLive = false
    private var monitorPowerLive = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); probe = arguments?.getString("probe") == "true"; videoSurface = arguments?.getString("videoSurface") == "true"; disconnectAll = arguments?.getString("disconnectAll") == "true"; pairingCode=arguments?.getString("pairMonitor"); monitorLive=arguments?.getString("monitorLive")=="true"; monitorPowerLive=arguments?.getString("monitorPowerLive")=="true"; start() }
    override fun onStart() {
        // Keep fixture Activity launches from restoring a real user's remote device.
        PowerRestorePolicy.automaticConnectionsAllowed = false
        try { runChecks() } finally { PowerRestorePolicy.automaticConnectionsAllowed = true }
    }
    private fun runChecks() {
        if (videoSurface) { VideoSurfaceSmoke.run(this); return }
        val report = Bundle()
        try {
            pairingCode?.let { code ->
                try {
                    val link=MonitorLink.parse(code)
                    val list=org.json.JSONArray(targetContext.getSharedPreferences("devices",0).getString("list","[]"))
                    val devices=(0 until list.length()).map { list.getJSONObject(it) }.filter { java.net.URI(it.getString("url")).host==link.device }
                    check(devices.size==1)
                    val device=devices.single()
                    PasswordStore(targetContext).write("monitor.${device.getString("id")}",device.getString("url"),code)
                    MonitorSessionReporter(targetContext,code).close()
                    report.putString("pairing","PASS: exact matching device paired with encrypted code storage")
                } catch (_: Exception) { report.putString("pairing","FAILED: pairing code or matching device unavailable") }
                finish(Activity.RESULT_OK,report); return
            }
            if (monitorLive) { report.putString("live",LiveConnectionSmoke.run(this,monitorProbe=true)); finish(Activity.RESULT_OK,report); return }
            if (monitorPowerLive) { report.putString("live",LiveConnectionSmoke.run(this,powerOnlyProbe=true)); finish(Activity.RESULT_OK,report); return }
            if (disconnectAll) {
                DisconnectSmoke.run(this,persistDisconnect = true)
                report.putString("disconnect","PASS: manual disconnect left power auto-restore disabled; no device login or physical power commands")
                finish(Activity.RESULT_OK,report); return
            }
            DeviceLifecycleSmoke.run(this)
            report.putString("deviceLifecycle","PASS: connection-only list has zero JetKVM HTTP/WebRTC requests, HTTPS-only agent settings saved, encrypted token reload, unavailable state, stop/restart cancellation")
            PowerControlSmoke.run(this)
            MonitorSignalSmoke.run(this)
            report.putString("monitorSignal","PASS: signed TCP idle/viewer/end snapshots, stable sender, monotonic sequence, encrypted pairing storage and code validation")
            DisconnectSmoke.run(this)
            report.putString("disconnect","PASS: toolbar disconnect navigates to list with no active agent requests; local mock signaling socket closes")
            report.putString("powerControl","PASS: local HTTP/WebSocket/WebRTC data-only RPC, ATX state query, desired on/off/reset, state-change rejection; no physical hardware")
            ToolbarSmoke.run(this)
            report.putString("toolbar","PASS: connecting/connected/disconnected actions, power gating, 320/360/600/840dp bounds and 48dp touch targets")
            PasswordStoreSmoke.run(this)
            report.putString("passwords", "PASS: Keystore encryption, reload, fresh IV, URL/device binding, tamper rejection, empty password and deletion")
            EditorActionSmoke.run(this)
            LanguageSmoke.run(this)
            report.putString("languages","PASS: Korean default independent of system locale, English/Japanese/Korean picker, Activity recreation, relaunch persistence, translated errors/accessibility and cancel; original preference restored")

            check(targetContext.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) { "ACCESS_NETWORK_STATE missing from installed APK" }
            val connectivity = targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.allNetworks // Same permission boundary used by WebRTC's native observer.
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(targetContext).createInitializationOptions())
            val vpn = WebRtcNetworkPolicy.vpnAvailable(targetContext)
            val options = WebRtcNetworkPolicy.options(vpn)
            check(options.disableNetworkMonitor == vpn)
            val candidateReady = CountDownLatch(1)
            val candidateCount = java.util.concurrent.atomic.AtomicInteger()
            val vpnCandidate = java.util.concurrent.atomic.AtomicBoolean()
            val vpnAddresses = connectivity.allNetworks.filter { connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
                .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }.mapNotNull { it.address.hostAddress }.toSet()
            val factory = PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory()
            try {

                val peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidate(candidate: IceCandidate) {
                        candidateCount.incrementAndGet()
                        if (candidate.sdp.split(' ').getOrNull(4) in vpnAddresses) vpnCandidate.set(true)
                        candidateReady.countDown()
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                    override fun onAddStream(stream: MediaStream) {}
                    override fun onRemoveStream(stream: MediaStream) {}
                    override fun onDataChannel(channel: DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                }) ?: error("PeerConnection creation failed")
                try {

                    peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                    val rpc = peer.createDataChannel("rpc",DataChannel.Init())
                    val hid = peer.createDataChannel("hidrpc",DataChannel.Init())
                    try {

                        val complete = CountDownLatch(1)
                        var failure: String? = null
                        peer.createOffer(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                peer.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(sdp: SessionDescription) {}
                                    override fun onSetSuccess() { complete.countDown() }
                                    override fun onCreateFailure(error: String) {}
                                    override fun onSetFailure(error: String) { failure = error; complete.countDown() }
                                },sdp)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(error: String) { failure = error; complete.countDown() }
                            override fun onSetFailure(error: String) {}
                        },MediaConstraints())
                        check(complete.await(15,TimeUnit.SECONDS)) { "SDP timeout" }
                        check(failure == null) { "SDP failure: $failure" }
                        check(peer.localDescription?.description?.contains("m=application") == true) { "Data channels missing from offer" }
                        check(peer.localDescription?.description?.contains("m=video") == true) { "Video transceiver missing from offer" }
                        // Close only after native ICE gathering has started its network observer.
                        check(candidateReady.await(10,TimeUnit.SECONDS)) { "No local ICE candidates" }
                        Thread.sleep(1000)
                    } finally { rpc.close(); hid.close(); rpc.dispose(); hid.dispose() }
                } finally { peer.close(); peer.dispose() }
            } finally { factory.dispose() }
            report.putString("result","PASS: vpn=$vpn candidates=${candidateCount.get()} vpnCandidate=${vpnCandidate.get()}, VPN-aware factory options, dialog Enter/Next/Done/Go/Send, installed network permission, native PeerConnection, video/data offer and ICE startup")
            if (probe) report.putString("live",LiveConnectionSmoke.run(this))
            finish(Activity.RESULT_OK,report)
        } catch (e: Exception) {
            report.putString("result","FAIL: ${e.javaClass.simpleName}: ${e.message}; ${e.cause?.message}")
            finish(Activity.RESULT_CANCELED,report)
        }
    }
}
