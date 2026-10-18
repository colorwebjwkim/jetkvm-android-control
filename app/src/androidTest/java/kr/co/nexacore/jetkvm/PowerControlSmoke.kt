package kr.co.nexacore.jetkvm

import android.app.Instrumentation
import android.os.SystemClock
import android.util.Base64
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Complete local HTTP/WebSocket/WebRTC RPC exchange. No external device or power output. */
object PowerControlSmoke {
    fun run(instrumentation: Instrumentation) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(instrumentation.targetContext).createInitializationOptions())
        val server = MockWebServer()
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val socket = AtomicReference<WebSocket>()
        val remoteSet = AtomicBoolean(false)
        val candidates = mutableListOf<IceCandidate>()
        val power = AtomicBoolean(true)
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable>()
        val stateWait = AtomicReference(true to CountDownLatch(1))
        val rejected = AtomicReference(CountDownLatch(1))
        val closed = CountDownLatch(1)
        var remoteChannel: DataChannel? = null
        var client: NativeJetKvmClient? = null
        fun send(type: String,data: Any) { socket.get()?.send(JSONObject().put("type",type).put("data",data).toString()) }
        fun observer(onCreate: (SessionDescription) -> Unit = {},onSet: () -> Unit = {}) = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
            override fun onSetSuccess() = onSet()
            override fun onCreateFailure(error: String) { failure.set(IllegalStateException(error)) }
            override fun onSetFailure(error: String) { failure.set(IllegalStateException(error)) }
        }
        val peer = checkNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) { send("new-ice-candidate",JSONObject().put("candidate",candidate.sdp).put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex)) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(channel: DataChannel) {
                remoteChannel = channel
                if (channel.label() != "rpc") { failure.set(IllegalStateException("Unexpected HID channel")); return }
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(amount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        try {
                            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                            val request = JSONObject(String(bytes,Charsets.UTF_8))
                            val result: Any = when(request.getString("method")) {
                                "getActiveExtension" -> "atx-power"
                                "getATXState" -> JSONObject().put("power",power.get())
                                "setATXPowerAction" -> {
                                    val action = request.getJSONObject("params").getString("action"); commands.add(action)
                                    if (action == "power-short") power.set(!power.get())
                                    true
                                }
                                else -> error("Unexpected RPC method")
                            }
                            val response = JSONObject().put("jsonrpc","2.0").put("id",request.get("id")).put("result",result).toString()
                            channel.send(DataChannel.Buffer(ByteBuffer.wrap(response.toByteArray()),false))
                        } catch (t: Throwable) { failure.set(t) }
                    }
                })
            }
        }))
        try {
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { closed.countDown() }
                override fun onClosed(webSocket: WebSocket,code: Int,reason: String) { closed.countDown() }
                override fun onOpen(webSocket: WebSocket,response: Response) { socket.set(webSocket); send("device-metadata",JSONObject()) }
                override fun onMessage(webSocket: WebSocket,text: String) {
                    try {
                        val message = JSONObject(text)
                        when(message.getString("type")) {
                            "offer" -> {
                                val offer = JSONObject(String(Base64.decode(message.getJSONObject("data").getString("sd"),Base64.DEFAULT),Charsets.UTF_8)).getString("sdp")
                                check(offer.contains("m=application") && !offer.contains("m=video") && !offer.contains("m=audio")) { "Power connection must be data-only" }
                                peer.setRemoteDescription(observer(onSet = {
                                    synchronized(candidates) { remoteSet.set(true); candidates.forEach { peer.addIceCandidate(it) }; candidates.clear() }
                                    peer.createAnswer(observer(onCreate = { answer ->
                                        peer.setLocalDescription(observer(onSet = {
                                            val encoded = Base64.encodeToString(JSONObject().put("type","answer").put("sdp",answer.description).toString().toByteArray(),Base64.NO_WRAP)
                                            send("answer",encoded)
                                        }),answer)
                                    }),MediaConstraints())
                                }),SessionDescription(SessionDescription.Type.OFFER,offer))
                            }
                            "new-ice-candidate" -> {
                                val data = message.getJSONObject("data")
                                val candidate = IceCandidate(data.getString("sdpMid"),data.getInt("sdpMLineIndex"),data.getString("candidate"))
                                synchronized(candidates) { if (remoteSet.get()) peer.addIceCandidate(candidate) else candidates.add(candidate) }
                            }
                        }
                    } catch (t: Throwable) { failure.set(t) }
                }
            }))
            server.start()
            val baseUrl = server.url("/").toString()
            instrumentation.runOnMainSync {
                client = NativeJetKvmClient(instrumentation.targetContext,baseUrl,SurfaceViewRenderer(instrumentation.targetContext),
                    { if (it == AppText.get(R.string.power_changed_no_command) || it == AppText.get(R.string.extension_check_no_command)) rejected.get().countDown() },
                    { if (it.usable(SystemClock.elapsedRealtime()) && it.on == stateWait.get().first) stateWait.get().second.countDown() },{},{ _,_ -> },controlOnly = true)
                client!!.start("")
            }
            fun await(latch: CountDownLatch,label: String) {
                check(latch.await(20,TimeUnit.SECONDS)) { "$label timeout: ${failure.get()?.message}" }
                failure.get()?.let { throw IllegalStateException("Mock peer failure",it) }
            }
            await(stateWait.get().second,"Initial ATX state")
            client!!.powerAction("power-on")
            await(rejected.get(),"Already-on rejection")
            check(commands.isEmpty())
            // State changes after the displayed snapshot: the pre-command query must prevent a toggle.
            power.set(false); rejected.set(CountDownLatch(1)); client!!.powerAction("power-off")
            await(rejected.get(),"Changed-state rejection")
            check(commands.isEmpty())
            power.set(true)
            stateWait.set(false to CountDownLatch(1)); client!!.powerAction("power-off")
            await(stateWait.get().second,"Power-off refresh")
            check(commands.toList() == listOf("power-short"))
            rejected.set(CountDownLatch(1)); client!!.powerAction("reboot")
            await(rejected.get(),"Powered-off reset rejection")
            check(commands.size == 1)
            stateWait.set(true to CountDownLatch(1)); client!!.powerAction("power-on")
            await(stateWait.get().second,"Power-on refresh")
            check(commands.toList() == listOf("power-short","power-short"))
            // Wait for acknowledged reset and final state refresh, without touching real hardware.
            stateWait.set(true to CountDownLatch(1)); client!!.powerAction("reboot")
            await(stateWait.get().second,"Reset refresh")
            check(commands.toList() == listOf("power-short","power-short","reset"))
            instrumentation.runOnMainSync { client!!.close() }
            await(closed,"Signaling connection close")
        } finally {
            instrumentation.runOnMainSync { client?.close() }
            remoteChannel?.unregisterObserver(); remoteChannel?.close(); remoteChannel?.dispose()
            peer.close(); peer.dispose(); factory.dispose(); server.close()
        }
    }
}
