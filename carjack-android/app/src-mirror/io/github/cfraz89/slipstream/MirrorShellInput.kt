package io.github.cfraz89.slipstream

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.TimeUnit

/** Uses the same shell input APIs as the working Slipstream display service, on phone display 0. */
class MirrorShellInput : AutoCloseable {
    private val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
    private val manager = managerClass.getMethod("getInstance").invoke(null)
    private val injectMethod = managerClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val displayMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    private val fingers = Fingers()
    private val keys = mutableMapOf<Int, Long>()
    private var open = true

    private fun inject(event: InputEvent): Boolean {
        displayMethod.invoke(event, 0 /* DEFAULT_DISPLAY: the phone being mirrored */)
        return injectMethod.invoke(manager, event, 0 /* ASYNC */) == true
    }
    @Synchronized fun handle(event: MirrorInputProtocol.Event): Boolean {
        if (!open) return false
        return when (event.type) {
            MirrorInputProtocol.TOUCH -> {
                val motion = fingers.event(event.action, event.pointer, event.x, event.y) ?: return true
                try { inject(motion) } finally { motion.recycle() }
            }
            MirrorInputProtocol.KEY -> {
                val now = SystemClock.uptimeMillis()
                val started = if (event.down) keys.getOrPut(event.code) { now } else keys[event.code] ?: now
                val accepted = inject(KeyEvent(started, now, if (event.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    event.code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
                if (!event.down && accepted) keys.remove(event.code)
                accepted
            }
            MirrorInputProtocol.HOME -> {
                cancel()
                // StartActivity is exported; it opens our private launcher only while mirroring.
                val process = ProcessBuilder("am", "start", "--user", "current", "-n",
                    "io.github.blazinsheath.highlandmirror/io.github.cfraz89.slipstream.StartActivity",
                    "-a", MirrorInputProtocol.ACTION_HOME, "--activity-reorder-to-front", "--activity-single-top")
                    .redirectOutput(java.io.File("/dev/null")).redirectError(java.io.File("/dev/null")).start()
                if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); false } else process.exitValue() == 0
            }
            MirrorInputProtocol.CANCEL -> { cancel(); true }
            else -> false
        }
    }
    private fun cancel() {
        fingers.cancel()?.let { event -> try { inject(event) } finally { event.recycle() } }
        val now = SystemClock.uptimeMillis()
        for ((code, started) in keys) runCatching {
            inject(KeyEvent(started, now, KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
        keys.clear()
    }
    @Synchronized override fun close() { if (open) { open = false; runCatching { cancel() } } }
}
