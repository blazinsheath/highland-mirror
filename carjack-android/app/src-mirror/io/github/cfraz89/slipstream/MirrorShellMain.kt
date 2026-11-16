package io.github.cfraz89.slipstream

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore
import kotlin.system.exitProcess

/** Bundled in this APK; runs only after the owner authorizes Android Wireless debugging. */
object MirrorShellMain {
    @JvmStatic fun main(args: Array<String>) {
        if (Process.myUid() != MirrorHelperProtocol.SHELL_UID || args.size != 2) exitProcess(1)
        val port = MirrorHelperProtocol.port(args[0]) ?: exitProcess(1)
        val key = try { MirrorHelperLink.key(args[1]) } catch (_: IllegalArgumentException) { exitProcess(1) }
        try { Os.setsid() } catch (_: Exception) { }
        // AudioRecord resolves its session through Context.getDeviceId() on Android 14+.
        // The shell capture wrapper still supplies shell attribution; its base must support
        // the default device even though this tool process has no Activity/Application context.
        val captureContext = object : ContextWrapper(null) {
            override fun getDeviceId() = Context.DEVICE_ID_DEFAULT
        }
        val playback = PlaybackCapture(captureContext) // Same capture implementation as Highland .6.
        val owner = AtomicBoolean(false)
        val inputOwner = AtomicReference<MirrorShellInput?>(null)
        val clients = Semaphore(8)
        val bluetoothLock = Any()
        Thread({ Thread.sleep(30000); if (!owner.get()) exitProcess(0) }, "helper-start-timeout").apply { isDaemon = true; start() }
        try {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                // The app reads just this marker, then authenticates the independent loopback link.
                System.out.println(MirrorShellLaunch.READY)
                System.out.flush()
                // Stop retaining the launch pipe once readiness is reported. Future native diagnostics
                // go to logcat; writing to a closed ADB pipe must never terminate audio capture.
                runCatching {
                    val sink = Os.open("/dev/null", OsConstants.O_RDWR, 0)
                    try { Os.dup2(sink, 1); Os.dup2(sink, 2) } finally { Os.close(sink) }
                }
                while (true) {
                    val client = server.accept()
                    if (!clients.tryAcquire()) { client.close(); continue }
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
                                        finally {
                                            owner.set(false)
                                            inputOwner.getAndSet(null)?.close()
                                            // Let a bounded reset restore Bluetooth before exiting on Stop.
                                            synchronized(bluetoothLock) { playback.stop(); exitProcess(0) }
                                        }
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
                                    MirrorHelperProtocol.INPUT -> {
                                        check(owner.get())
                                        val control = try { MirrorShellInput() } catch (_: Exception) {
                                            output.writeInt(1); output.flush(); return@Thread
                                        }
                                        inputOwner.getAndSet(control)?.close()
                                        try {
                                            output.writeInt(MirrorHelperProtocol.OK); output.flush()
                                            client.soTimeout = 0
                                            while (true) {
                                                val accepted = control.handle(MirrorInputProtocol.read(input))
                                                output.writeInt(if (accepted) MirrorHelperProtocol.OK else 1); output.flush()
                                                if (!accepted) break
                                            }
                                        } finally { control.close(); inputOwner.compareAndSet(control, null) }
                                    }
                                    MirrorHelperProtocol.RESET_BLUETOOTH -> {
                                        check(owner.get())
                                        val ok = synchronized(bluetoothLock) { owner.get() && MirrorBluetoothReset.execute() }
                                        output.writeInt(if (ok) MirrorHelperProtocol.OK else 1); output.flush()
                                    }
                                    else -> error("Unknown helper command")
                                }
                            }
                        } catch (e: Exception) { Log.i("carjack", "Bundled helper client ended: ${e.javaClass.simpleName}") }
                        finally { clients.release() }
                    }, "mirror-helper-client").start()
                }
            }
        } catch (e: Exception) {
            // This remains on the launch pipe if binding failed before READY; no arguments or secrets.
            System.err.println("Highland helper startup failure: " +
                generateSequence<Throwable>(e) { it.cause }.take(6).joinToString(" > ") { it.javaClass.name })
            System.err.flush()
            Log.e("carjack", "Bundled helper ended", e); inputOwner.getAndSet(null)?.close(); playback.stop(); exitProcess(1)
        }
    }
}
