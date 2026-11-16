package io.github.cfraz89.slipstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Own-phone ADB is used only to start the helper; media uses authenticated local Unix sockets. */
object MirrorHelper {
    private const val CHANNEL = "helper-pairing"
    private const val NOTICE = 42
    const val CODE = "pairing_code"
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var manager: MirrorAdbKeys? = null // Access only from work.
    private var pairing: AdbMdns? = null // Access only from main.
    @Volatile private var pairingPort = 0
    @Volatile private var name = ""
    @Volatile private var control: LocalSocket? = null
    @Volatile var ready = false
        private set
    val audio = MirrorHelperAudio()

    private fun state(context: Context, text: String) {
        Variant.prefs(context).edit().putString("helper_status", text).apply()
    }
    private fun adb(context: Context) = manager ?: MirrorAdbKeys(context.applicationContext).also { manager = it }

    fun ensure(context: Context, result: (Boolean) -> Unit) {
        val app = context.applicationContext
        work.execute {
            val ok = try { connectAndStart(app); true }
            catch (e: Exception) {
                Log.w("carjack", "Built-in helper setup: ${e.javaClass.simpleName}")
                state(app, "Helper unavailable. Enable Wireless debugging, then retry; pair once if needed.")
                false
            }
            main.post { result(ok) }
        }
    }

    private fun discover(context: Context): Int {
        val found = CountDownLatch(1)
        var port = 0
        val discovery = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { _, changed ->
            if (changed in 1..65535) { port = changed; found.countDown() }
        }
        main.post { discovery.start() }
        try { if (!found.await(10, TimeUnit.SECONDS)) throw IOException("Wireless debugging not found") }
        finally { main.post { discovery.stop() } }
        return port
    }

    private fun connectAndStart(context: Context) {
        if (ready && control != null) return
        state(context, "Starting the built-in helper…")
        val connection = adb(context)
        var connected = false
        val cached = Variant.prefs(context).getInt("helper_connect_port", 0)
        if (cached in 1..65535) try { connected = connection.connect("127.0.0.1", cached) || connection.isConnected }
        catch (_: Exception) { runCatching { connection.disconnect() } }
        try {
            if (!connected) {
                val port = discover(context)
                connected = connection.connect("127.0.0.1", port) || connection.isConnected
                if (connected) Variant.prefs(context).edit().putInt("helper_connect_port", port).apply()
            }
            check(connected) { "Wireless debugging needs pairing" }
            val socketName = "highland." + UUID.randomUUID().toString().replace("-", "")
            name = socketName
            val command = "trap '' HUP; CLASSPATH=" + MirrorHelperProtocol.quote(context.applicationInfo.sourceDir) +
                " app_process /system/bin io.github.cfraz89.slipstream.MirrorShellMain " +
                MirrorHelperProtocol.quote(socketName) + " " + Process.myUid() + " </dev/null >/dev/null 2>&1 &"
            connection.openStream("shell:$command").use { stream ->
                // Only a fixed launch command is sent; no user input, pairing code or audio is logged.
                stream.openInputStream().use { it.readBytes() }
            }
            var client: LocalSocket? = null
            for (attempt in 0 until 30) {
                try { client = openSocket(); break } catch (_: IOException) { Thread.sleep(100) }
            }
            val owner = client ?: throw IOException("Helper process did not start")
            try {
                val output = DataOutputStream(owner.outputStream)
                output.writeInt(MirrorHelperProtocol.VERSION); output.writeInt(MirrorHelperProtocol.HELLO); output.flush()
                val input = DataInputStream(owner.inputStream)
                check(input.readInt() == MirrorHelperProtocol.VERSION)
                val bt = NetworkIdentity.usableMac(input.readUTF()) ?: throw IOException("Phone Bluetooth identity unavailable")
                Variant.prefs(context).edit().putString("phone_bt", bt).putBoolean("helper_paired", true).apply()
                owner.soTimeout = 0
                control = owner; ready = true
                state(context, "Built-in helper ready · phone identity detected")
                Log.i("carjack", "Bundled shell helper connected; phone identity detected automatically")
                Thread({
                    try { while (input.read() != -1) { } } catch (_: IOException) { }
                    finally {
                        if (control === owner) {
                            ready = false; control = null; audio.stop()
                            state(context, "Helper stopped. Tap Start to reconnect.")
                            main.post { if (MirrorProjection.active) context.stopService(Intent(context, SlipstreamService::class.java)) }
                        }
                        runCatching { owner.close() }
                    }
                }, "mirror-helper-owner").start()
            } catch (e: Exception) { owner.close(); throw e }
        } finally { runCatching { connection.disconnect() } }
    }

