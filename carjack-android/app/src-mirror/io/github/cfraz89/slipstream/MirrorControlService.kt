package io.github.cfraz89.slipstream

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Explicitly enabled, optional touch replay. No window retrieval or screen-content inspection. */
class MirrorControlService : AccessibilityService() {
    companion object {
        @Volatile private var current: MirrorControlService? = null
        private val main = Handler(Looper.getMainLooper())
        fun available() = current != null
        fun cancelTouch() { main.post { current?.clearTouch() } }
        fun global(action: Int) {
            main.post { if (MirrorProjection.attached) current?.performGlobalAction(action) }
        }
        fun home() {
            main.post {
                if (!MirrorProjection.attached) return@post
                current?.let { service ->
                    service.startActivity(android.content.Intent(service,
                        io.github.cfraz89.slipstream.launcher.HomeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }
        fun touch(action: Int, pointer: Int, point: MirrorGeometry.Point?) {
            main.post {
                if (!MirrorProjection.attached) return@post
                current?.accept(action, pointer, point)
            }
        }
    }

    private var path: Path? = null
    private var pointer = -1
    private var started = 0L
    private var points = 0
    override fun onServiceConnected() { current = this }
    override fun onDestroy() { if (current === this) current = null; clearTouch(); super.onDestroy() }
    override fun onInterrupt() { clearTouch() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun clearTouch() { path = null; pointer = -1; points = 0 }

    private fun accept(action: Int, id: Int, point: MirrorGeometry.Point?) {
        when (action) {
            Touch.DOWN -> {
                if (pointer != -1 || point == null) return // First prototype supports one finger.
                pointer = id
                path = Path().apply { moveTo(point.x, point.y) }
                started = SystemClock.uptimeMillis()
                points = 1
            }
            Touch.MOVE -> {
                if (pointer != id) return
                if (point == null) { clearTouch(); return }
                if (++points <= 1000) path?.lineTo(point.x, point.y)
            }
            Touch.UP -> {
                if (pointer != id) return
                val gesturePath = path
                val duration = (SystemClock.uptimeMillis() - started).coerceIn(1L, 2000L)
                clearTouch()
                if (point == null || gesturePath == null) return
                gesturePath.lineTo(point.x, point.y)
                dispatchGesture(GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(gesturePath, 0, duration)).build(), null, null)
            }
        }
    }
}
