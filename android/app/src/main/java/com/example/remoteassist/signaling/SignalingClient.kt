package com.example.remoteassist.signaling

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin WebSocket wrapper around the Cloudflare Worker signaling endpoint.
 *
 * Server URL shape: wss://<your-worker-host>/signal
 * First message sent must be a "create_room" (host) or "join" (controller).
 * See server/src/index.ts + server/src/room.ts for the protocol implementation.
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onMessage(msg: SignalingMessage)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val seqCounter = AtomicLong(0)

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    listener.onMessage(SignalingMessage.fromJson(text))
                } catch (e: Exception) {
                    Log.e(TAG, "Malformed signaling message dropped", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }
        })
    }

    fun send(message: SignalingMessage) {
        val withSeq = message.copy(seq = seqCounter.incrementAndGet())
        webSocket?.send(withSeq.toJson())
    }

    fun close() {
        webSocket?.close(1000, "client_closed")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