    fun openSocket(): LocalSocket {
        val client = LocalSocket()
        try {
            client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            check(client.peerCredentials.uid == MirrorHelperProtocol.SHELL_UID) { "Unexpected helper owner" }
            client.soTimeout = 10000
            return client
        } catch (e: Exception) { client.close(); throw e }
    }

    fun stop() {
        ready = false
        val previous = control; control = null
        audio.stop(); runCatching { previous?.close() }
    }

    fun beginPairing(context: Context) {
        val app = context.applicationContext
        main.post {
            pairing?.stop(); pairingPort = 0
            state(app, "In Wireless debugging, choose Pair device with pairing code; reply to the Highland notification.")
            pairing = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
                pairingPort = port
                if (port in 1..65535) main.post { pairingNotification(app) }
            }.also { it.start() }
            pairingNotification(app)
            val active = pairing
            main.postDelayed({ if (pairing === active) { pairing?.stop(); pairing = null; pairingPort = 0 } }, 180000)
        }
    }

    private fun pairingNotification(context: Context) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Built-in helper setup", NotificationManager.IMPORTANCE_HIGH))
        val input = RemoteInput.Builder(CODE).setLabel("Six-digit pairing code").build()
        val reply = PendingIntent.getBroadcast(context, 42, Intent(context, MirrorPairingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_mirror),
            "Enter pairing code", reply).addRemoteInput(input).setAllowGeneratedReplies(false).build()
        val available = pairingPort in 1..65535
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(if (available) "Highland Mirror · enter pairing code" else "Highland Mirror · waiting for pairing")
            .setContentText(if (available) "Keep Android’s pairing dialog open and enter its six-digit code here." else "Open Wireless debugging → Pair device with pairing code.")
            .setContentIntent(Variant.openApp(context)).setOnlyAlertOnce(true).setOngoing(true)
        if (available) builder.addAction(action)
        notifications.notify(NOTICE, builder.build())
    }

    fun pair(context: Context, code: String, manualPort: Int? = null, done: () -> Unit = {}) {
        val app = context.applicationContext
        work.execute {
            try {
                require(MirrorHelperProtocol.pairingCode(code)) { "Enter six digits" }
                val port = manualPort ?: pairingPort.takeIf { it in 1..65535 } ?: throw IOException("Keep pairing dialog open")
                state(app, "Pairing the built-in helper…")
                check(adb(app).pair("127.0.0.1", port, code))
                Variant.prefs(app).edit().putBoolean("helper_paired", true).apply()
                main.post { pairing?.stop(); pairing = null; pairingPort = 0
                    app.getSystemService(NotificationManager::class.java).cancel(NOTICE) }
                connectAndStart(app)
            } catch (e: Exception) {
                Log.w("carjack", "Built-in pairing failed: ${e.javaClass.simpleName}")
                state(app, "Pairing did not finish. Keep Android’s pairing dialog open and retry with its current code.")
            } finally { done() }
        }
    }
}

/** Explicit, non-exported notification reply target; the pairing code is never persisted. */
class MirrorPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MirrorHelper.CODE)?.toString()?.trim() ?: return
        if (!MirrorHelperProtocol.pairingCode(code)) return
        val pending = goAsync()
        MirrorHelper.pair(context, code, done = { pending.finish() })
    }
}
