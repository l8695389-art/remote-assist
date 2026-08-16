package com.example.remoteassist.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for "is there an active, consented remote session right now".
 *
 * The Accessibility service consults [isSessionActive] before executing any gesture.
 * Nothing in this app flips [start] except the explicit user action on the Host screen
 * (tapping "Bắt đầu chia sẻ" after granting the MediaProjection system dialog).
 *
 * This object is intentionally dumb: it holds no network logic, no permission-granting
 * logic, nothing that could be used to start a session without the two real Android
 * consent dialogs (MediaProjection capture consent + manually-enabled Accessibility)
 * having already happened.
 */
object SessionManager {

    private val active = AtomicBoolean(false)
    private val sessionId = AtomicReference<String?>(null)
    private val screenWidthPx = AtomicReference(0)
    private val screenHeightPx = AtomicReference(0)

    fun start(sessionId: String, widthPx: Int, heightPx: Int) {
        this.sessionId.set(sessionId)
        this.screenWidthPx.set(widthPx)
        this.screenHeightPx.set(heightPx)
        active.set(true)
    }

    fun stop() {
        active.set(false)
        sessionId.set(null)
    }

    fun isSessionActive(): Boolean = active.get()

    fun currentSessionId(): String? = sessionId.get()

    fun screenSize(): Pair<Int, Int> = screenWidthPx.get() to screenHeightPx.get()

    /** Validates that an inbound control message actually belongs to the live session. */
    fun isValidForCurrentSession(msgSessionId: String?): Boolean {
        if (!active.get()) return false
        val current = sessionId.get() ?: return false
        return msgSessionId != null && msgSessionId == current
    }
}
