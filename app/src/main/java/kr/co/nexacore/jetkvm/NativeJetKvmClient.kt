package kr.co.nexacore.jetkvm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All signaling, SDP, RPC and HID state is confined to one serial executor. */
class NativeJetKvmClient(
    private val context: Context, base: String, private val renderer: SurfaceViewRenderer,
    private val status: (String) -> Unit, private val power: (PowerSnapshot) -> Unit,
    private val inputReady: (Boolean) -> Unit, private val aspect: (Int, Int) -> Unit,
    private val connectionChanged: (ConnectionPhase) -> Unit = {},
    private val controlOnly: Boolean = false,
    private val powerEnabled: Boolean = true
) : Closeable {
    private val http = JetKvmHttp(base)
    private val serial = Executors.newSingleThreadScheduledExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var socket: WebSocket? = null
    private var rpc: DataChannel? = null
    private var hid: DataChannel? = null
    private var video: VideoTrack? = null
    private var handshake = false
    private var remoteSet = false
    private var offerSent = false
    private val localIce = mutableListOf<IceCandidate>()
    private val remoteIce = mutableListOf<IceCandidate>()
    private val pending = RpcTracker { now() }
    private var snapshot = PowerSnapshot()
    private var polling = false
    private var actionBusy = false
    private var typing = false
    private var stage = "INIT"
    private var localCandidates = 0
    private var remoteCandidates = 0
    private fun trace(code: String) { stage = code; android.util.Log.i("JetKvmConnection",code) }
    private fun now() = SystemClock.elapsedRealtime()
    private fun task(block: () -> Unit) {
        if (stopped.get()) return
        runCatching { serial.execute { if (!stopped.get()) runCatching(block).onFailure { trace("ERROR_${it.javaClass.simpleName}"); fail(AppText.get(R.string.communication_failed, stage)) } } }
    }
    private fun show(text: String) { ui.post { if (!stopped.get()) status(text) } }
    private fun publish(value: PowerSnapshot) { snapshot = value; val shown = if (actionBusy) value.copy(checkedAt = 0) else value; ui.post { if (!stopped.get()) power(shown) } }
    init {
        renderer.init(egl.eglBaseContext,object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(width: Int,height: Int,rotation: Int) {
                val rotated = rotation == 90 || rotation == 270
                ui.post {
                    if (!stopped.get()) aspect(if (rotated) height else width,if (rotated) width else height)
                }
            }
        })
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(false)
    }
    fun start(password: String, loginAllowed: Boolean = true) = task {
        trace("AUTH_START"); show(AppText.get(R.string.authenticating))
        try { http.authenticate(password,loginAllowed) } catch (e: Exception) {
            trace("AUTH_ERROR_${e.javaClass.simpleName}")
            fail(if (e is IllegalStateException) e.message ?: AppText.get(R.string.auth_failed) else AppText.get(R.string.connection_failed)); return@task
        }
        trace("AUTH_OK")
        val vpn = WebRtcNetworkPolicy.vpnAvailable(context)
        trace(if (vpn) "VPN_OS_ROUTING" else "STANDARD_NETWORK")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder().setOptions(WebRtcNetworkPolicy.options(vpn))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        trace("WS_CONNECT"); show(if (vpn) AppText.get(R.string.webrtc_vpn_connecting) else AppText.get(R.string.webrtc_connecting))
        socket = http.client.newWebSocket(Request.Builder().url(http.baseUrl.resolve("/webrtc/signaling/client")!!).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = task { trace("WS_OPEN") }
            override fun onMessage(webSocket: WebSocket, text: String) = task {
                if (text == "pong") return@task
                if (text == "ping") { webSocket.send("pong"); return@task }
                val message = JSONObject(text)
                when (message.optString("type")) {
                    "device-metadata" -> if (peer == null) { trace("METADATA_OK"); createPeer() }
                    "answer" -> {
                        trace("ANSWER_RECEIVED")
                        val data = JSONObject(String(Base64.decode(message.getString("data"), Base64.DEFAULT), Charsets.UTF_8))
                        peer?.setRemoteDescription(sdpObserver(onSet = {
                            trace("ANSWER_APPLIED"); show(AppText.get(R.string.answer_applied)); remoteSet = true; remoteIce.forEach { peer?.addIceCandidate(it) }; remoteIce.clear()
                        }), SessionDescription(SessionDescription.Type.ANSWER, data.getString("sdp")))
                    }
                    "new-ice-candidate" -> {
                        remoteCandidates++; android.util.Log.i("JetKvmConnection","REMOTE_ICE count=$remoteCandidates")
                        val c = message.getJSONObject("data")
                        val ice = IceCandidate(c.optString("sdpMid"),c.optInt("sdpMLineIndex"),c.getString("candidate"))
                        if (remoteSet) peer?.addIceCandidate(ice) else remoteIce.add(ice)
                    }
                    "other-session-connected" -> fail(AppText.get(R.string.other_client_reconnect))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = task { trace("WS_ERROR_${response?.code ?: 0}_${t.javaClass.simpleName}"); fail(AppText.get(R.string.signaling_failed, stage)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = task { fail(if (code == 4001) AppText.get(R.string.other_client_connected) else AppText.get(R.string.connection_ended)) }
        })
        serial.schedule({ if (!stopped.get() && rpc?.state() != DataChannel.State.OPEN) fail(AppText.get(R.string.connection_timeout, stage, localCandidates, remoteCandidates)) },30,TimeUnit.SECONDS)
        serial.scheduleAtFixedRate({ if (!stopped.get()) {
            pending.expire()
            if (!snapshot.usable(now())) publish(PowerSnapshot())
            if (powerEnabled && rpc?.state() == DataChannel.State.OPEN && !polling && !actionBusy) refreshPower()
        } },1,5,TimeUnit.SECONDS)
    }
    private fun signal(type: String, data: Any) {
        check(socket?.send(JSONObject().put("type",type).put("data",data).toString()) == true)
    }
    private fun sdpObserver(onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = task { onCreate(sdp) }
        override fun onSetSuccess() = task { onSet() }
        override fun onCreateFailure(error: String) = task { fail(AppText.get(R.string.sdp_create_failed)) }
        override fun onSetFailure(error: String) = task { fail(AppText.get(R.string.sdp_apply_failed)) }
    }
    private fun createPeer() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = task { android.util.Log.i("JetKvmConnection","SIGNALING_$state") }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = task {
                trace("ICE_$state")
                when(state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> ui.post {
                        if (!stopped.get()) { connectionChanged(ConnectionPhase.CONNECTED); inputReady(handshake) }
                    }
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> fail(AppText.get(R.string.webrtc_ended))
                    PeerConnection.IceConnectionState.DISCONNECTED -> { publish(PowerSnapshot()); ui.post { inputReady(false); connectionChanged(ConnectionPhase.INTERRUPTED) }; show(AppText.get(R.string.connection_unstable)) }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = task { android.util.Log.i("JetKvmConnection","GATHERING_$state") }
            override fun onIceCandidate(candidate: IceCandidate) = task { localCandidates++; android.util.Log.i("JetKvmConnection","LOCAL_ICE count=$localCandidates"); if (offerSent) sendIce(candidate) else localIce.add(candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = task {
                (receiver.track() as? VideoTrack)?.let { track -> video = track; track.addSink(renderer) }
            }
        }) ?: error("PeerConnection unavailable")
        if (!controlOnly) peer!!.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        rpc = peer!!.createDataChannel("rpc",DataChannel.Init()).also { channel -> channel.registerObserver(observer(channel, false)) }
        if (!controlOnly) hid = peer!!.createDataChannel("hidrpc",DataChannel.Init()).also { channel -> channel.registerObserver(observer(channel, true)) }
        peer!!.createOffer(sdpObserver(onCreate = { sdp ->
            peer!!.setLocalDescription(sdpObserver(onSet = {
                val encoded = Base64.encodeToString(JSONObject().put("type","offer").put("sdp",sdp.description).toString().toByteArray(),Base64.NO_WRAP)
                signal("offer",JSONObject().put("sd",encoded)); trace("OFFER_SENT"); show(AppText.get(R.string.offer_sent)); offerSent = true
                localIce.forEach { sendIce(it) }; localIce.clear()
            }),sdp)
        }),MediaConstraints())
    }
    private fun sendIce(c: IceCandidate) = signal("new-ice-candidate",JSONObject().put("candidate",c.sdp).put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex))
    private fun observer(channel: DataChannel, binary: Boolean) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {}
        override fun onStateChange() = task {
            if (channel.state() == DataChannel.State.OPEN) {
                if (binary) {
                    sendHid(byteArrayOf(1,1),true)
                    serial.schedule({ if (!stopped.get() && !handshake) show(AppText.get(R.string.hid_no_response)) },8,TimeUnit.SECONDS)
                } else { trace("RPC_OPEN"); show(AppText.get(R.string.native_connected)); if (powerEnabled) refreshPower() }
            } else if (channel.state() == DataChannel.State.CLOSED) fail(AppText.get(R.string.control_channel_closed))
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
            task {
                if (binary) {
                    if (bytes.size >= 2 && bytes[0] == 1.toByte() && bytes[1] == 1.toByte()) { trace("HID_READY"); handshake = true; ui.post { if (!stopped.get()) inputReady(true) }; show(AppText.get(R.string.native_connected)) }
                } else {
                    val message = JSONObject(String(bytes,Charsets.UTF_8))
                    pending.receive(message)
                }
            }
        }
    }
    private fun request(method: String, params: JSONObject = JSONObject(), callback: (JSONObject?) -> Unit) {
        if (stopped.get()) { callback(null); return }
        val channel = rpc
        if (channel?.state() != DataChannel.State.OPEN) { callback(null); return }
        val id = pending.add(callback)
        val body = JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params).toString()
        if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(body.toByteArray()),false))) pending.failed(id)
    }
    private fun result(response: JSONObject?) = response?.takeUnless { it.has("error") }?.opt("result")
    private fun refreshPower(done: ((PowerSnapshot) -> Unit)? = null) {
        polling = true
        request("getActiveExtension") { reply ->
            val extension = result(reply) as? String ?: ""
            val method = when(extension) { "atx-power" -> "getATXState"; "dc-power" -> "getDCPowerState"; else -> null }
            if (method == null) { polling = false; publish(PowerSnapshot(extension)); done?.invoke(snapshot) }
            else request(method) { state ->
                val obj = result(state) as? JSONObject
                val field = if (extension == "atx-power") "power" else "isOn"
                val value = obj?.opt(field) as? Boolean
                polling = false; publish(PowerSnapshot(extension,value,if (value == null) 0 else now())); done?.invoke(snapshot)
            }
        }
    }
    fun powerAction(action: String) = task {
        if (!powerEnabled || actionBusy || polling || !snapshot.usable(now())) { show(AppText.get(R.string.status_check_busy)); return@task }
        val expected = snapshot.extension
        actionBusy = true; publish(snapshot.copy(checkedAt = 0))
        refreshPower { fresh ->
            if (fresh.extension != expected || !fresh.usable(now())) { actionBusy = false; show(AppText.get(R.string.extension_check_no_command)); return@refreshPower }
            val method: String
            val params: JSONObject
            val command = if (action in setOf("power-on","power-off","reboot")) PowerPolicy.command(action,fresh,now()) else action
            if (command == null) { actionBusy = false; publish(fresh); show(AppText.get(R.string.power_changed_no_command)); return@refreshPower }
            if (fresh.extension == "atx-power" && command in setOf("power-short","power-long","reset")) {
                method = "setATXPowerAction"; params = JSONObject().put("action",command)
            } else if (fresh.extension == "dc-power" && command in setOf("dc-on","dc-off")) {
                method = "setDCPowerState"; params = JSONObject().put("enabled",command == "dc-on")
            } else { actionBusy = false; return@refreshPower }
            // Commands are never retried. A lost acknowledgement leaves the outcome unknown.
            publish(PowerSnapshot())
            request(method,params) { response ->
                actionBusy = false
                show(if (response == null) AppText.get(R.string.power_response_timeout)
                    else if (response.has("error")) AppText.get(R.string.power_command_rejected)
                    else AppText.get(R.string.power_command_accepted))
                refreshPower()
            }
        }
    }
    private fun sendHid(bytes: ByteArray, force: Boolean = false) {
        if (!force && !handshake) return
        if (hid?.state() == DataChannel.State.OPEN && (hid?.bufferedAmount() ?: 0) < 65536) hid?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),true))
    }
    fun pointer(x: Int, y: Int, buttons: Int) = task { if (!typing) sendHid(HidProtocol.pointer(x,y,buttons)) }
    fun key(code: Int, modifier: Int = 0) = task { if (!typing) { sendHid(HidProtocol.keyboard(modifier,code)); sendHid(HidProtocol.keyboard(0)) } }
    fun text(value: String): Boolean {
        if (value.length > 256 || value.any { HidProtocol.ascii(it) == null }) return false
        task {
            if (!handshake || typing) return@task
            typing = true
            value.forEachIndexed { index, c ->
                val (mod,key) = HidProtocol.ascii(c)!!
                serial.schedule({ if (!stopped.get()) sendHid(HidProtocol.keyboard(mod,key)) },index * 70L,TimeUnit.MILLISECONDS)
                serial.schedule({ if (!stopped.get()) sendHid(HidProtocol.keyboard(0)) },index * 70L + 35,TimeUnit.MILLISECONDS)
            }
            serial.schedule({ typing = false },value.length * 70L,TimeUnit.MILLISECONDS)
        }
        return true
    }
    fun releaseInput() = task { sendHid(HidProtocol.keyboard(0)); sendHid(HidProtocol.pointer(0,0,0)) }
    private fun fail(message: String) { ui.post { inputReady(false); power(PowerSnapshot()); status(message) }; close() }
    override fun close() {
        if (!stopped.compareAndSet(false,true)) return
        ui.post { connectionChanged(ConnectionPhase.DISCONNECTED) }
        http.close()
        serial.execute {
            pending.clear()
            sendHid(HidProtocol.keyboard(0)); sendHid(HidProtocol.pointer(0,0,0))
            handshake = false
            socket?.cancel(); socket = null
            video?.removeSink(renderer); video = null
            hid?.unregisterObserver(); rpc?.unregisterObserver()
            hid?.close(); rpc?.close(); hid?.dispose(); rpc?.dispose(); hid = null; rpc = null
            peer?.close(); peer?.dispose(); peer = null; factory?.dispose(); factory = null
            ui.post { renderer.release(); egl.release() }
            serial.shutdownNow()
        }
    }
}
