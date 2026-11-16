package io.github.cfraz89.slipstream

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Fixed-size input messages. No shell commands, text fields or arbitrary component names. */
object MirrorInputProtocol {
    const val TOUCH = 1
    const val KEY = 2
    const val HOME = 3
    const val CANCEL = 4
    const val ACTION_HOME = "io.github.blazinsheath.highlandmirror.OPEN_HOME"
    data class Event(val type: Int, val action: Int = 0, val pointer: Int = 0,
                     val x: Float = 0f, val y: Float = 0f, val code: Int = 0, val down: Boolean = false)
    fun valid(event: Event): Boolean = when (event.type) {
        TOUCH -> event.action in 0..2 && event.pointer in 0..31 && event.x.isFinite() && event.y.isFinite() &&
            event.x >= 0 && event.y >= 0 && event.x < 32768 && event.y < 32768
        KEY -> event.code in 1..511
        HOME, CANCEL -> true
        else -> false
    }
    fun write(output: DataOutputStream, event: Event) {
        require(valid(event))
        output.writeInt(event.type)
        when (event.type) {
            TOUCH -> { output.writeInt(event.action); output.writeInt(event.pointer); output.writeFloat(event.x); output.writeFloat(event.y) }
            KEY -> { output.writeInt(event.code); output.writeBoolean(event.down) }
        }
        output.flush()
    }
    fun read(input: DataInputStream): Event {
        val event = when (val type = input.readInt()) {
            TOUCH -> Event(type, input.readInt(), input.readInt(), input.readFloat(), input.readFloat())
            KEY -> Event(type, code = input.readInt(), down = input.readBoolean())
            HOME, CANCEL -> Event(type)
            else -> throw IOException("Unknown input message")
        }
        if (!valid(event)) throw IOException("Invalid input message")
        return event
    }
}
