package com.example.remoteassist.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import com.example.remoteassist.AppConfig
import com.example.remoteassist.session.SessionManager
import com.example.remoteassist.signaling.SignalingClient
import com.example.remoteassist.signaling.SignalingMessage
import com.example.remoteassist.util.NotificationHelper
import com.example.remoteassist.webrtc.WebRtcClient
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Foreground service ("mediaProjection" type) that owns the entire Host-side pipeline:
 * pairing room creation, signaling, WebRTC peer connection + screen capture track,
 * and the ongoing "you are sharing" notification with an instant Stop action.
 *
 * Capture only ever starts from [ACTION_START], which must carry the exact
 * (resultCode, data) pair produced by the MediaProjection system consent dialog that
 * the user just approved in HostActivity. There is no other entry point.
 */
class ScreenCaptureService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()
    private var listener: StatusListener? = null

    interface StatusListener {
        fun onPairingCode(code: String, expiresInSeconds: Long)
        fun onControllerJoined()
        fun onSharingStarted()
        fun onSharingStopped()
        fun onError(message: String)
    }

    fun setStatusListener(l: StatusListener?) { listener = l }

    private lateinit var eglBase: EglBase
    private var signaling: SignalingClient? = null
    private var webRtc: WebRtcClient? = null
    private var pairingCode: String? = null
    private var sessionId: String? = null
    private var isSharing = false

    override fun onCreate() {
        super.onCreate()
        eglBase = EglBase.create()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CREATE_ROOM -> {
                // Only signaling/pairing at this point — no capture, no MediaProjection
                // token yet, so this deliberately does NOT start a mediaProjection-typed
                // foreground service here (Android 14 forbids that before consent).
                // The Activity is in the foreground and bound to this service, which is
                // enough process priority for a WebSocket connection to stay alive.
                connectAndCreateRoom()
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null) {
                    // MediaProjection consent was just granted by the user. Promote to a
                    // mediaProjection-typed foreground service immediately, then capture.
                    ServiceCompat.startForeground(
                        this,
                        NotificationHelper.SHARING_NOTIFICATION_ID,
                        NotificationHelper.buildSharingNotification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                    beginCapture(resultCode, data)
                } else {
                    listener?.onError("Thiếu dữ liệu cấp quyền chia sẻ màn hình")
                }
            }
            ACTION_STOP -> {
                stopSharingAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------- Room + signaling ----------------

    private fun connectAndCreateRoom() {
        signaling = SignalingClient(AppConfig.SIGNALING_SERVER_URL, object : SignalingClient.Listener {
            override fun onOpen() {
                signaling?.send(SignalingMessage(type = "create_room", role = "host"))
            }

            override fun onMessage(msg: SignalingMessage) = handleSignalingMessage(msg)

            override fun onClosed(code: Int, reason: String) {
                Log.i(TAG, "Signaling closed: $code $reason")
            }

            override fun onFailure(t: Throwable) {
                Log.e(TAG, "Signaling failure", t)
                listener?.onError("Mất kết nối tới máy chủ báo hiệu")
            }
        })
        signaling?.connect()
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        when (msg.type) {
            "room_created" -> {
                pairingCode = msg.code
                sessionId = msg.sessionId
                listener?.onPairingCode(msg.code ?: "", msg.expiresInSeconds ?: AppConfig.PAIRING_CODE_TTL_SECONDS)
            }
            "peer_joined" -> {
                controllerPresent = true
                listener?.onControllerJoined()
                setUpPeerConnection()
                // If the host already approved MediaProjection before the controller
                // joined, start capturing + offering now.
                if (pendingResultData != null) startCaptureAndOffer()
            }
            "answer" -> {
                val sdp = msg.sdp ?: return
                webRtc?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice_candidate" -> {
                val candidate = msg.candidate ?: return
                webRtc?.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex ?: 0, candidate))
            }
            "peer_left" -> {
                stopSharingAndSelf()
            }
            "error" -> {
                listener?.onError(msg.reason ?: "Lỗi không xác định từ máy chủ")
            }
        }
    }

    // ---------------- WebRTC + capture ----------------

    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null
    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var controllerPresent = false

    /** Called when the user taps "Bắt đầu chia sẻ" and approves the MediaProjection dialog. */
    private fun beginCapture(resultCode: Int, data: Intent) {
        pendingResultCode = resultCode
        pendingResultData = data

        // getRealMetrics() is deprecated since API 30 in favor of
        // WindowManager.currentWindowMetrics, but it remains functional through
        // current Android versions and keeps this sample working back to API 26.
        // On API 30+ you may switch to: wm.currentWindowMetrics.bounds.width()/height().
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels

        isSharing = true
        val sid = sessionId ?: UUID.randomUUID().toString().also { sessionId = it }
        SessionManager.start(sid, screenWidthPx, screenHeightPx)
        listener?.onSharingStarted()

        if (controllerPresent) startCaptureAndOffer()
        // else: capture starts as soon as the controller joins (see handleSignalingMessage).
    }

    /** Creates the PeerConnection + control DataChannel. No media flows yet, no offer sent yet. */
    private fun setUpPeerConnection() {
        if (webRtc != null) return
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
                Log.i(TAG, "ICE state: $state")
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    stopSharingAndSelf()
                }
            }

            override fun onRemoteVideoTrack(track: VideoTrack) { /* host does not render remote video */ }

            override fun onDataChannelMessage(text: String) {
                RemoteControlAccessibilityService.handleIncoming(text)
            }

            override fun onDataChannelReady() {
                Log.i(TAG, "Control channel open")
            }
        }).apply {
            initFactory()
            createPeerConnection()
            createHostDataChannel()
        }
    }

    /** Adds the screen-capture video track and sends the SDP offer. Requires an approved MediaProjection grant. */
    private fun startCaptureAndOffer() {
        val data = pendingResultData ?: return
        webRtc?.startScreenCapture(pendingResultCode, data, screenWidthPx, screenHeightPx)
        webRtc?.createOffer { desc ->
            signaling?.send(
                SignalingMessage(
                    type = "offer",
                    code = pairingCode,
                    sessionId = sessionId,
                    sdp = desc.description,
                    sdpType = "offer",
                    screenWidth = screenWidthPx,
                    screenHeight = screenHeightPx
                )
            )
        }
    }

    // ---------------- Teardown ----------------

    fun stopSharingAndSelf() {
        isSharing = false
        SessionManager.stop()
        webRtc?.close()
        webRtc = null
        signaling?.send(SignalingMessage(type = "cancel_room", code = pairingCode, sessionId = sessionId))
        signaling?.close()
        signaling = null
        listener?.onSharingStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isSharing) stopSharingAndSelf()
        eglBase.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        const val ACTION_CREATE_ROOM = "com.example.remoteassist.action.CREATE_ROOM"
        const val ACTION_START = "com.example.remoteassist.action.START"
        const val ACTION_STOP = "com.example.remoteassist.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        /**
         * Starts signaling/pairing only. Deliberately a plain startService() (not
         * startForegroundService()) since no MediaProjection consent exists yet and the
         * calling Activity is in the foreground, so no foreground-service promotion —
         * and therefore no 5-second startForeground() deadline — applies here.
         */
        fun beginPairing(context: android.content.Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CREATE_ROOM
            }
            context.startService(intent)
        }
    }
}
