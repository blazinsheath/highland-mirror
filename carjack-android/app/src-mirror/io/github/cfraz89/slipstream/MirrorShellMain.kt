package io.github.cfraz89.slipstream

import android.os.Process
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Bundled in this APK; runs only after the owner authorizes Android Wireless debugging. */
object MirrorShellMain {
    @JvmStatic fun main(args: Array<String>) {
        if (Process.myUid() != MirrorHelperProtocol.SHELL_UID || args.size != 2) exitProcess(1)
        val port = MirrorHelperProtocol.port(args[0]) ?: exitProcess(1)
        val key = try { MirrorHelperLink.key(args[1]) } catch (_: IllegalArgumentException) { exitProcess(1) }
        try { Os.setsid() } catch (_: Exception) { }
        val playback = PlaybackCapture(null) // The same REMOTE_SUBMIX implementation as Highland .6.
        val owner = AtomicBoolean(false)
        Thread({ Thread.sleep(30000); if (!owner.get()) exitProcess(0) }, "helper-start-timeout").apply { isDaemon = true; start() }
        try {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                while (true) {
                    val client = server.accept()
                    Thread({
                        try {
                            client.use {
                                client.soTimeout = 10000
                                client.tcpNoDelay = true
                                MirrorHelperLink.server(client, key)
                                val input = DataInputStream(client.inputStream)
                                val output = DataOutputStream(client.outputStream)
                                check(input.readInt() == MirrorHelperProtocol.VERSION)
                                when (input.readInt()) {
                                    MirrorHelperProtocol.HELLO -> {
                                        check(owner.compareAndSet(false, true))
                                        try {
                                            val process = ProcessBuilder("settings", "get", "secure", "bluetooth_address").start()
                                            val address = process.inputStream.bufferedReader().use { it.readLine().orEmpty() }.trim()
                                            process.waitFor()
                                            output.writeInt(MirrorHelperProtocol.VERSION)
                                            output.writeUTF(NetworkIdentity.usableMac(address).orEmpty())
                                            output.flush()
                                            client.soTimeout = 0
                                            while (input.read() != -1) { }
                                        }
                                        finally { playback.stop(); exitProcess(0) }
                                    }
                                    MirrorHelperProtocol.AUDIO -> {
                                        check(owner.get())
                                        val token = input.readLong()
                                        val rate = input.readInt()
                                        val channels = input.readInt()
                                        check(MirrorHelperProtocol.validAudio(rate, channels))
                                        val pipe = playback.start(token, rate, channels)
                                        output.writeInt(if (pipe != null) MirrorHelperProtocol.OK else 1)
                                        output.flush()
                                        if (pipe == null) return@Thread
                                        client.soTimeout = 0
                                        // EOF on the command direction stops capture even during silence.
                                        val watch = Thread({
                                            try { input.read() } catch (_: Exception) { }
                                            finally { playback.stop(token) }
                                        }, "helper-audio-owner").apply { isDaemon = true; start() }
                                        try { ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.copyTo(output, 16384) } }
                                        finally { playback.stop(token); client.close(); watch.interrupt() }
                                    }
                                    else -> error("Unknown helper command")
                                }
                            }
                        } catch (e: Exception) { Log.i("carjack", "Bundled helper client ended: ${e.javaClass.simpleName}") }
                    }, "mirror-helper-client").start()
                }
            }
        } catch (e: Exception) { Log.e("carjack", "Bundled helper ended", e); playback.stop(); exitProcess(1) }
    }
}
