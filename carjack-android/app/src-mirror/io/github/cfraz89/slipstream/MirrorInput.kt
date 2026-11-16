package io.github.cfraz89.slipstream

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque

/** Automatic input through the already-authorized helper; never blocks the media/native thread. */
object MirrorInput {
    private val lock = Any()
    @Volatile private var current: Connection? = null
    val available get() = current?.ready == true

    fun start(context: Context) {
        synchronized(lock) {
            if (current != null) return
            val next = Connection(context.applicationContext)
            current = next
            Variant.prefs(context).edit().putString("input_error", "").putBoolean("input_ready", false).apply()
            next.worker.start()
        }
    }
    fun stop() {
        val previous = synchronized(lock) { val old = current; current = null; old }
        previous?.close()
    }
    fun cancel() { current?.send(MirrorInputProtocol.Event(MirrorInputProtocol.CANCEL), clear = true) }
    fun touch(action: Int, pointer: Int, point: MirrorGeometry.Point?): Boolean {
        if (point == null) { cancel(); return available }
        return send(MirrorInputProtocol.Event(MirrorInputProtocol.TOUCH, action, pointer, point.x, point.y))
    }
    fun key(code: Int, down: Boolean) = send(MirrorInputProtocol.Event(MirrorInputProtocol.KEY, code = code, down = down))
    fun home() = send(MirrorInputProtocol.Event(MirrorInputProtocol.HOME))
    private fun send(event: MirrorInputProtocol.Event): Boolean {
        if (!MirrorProjection.attached || !MirrorHelper.ready || !MirrorInputProtocol.valid(event)) return false
        return current?.send(event) == true
    }

    private class Connection(private val context: Context) {
        @Volatile var ready = false
        @Volatile private var running = true
        @Volatile private var socket: Socket? = null
        private val events = LinkedBlockingDeque<MirrorInputProtocol.Event>(128)
        val worker = Thread(::run, "mirror-vehicle-input")
        fun send(event: MirrorInputProtocol.Event, clear: Boolean = false): Boolean {
            if (!ready || !running) return false
            synchronized(events) {
                if (clear) events.clear()
                if (event.type == MirrorInputProtocol.TOUCH && event.action == Touch.MOVE)
                    events.removeAll { it.type == MirrorInputProtocol.TOUCH && it.action == Touch.MOVE && it.pointer == event.pointer }
                if (!events.offerLast(event)) {
                    events.clear()
                    events.offerLast(MirrorInputProtocol.Event(MirrorInputProtocol.CANCEL))
                    events.offerLast(event)
                }
            }
            return true
        }
        private fun run() {
            var client: Socket? = null
            try {
                val connected = MirrorHelper.openSocket()
                client = connected
                synchronized(this) {
                    if (!running) { connected.close(); return }
                    socket = connected
                }
                val output = DataOutputStream(connected.outputStream)
                val input = DataInputStream(connected.inputStream)
                output.writeInt(MirrorHelperProtocol.VERSION); output.writeInt(MirrorHelperProtocol.INPUT); output.flush()
                if (input.readInt() != MirrorHelperProtocol.OK) throw IOException("Automatic input unavailable")
                ready = true
                MirrorControlService.cancelTouch()
                Variant.prefs(context).edit().putBoolean("input_ready", true).putString("input_status", "Vehicle touch: automatic").apply()
                Log.i("carjack", "Vehicle input connected through built-in helper; no Accessibility setup")
                while (running) {
                    val event = events.takeFirst()
                    if (!running) break
                    MirrorInputProtocol.write(output, event)
                    if (input.readInt() != MirrorHelperProtocol.OK) throw IOException("Vehicle input was not accepted")
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w("carjack", "Automatic input unavailable: ${e.javaClass.simpleName}")
                    Variant.prefs(context).edit().putString("input_status", "Automatic touch unavailable · see Settings & help")
                        .putString("input_error", e.javaClass.simpleName).apply()
                }
            } finally {
                ready = false
                runCatching { client?.close() }
                synchronized(lock) {
                    if (current === this) {
                        current = null
                        Variant.prefs(context).edit().putBoolean("input_ready", false).apply()
                    }
                }
            }
        }
        fun close() {
            running = false; ready = false
            synchronized(this) { runCatching { socket?.close() }; socket = null }
            events.clear(); worker.interrupt()
            synchronized(lock) {
                if (current == null || current === this) Variant.prefs(context).edit().putBoolean("input_ready", false).apply()
            }
        }
    }
}
