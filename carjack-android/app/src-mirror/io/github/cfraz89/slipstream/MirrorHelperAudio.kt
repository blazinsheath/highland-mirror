package io.github.cfraz89.slipstream

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/** PCM travels over authenticated loopback, surviving the handover to the vehicle Wi-Fi. */
class MirrorHelperAudio {
    private var socket: Socket? = null
    private var destination: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var owner = 0L

    @Synchronized fun start(token: Long, sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stop()
        owner = token
        if (!MirrorHelperProtocol.validAudio(sampleRate, channels)) return null
        var client: Socket? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            client = MirrorHelper.openSocket()
            val command = DataOutputStream(client.outputStream)
            command.writeInt(MirrorHelperProtocol.VERSION); command.writeInt(MirrorHelperProtocol.AUDIO)
            command.writeLong(token); command.writeInt(sampleRate); command.writeInt(channels); command.flush()
            val input = DataInputStream(client.inputStream)
            check(input.readInt() == MirrorHelperProtocol.OK) { "Shell playback capture unavailable" }
            client.soTimeout = 0
            pipe = ParcelFileDescriptor.createPipe()
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val connected = client
            socket = connected; destination = out
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val bytes = ByteArray(sampleRate / 100 * channels * 2)
                var chunks = 0L; var peak = 0
                try {
                    while (true) {
                        input.readFully(bytes)
                        for (i in bytes.indices step 2) {
                            val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                            peak = maxOf(peak, kotlin.math.abs(sample))
                        }
                        out.write(bytes)
                        if (++chunks % 500 == 0L) {
                            Log.i("carjack", "Bundled shell playback: $chunks PCM chunks; peak=$peak/32768")
                            peak = 0 // Only numeric signal levels enter diagnostics; no samples are saved.
                        }
                    }
                } catch (_: Exception) { Log.i("carjack", "Bundled playback pipe ended") }
                finally {
                    runCatching { connected.close() }; runCatching { out.close() }
                    synchronized(this) { if (socket === connected) { socket = null; destination = null } }
                }
            }, "mirror-shell-audio").start()
            Log.i("carjack", "Bundled REMOTE_SUBMIX capture ready: $sampleRate Hz, $channels channels")
            return pipe[0]
        } catch (e: Exception) {
            runCatching { client?.close() }; pipe?.forEach { runCatching { it.close() } }
            Log.e("carjack", "Bundled audio could not start", e)
            return null
        }
    }

    @Synchronized fun stop(token: Long = owner) {
        if (token != owner) return
        runCatching { socket?.close() }; socket = null
        runCatching { destination?.close() }; destination = null
    }
}
