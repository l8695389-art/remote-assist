package com.example.remoteassist

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.remoteassist.databinding.ActivityControllerBinding
import com.example.remoteassist.signaling.GestureCommand
import com.example.remoteassist.signaling.SignalingClient
import com.example.remoteassist.signaling.SignalingMessage
import com.example.remoteassist.webrtc.WebRtcClient
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.math.hypot

class ControllerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerBinding
    private lateinit var eglBase: EglBase
    private var signaling: SignalingClient? = null
    private var webRtc: WebRtcClient? = null

    private var sessionId: String? = null
    private var pairingCode: String? = null
    private var remoteScreenW = 0
    private var remoteScreenH = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var longPressFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        eglBase = EglBase.create()

        binding.btnConnect.setOnClickListener {
            val code = binding.etPairingCode.text.toString().trim()
            if (code.length in 6..8) connectToRoom(code) else {
                binding.tvStatus.text = "Nhập mã ghép đôi hợp lệ (6-8 ký tự)"
            }
        }
        binding.btnDisconnect.setOnClickListener {
            teardown()
            finish()
        }

        binding.remoteRenderer.setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun connectToRoom(code: String) {
        pairingCode = code
        binding.tvStatus.text = getString(R.string.status_connecting)

        val url = "${AppConfig.SIGNALING_SERVER_URL}?code=$code"
        signaling = SignalingClient(url, object : SignalingClient.Listener {
            override fun onOpen() {
                signaling?.send(SignalingMessage(type = "join", code = code, role = "controller"))
            }

            override fun onMessage(msg: SignalingMessage) = handleSignalingMessage(msg)

            override fun onClosed(code: Int, reason: String) {
                runOnUiThread { binding.tvStatus.text = getString(R.string.status_disconnected) }
            }

            override fun onFailure(t: Throwable) {
                runOnUiThread { binding.tvStatus.text = "Lỗi kết nối: ${t.message}" }
            }
        })
        signaling?.connect()
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        when (msg.type) {
            "joined" -> {
                sessionId = msg.sessionId
                runOnUiThread { binding.tvStatus.text = getString(R.string.status_connected) }
                setUpPeerConnection()
            }
            "offer" -> {
                remoteScreenW = msg.screenWidth ?: 0
                remoteScreenH = msg.screenHeight ?: 0
                val sdp = msg.sdp ?: return
                webRtc?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                webRtc?.createAnswer { desc ->
                    signaling?.send(
                        SignalingMessage(
                            type = "answer",
                            code = pairingCode,
                            sessionId = sessionId,
                            sdp = desc.description,
                            sdpType = "answer"
                        )
                    )
                }
            }
            "ice_candidate" -> {
                val candidate = msg.candidate ?: return
                webRtc?.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex ?: 0, candidate))
            }
            "peer_left", "error" -> {
                runOnUiThread { binding.tvStatus.text = msg.reason ?: getString(R.string.status_disconnected) }
            }
        }
    }

    private fun setUpPeerConnection() {
        webRtc = WebRtcClient(this, eglBase, AppConfig.iceServers(), object : WebRtcClient.Callbacks {
            override fun onLocalIceCandidate(candidate: IceCandidate) {
                signaling?.send(
                    SignalingMessage(
                        type = "ice_candidate",
                        code = pairingCode,
                        sessionId = sessionId,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    runOnUiThread { binding.tvStatus.text = "Kết nối thất bại" }
                }
            }

            override fun onRemoteVideoTrack(track: VideoTrack) {
                runOnUiThread {
                    binding.layoutConnect.visibility = View.GONE
                    track.addSink(binding.remoteRenderer)
                }
            }

            override fun onDataChannelMessage(text: String) { /* controller does not receive gesture commands */ }

            override fun onDataChannelReady() {
                runOnUiThread { binding.tvStatus.text = getString(R.string.status_sharing_active) }
            }
        }).apply {
            initFactory()
            createPeerConnection()
            attachRenderer(binding.remoteRenderer)
        }
    }

    // ---------------- Touch -> gesture translation ----------------

    private fun handleTouch(event: MotionEvent): Boolean {
        val sid = sessionId ?: return true
        val w = binding.remoteRenderer.width.takeIf { it > 0 } ?: return true
        val h = binding.remoteRenderer.height.takeIf { it > 0 } ?: return true
        val nx = (event.x / w).coerceIn(0f, 1f)
        val ny = (event.y / h).coerceIn(0f, 1f)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = nx; downY = ny; downTime = System.currentTimeMillis(); longPressFired = false
                longPressRunnable = Runnable {
                    longPressFired = true
                    webRtc?.sendGesture(
                        GestureCommand("long_press", sid, downX, downY, durationMs = 600).toJson()
                    )
                }
                mainHandler.postDelayed(longPressRunnable!!, 500)
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                if (!longPressFired) {
                    val dist = hypot((nx - downX).toDouble(), (ny - downY).toDouble())
                    val elapsed = System.currentTimeMillis() - downTime
                    if (dist < 0.02) {
                        webRtc?.sendGesture(GestureCommand("tap", sid, downX, downY).toJson())
                    } else {
                        webRtc?.sendGesture(
                            GestureCommand(
                                "swipe", sid, downX, downY, nx, ny,
                                durationMs = elapsed.coerceIn(50, 2000)
                            ).toJson()
                        )
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            }
        }
        return true
    }

    private fun teardown() {
        signaling?.send(SignalingMessage(type = "peer_left", code = pairingCode, sessionId = sessionId))
        webRtc?.close()
        webRtc = null
        signaling?.close()
        signaling = null
    }

    override fun onDestroy() {
        teardown()
        eglBase.release()
        super.onDestroy()
    }
}
