package com.example.remoteassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.remoteassist.session.SessionManager
import com.example.remoteassist.signaling.GestureCommand

/**
 * Replays touch gestures received from the controller over the WebRTC DataChannel.
 *
 * Hard constraints enforced here, not just documented:
 *  - The user must have manually enabled this service in system Settings; nothing in
 *    this codebase requests or auto-grants BIND_ACCESSIBILITY_SERVICE.
 *  - Every incoming command is checked against [SessionManager]. If there is no active,
 *    explicitly-started sharing session with a matching sessionId, the command is
 *    dropped silently — no gesture is dispatched.
 *  - This service does not read screen content (canRetrieveWindowContent="false" in
 *    accessibility_service_config.xml) and does not log coordinates persistently.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteControlA11y"

        @Volatile
        private var instance: RemoteControlAccessibilityService? = null

        fun isBound(): Boolean = instance != null

        /** Entry point called by ScreenCaptureService when a gesture command arrives. */
        fun handleIncoming(raw: String) {
            val svc = instance ?: run {
                Log.w(TAG, "Gesture dropped: accessibility service not enabled by the user")
                return
            }
            svc.handle(raw)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    private fun handle(raw: String) {
        val cmd = try {
            GestureCommand.fromJson(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed gesture payload dropped", e)
            return
        }

        if (!SessionManager.isValidForCurrentSession(cmd.sessionId)) {
            Log.w(TAG, "Gesture dropped: no active/matching session")
            return
        }

        val (widthPx, heightPx) = SessionManager.screenSize()
        if (widthPx <= 0 || heightPx <= 0) return

        val path = Path()
        val x1 = (cmd.x.coerceIn(0f, 1f)) * widthPx
        val y1 = (cmd.y.coerceIn(0f, 1f)) * heightPx

        when (cmd.t) {
            "tap" -> {
                path.moveTo(x1, y1)
                dispatch(path, 0, 60)
            }
            "long_press" -> {
                path.moveTo(x1, y1)
                dispatch(path, 0, cmd.durationMs.coerceAtLeast(400))
            }
            "swipe" -> {
                val x2 = (cmd.x2 ?: cmd.x).coerceIn(0f, 1f) * widthPx
                val y2 = (cmd.y2 ?: cmd.y).coerceIn(0f, 1f) * heightPx
                path.moveTo(x1, y1)
                path.lineTo(x2, y2)
                dispatch(path, 0, cmd.durationMs.coerceIn(50, 5000))
            }
            else -> Log.w(TAG, "Unknown gesture type: ${cmd.t}")
        }
    }

    private fun dispatch(path: Path, startTime: Long, duration: Long) {
        val stroke = GestureDescription.StrokeDescription(path, startTime, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
