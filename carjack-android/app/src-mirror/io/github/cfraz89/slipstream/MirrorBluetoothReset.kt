package io.github.cfraz89.slipstream

import java.io.File
import java.util.concurrent.TimeUnit

/** Fixed shell operation, matching Slipstream's recovery; never accepts shell text from a client. */
object MirrorBluetoothReset {
    fun reset(run: (String) -> Boolean): Boolean {
        var off = false
        var on = false
        try {
            off = run("disable") && run("wait-for-state:STATE_OFF")
        } finally {
            // Even a timeout while turning off must attempt to restore the phone's Bluetooth.
            on = run("enable") && run("wait-for-state:STATE_ON")
        }
        return off && on
    }

    fun execute(): Boolean = reset { operation ->
        try {
            val process = ProcessBuilder("cmd", "bluetooth_manager", operation)
                .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
            try { process.waitFor(6, TimeUnit.SECONDS) && process.exitValue() == 0 }
            finally { if (process.isAlive) process.destroyForcibly() }
        } catch (_: Exception) { false }
    }
}
