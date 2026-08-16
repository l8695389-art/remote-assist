package com.example.remoteassist.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.charset.StandardCharsets

/**
 * Wraps a single WebRTC PeerConnection used for exactly one remote-assist session.
 *
 * Host role: captures the screen via MediaProjection (ScreenCapturerAndroid), publishes
 * a video track, creates the offer, opens a DataChannel to receive gesture commands.
 *
 * Controller role: renders the incoming video track, creates the answer, listens on the
 * DataChannel opened by the host, and sends gesture commands over it.
 */
class WebRtcClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val iceServers: List<IceServer>,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnectionChange(state: PeerConnection.IceConnectionState)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onDataChannelMessage(text: String)
        fun onDataChannelReady()
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun initFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                callbacks.onLocalIceCandidate(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                callbacks.onIceConnectionChange(state)
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    callbacks.onRemoteVideoTrack(track)
                }
            }

            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                registerDataChannelObserver(channel)
            }

            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })
    }

    // ---------- Host side: screen capture + outgoing track + data channel owner ----------

    /**
     * Must be called with the exact (resultCode, data) Intent pair returned from the
     * MediaProjection consent dialog (Activity.RESULT_OK + the granted Intent). This is
     * the only way capture starts; there is no path that begins capture without it.
     */
    fun startScreenCapture(resultCode: Int, data: Intent, widthPx: Int, heightPx: Int, fps: Int = 20) {
        val capturer = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by the system or the user")
            }
        })
        videoCapturer = capturer

        val source = factory!!.createVideoSource(capturer.isScreencast)
        localVideoSource = source

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
        capturer.startCapture(widthPx, heightPx, fps)

        val videoTrack = factory!!.createVideoTrack("screen_share_track", source)
        peerConnection?.addTrack(videoTrack, listOf("screen_share_stream"))
    }

    fun createHostDataChannel() {
        val init = DataChannel.Init().apply {
            ordered = true
        }
        val channel = peerConnection!!.createDataChannel("control", init)
        dataChannel = channel
        registerDataChannelObserver(channel)
    }

    fun stopScreenCapture() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping capturer", e)
        }
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoSource?.dispose()
        localVideoSource = null
    }

    // ---------- Controller side: rendering + sending gesture commands ----------

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(false)
    }

    fun sendGesture(json: String) {
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8)), false
        )
        dataChannel?.send(buffer)
    }

    private fun registerDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    callbacks.onDataChannelReady()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                callbacks.onDataChannelMessage(String(bytes, StandardCharsets.UTF_8))
            }
        })
    }

    // ---------- SDP negotiation ----------

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection!!.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection!!.setLocalDescription(SdpObserverAdapter(), desc)
                onSuccess(desc)
            }
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection!!.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection!!.setLocalDescription(SdpObserverAdapter(), desc)
                onSuccess(desc)
            }
        }, constraints)
    }

    fun setRemoteDescription(desc: SessionDescription) {
        peerConnection!!.setRemoteDescription(SdpObserverAdapter(), desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        stopScreenCapture()
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        factory?.dispose()
        factory = null
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) { Log.e(TAG, "SDP create failure: $error") }
        override fun onSetFailure(error: String) { Log.e(TAG, "SDP set failure: $error") }
    }

    companion object {
        private const val TAG = "WebRtcClient"
    }
}
