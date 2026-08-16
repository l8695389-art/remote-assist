package com.example.remoteassist

import org.webrtc.PeerConnection

/**
 * Edit these two constants after you deploy the Cloudflare Worker (see server/README
 * / root README "Deploy Cloudflare Worker" section).
 */
object AppConfig {

    /** wss:// endpoint of the deployed Worker, e.g. wss://remote-assist-signal.<subdomain>.workers.dev/signal */
    const val SIGNALING_SERVER_URL = "wss://remote-assist-signal.example.workers.dev/signal"

    /**
     * ICE servers used for NAT traversal. STUN is free (Google's public servers).
     * TURN is required for the majority of real-world mobile-to-mobile connections
     * (carrier-grade NAT). Fill in your own TURN credentials — e.g. from Cloudflare
     * Calls TURN, Twilio, or a self-hosted coturn instance. Without TURN, sessions
     * across two different cellular networks will frequently fail to connect.
     */
    fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // Example TURN entry — replace host/username/credential with your own:
        // PeerConnection.IceServer.builder("turn:turn.example.com:3478")
        //     .setUsername("turn_user")
        //     .setPassword("turn_password")
        //     .createIceServer()
    )

    /** Pairing code TTL if it is never used, enforced server-side too. */
    const val PAIRING_CODE_TTL_SECONDS = 300L
}
