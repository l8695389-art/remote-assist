package com.example.remoteassist.signaling

import org.json.JSONObject

/**
 * Wire format for every message exchanged with the Cloudflare Worker signaling server.
 *
 * type: create_room | room_created | join | joined | peer_joined | offer | answer |
 *       ice_candidate | peer_left | error | cancel_room
 *
 * seq is a per-sender monotonically increasing counter used by the server (and the
 * client) to drop obviously-replayed or out-of-order messages.
 */
data class SignalingMessage(
    val type: String,
    val code: String? = null,
    val sessionId: String? = null,
    val role: String? = null,          // "host" | "controller"
    val seq: Long = 0L,
    val sdp: String? = null,
    val sdpType: String? = null,       // "offer" | "answer"
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val reason: String? = null,
    val expiresInSeconds: Long? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("type", type)
        code?.let { o.put("code", it) }
        sessionId?.let { o.put("sessionId", it) }
        role?.let { o.put("role", it) }
        o.put("seq", seq)
        sdp?.let { o.put("sdp", it) }
        sdpType?.let { o.put("sdpType", it) }
        candidate?.let { o.put("candidate", it) }
        sdpMid?.let { o.put("sdpMid", it) }
        sdpMLineIndex?.let { o.put("sdpMLineIndex", it) }
        screenWidth?.let { o.put("screenWidth", it) }
        screenHeight?.let { o.put("screenHeight", it) }
        reason?.let { o.put("reason", it) }
        expiresInSeconds?.let { o.put("expiresInSeconds", it) }
        return o.toString()
    }

    companion object {
        fun fromJson(raw: String): SignalingMessage {
            val o = JSONObject(raw)
            return SignalingMessage(
                type = o.optString("type"),
                code = o.optString("code", null),
                sessionId = o.optString("sessionId", null),
                role = o.optString("role", null),
                seq = o.optLong("seq", 0L),
                sdp = o.optString("sdp", null),
                sdpType = o.optString("sdpType", null),
                candidate = o.optString("candidate", null),
                sdpMid = o.optString("sdpMid", null),
                sdpMLineIndex = if (o.has("sdpMLineIndex")) o.optInt("sdpMLineIndex") else null,
                screenWidth = if (o.has("screenWidth")) o.optInt("screenWidth") else null,
                screenHeight = if (o.has("screenHeight")) o.optInt("screenHeight") else null,
                reason = o.optString("reason", null),
                expiresInSeconds = if (o.has("expiresInSeconds")) o.optLong("expiresInSeconds") else null
            )
        }
    }
}

/** Gesture command sent over the WebRTC DataChannel, controller -> host. Coordinates are normalized 0..1. */
data class GestureCommand(
    val t: String,          // "tap" | "long_press" | "swipe"
    val sessionId: String,
    val x: Float,
    val y: Float,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long = 100
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("t", t)
        o.put("sessionId", sessionId)
        o.put("x", x)
        o.put("y", y)
        x2?.let { o.put("x2", it) }
        y2?.let { o.put("y2", it) }
        o.put("durationMs", durationMs)
        return o.toString()
    }

    companion object {
        fun fromJson(raw: String): GestureCommand {
            val o = JSONObject(raw)
            return GestureCommand(
                t = o.optString("t"),
                sessionId = o.optString("sessionId"),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                x2 = if (o.has("x2")) o.optDouble("x2").toFloat() else null,
                y2 = if (o.has("y2")) o.optDouble("y2").toFloat() else null,
                durationMs = o.optLong("durationMs", 100)
            )
        }
    }
}
