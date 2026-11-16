package io.github.cfraz89.slipstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.AdbStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Own-phone ADB starts the helper; mutually authenticated loopback carries media and lifetime. */
object MirrorHelper {
    private const val CHANNEL = "helper-pairing"
    private const val NOTICE = 42
    const val CODE = "pairing_code"
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val launches = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mirror-helper-launch").apply { isDaemon = true }
    }
    private var manager: MirrorAdbKeys? = null // Access only from work.
    private var pairing: AdbMdns? = null // Access only from main.
    @Volatile private var pairingPort = 0
    @Volatile private var localPort = 0
    @Volatile private var secret = ByteArray(0)
    @Volatile private var control: Socket? = null
    private var phase = MirrorHelperRecovery.Phase.IDENTITY
    @Volatile var ready = false
        private set
    val audio = MirrorHelperAudio()
    private val resettingBluetooth = java.util.concurrent.atomic.AtomicBoolean(false)
    val bluetoothResetting get() = resettingBluetooth.get()

    /** Nonblocking for the service/UI. At most one recovery is in flight. */
    fun cycleBluetooth(context: Context): Boolean {
        if (!ready) return false
        if (!resettingBluetooth.compareAndSet(false, true)) return true
        val app = context.applicationContext
        val beforeRestore = Variant.prefs(app).getLong("bluetooth_restore_revision", 0)
        Variant.prefs(app).edit().putBoolean("bluetooth_recovering", true)
            .putString("status", "Refreshing Bluetooth for the vehicle…").apply()
        work.execute {
            var detail = "helper connection unavailable"
            val ok = try {
                openSocket().use { client ->
                    client.soTimeout = 28000
                    val output = DataOutputStream(client.outputStream)
                    output.writeInt(MirrorHelperProtocol.VERSION)
                    output.writeInt(MirrorHelperProtocol.RESET_BLUETOOTH); output.flush()
                    val input = DataInputStream(client.inputStream)
                    val result = input.readInt() == MirrorHelperProtocol.OK
                    detail = input.readUTF().take(1000)
                    result
                }
            } catch (e: Exception) { detail = e.javaClass.simpleName; false }
            finally { resettingBluetooth.set(false) }
            val prefs = Variant.prefs(app)
            val restored = prefs.getLong("bluetooth_restore_revision", 0) > beforeRestore
            val report = "commands=$ok; adapter restored=$restored; $detail"
            Log.i("carjack", "Vehicle Bluetooth recovery completed: $report")
            // Adapter broadcasts drive the service's fresh connection attempt. A CLI timeout
            // must never overwrite a newer connected/manual-reset status with a toggle prompt.
            prefs.edit().putBoolean("bluetooth_recovering", false)
                .putString("bluetooth_reset_report", report).apply()
        }
        return true
    }

    private fun state(context: Context, text: String) {
        Variant.prefs(context).edit().putString("helper_status", text).apply()
    }
    private fun stage(context: Context, value: MirrorHelperRecovery.Phase, text: String) {
        phase = value
        Variant.prefs(context).edit().putString("helper_phase", value.name).putString("helper_error_type", "")
            .putString("helper_recovery", "").apply()
        state(context, text)
        Log.i("carjack", "Built-in helper step: ${value.name}")
    }
    private fun failure(context: Context, error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val rejected = causes.any { it.javaClass.simpleName in listOf("AdbAuthenticationFailedException", "AdbPairingRequiredException") }
        val paired = Variant.prefs(context).getBoolean("helper_paired", false)
        val advice = MirrorHelperRecovery.message(paired, phase, rejected)
        Variant.prefs(context).edit().putString("helper_error_type", causes.joinToString(" > ") { it.javaClass.simpleName })
            .putString("helper_recovery", MirrorHelperRecovery.action(paired, phase, rejected).name)
            .putString("helper_status", advice).apply()
        // Persist startup failures even before a CarPlay service exists. Never record PINs or launch secrets.
        val report = buildString {
            append("${java.util.Date()} phase=${phase.name} paired=$paired\n")
            for (cause in causes) {
                append(cause.javaClass.name).append('\n')
                cause.stackTrace.take(20).forEach { append("  at ").append(it).append('\n') }
            }
        }
        synchronized(this) {
            runCatching {
                val file = File(context.filesDir, "helper.log")
                if (file.length() > 262144) file.writeText("", Charsets.UTF_8)
                file.appendText(report, Charsets.UTF_8)
            }
        }
        Log.w("carjack", "Built-in helper failed at ${phase.name}: ${causes.joinToString(" > ") { it.javaClass.simpleName }}")
    }
    private fun adb(context: Context) = manager ?: MirrorAdbKeys(context.applicationContext).also { manager = it }

    fun ensure(context: Context, result: (Boolean) -> Unit) {
        val app = context.applicationContext
        work.execute {
            val ok = try { connectAndStart(app); true }
            catch (e: Exception) {
                failure(app, e)
                false
            }
            main.post { result(ok) }
        }
    }

    private fun discover(context: Context): Int {
        stage(context, MirrorHelperRecovery.Phase.DISCOVERY, "Finding the saved debugging connection…")
        val found = CountDownLatch(1)
        var port = 0
        val discovery = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { _, changed ->
            if (changed in 1..65535) { port = changed; found.countDown() }
        }
        main.post { runCatching { discovery.start() } }
        try { if (!found.await(10, TimeUnit.SECONDS)) throw IOException("Wireless debugging not found") }
        finally { main.post { runCatching { discovery.stop() } } }
        return port
    }

    private fun systemPort(): Int? = try {
        // Some builds expose their current TLS port to apps; absence simply falls back to NSD.
        val process = ProcessBuilder("/system/bin/getprop", "service.adb.tls.port").start()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) { process.destroy(); null }
        else process.inputStream.bufferedReader().use { MirrorHelperProtocol.port(it.readText().trim()) }
    } catch (_: Exception) { null }

    private fun connectAndStart(context: Context) {
        if (ready && control != null) return
        stage(context, MirrorHelperRecovery.Phase.IDENTITY, "Loading the saved helper identity…")
        val connection = adb(context)
        var connected = false
        var launchStream: AdbStream? = null
        val prefs = Variant.prefs(context)
        try {
            val ports = listOf(prefs.getInt("helper_connect_override", 0), systemPort() ?: 0,
                prefs.getInt("helper_connect_port", 0)).filter { it in 1..65535 }.distinct()
            for (port in ports) {
                stage(context, MirrorHelperRecovery.Phase.CONNECT, "Connecting with the saved pairing…")
                try {
                    connection.disconnect()
                    connected = connection.connect("127.0.0.1", port)
                    if (connected) { prefs.edit().putInt("helper_connect_port", port).apply(); break }
                } catch (_: Exception) { runCatching { connection.disconnect() } }
            }
            if (!connected) {
                val port = discover(context)
                stage(context, MirrorHelperRecovery.Phase.CONNECT, "Connecting with the saved pairing…")
                connection.disconnect()
                connected = connection.connect("127.0.0.1", port)
                if (connected) prefs.edit().putInt("helper_connect_port", port).apply()
            }
            check(connected) { "Wireless debugging needs pairing" }
            // Reserve an OS-selected loopback port, then transfer ownership to the shell helper.
            // Mutual authentication rejects an unrelated listener if the port is taken in between.
            localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val launchSecret = MirrorHelperLink.newSecret()
            secret = MirrorHelperLink.key(launchSecret)
            stage(context, MirrorHelperRecovery.Phase.LAUNCH, "Pairing saved · starting the built-in helper…")
            val command = MirrorShellLaunch.command(context.applicationInfo.sourceDir, localPort, launchSecret)
            prefs.edit().putString("helper_launch_note", "").apply()
            val transport = checkNotNull(connection.adbConnection)
            val launch = launches.submit<AdbStream> {
                val stream = connection.openStream("shell:$command")
                try {
                    MirrorShellLaunch.awaitReady(stream.openInputStream()) { output ->
                        prefs.edit().putString("helper_launch_note", MirrorShellLaunch.safeDiagnostics(output, launchSecret)).apply()
                    }
                    stream // Ownership stays with startup until the helper's HELLO is authenticated.
                } catch (e: Exception) {
                    runCatching { stream.close() }
                    throw e
                }
            }
            try { launchStream = launch.get(15, TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) {
                // A library OPEN-answer race must not leave Start (or all later pairing attempts) wedged.
                launch.cancel(true)
                runCatching { transport.close() }
                throw IOException("Helper launch timed out", e)
            }
            stage(context, MirrorHelperRecovery.Phase.LINK, "Connecting to the built-in helper…")
            var client: Socket? = null
            for (attempt in 0 until 100) {
                try { client = openSocket(); break } catch (_: IOException) { Thread.sleep(100) }
            }
            val owner = client ?: throw IOException("Helper process did not start")
            try {
                val output = DataOutputStream(owner.outputStream)
                output.writeInt(MirrorHelperProtocol.VERSION); output.writeInt(MirrorHelperProtocol.HELLO); output.flush()
                val input = DataInputStream(owner.inputStream)
                check(input.readInt() == MirrorHelperProtocol.VERSION)
                stage(context, MirrorHelperRecovery.Phase.PHONE_ID, "Detecting phone Bluetooth identity…")
                val bt = NetworkIdentity.usableMac(input.readUTF()) ?: throw IOException("Phone Bluetooth identity unavailable")
                Variant.prefs(context).edit().putString("phone_bt", bt).putBoolean("helper_paired", true).apply()
                owner.soTimeout = 0
                control = owner; ready = true
                prefs.edit().putString("helper_phase", "READY").apply()
                state(context, "Built-in helper ready · phone identity detected")
                Log.i("carjack", "Bundled shell helper connected; phone identity detected automatically")
                Thread({
                    try { while (input.read() != -1) { } } catch (_: IOException) { }
                    finally {
                        if (control === owner) {
                            ready = false; control = null; audio.stop()
                            MirrorInput.stop()
                            state(context, "Helper stopped. Tap Start to reconnect.")
                            main.post { if (MirrorProjection.active) context.stopService(Intent(context, SlipstreamService::class.java)) }
                        }
                        runCatching { owner.close() }
                    }
                }, "mirror-helper-owner").start()
            } catch (e: Exception) { owner.close(); throw e }
        } finally {
            runCatching { launchStream?.close() }
            runCatching { connection.disconnect() }
        }
    }

    fun openSocket(): Socket {
        val port = localPort
        val key = secret
        check(port in 1..65535 && key.size == 32) { "Helper is not running" }
        val client = Socket()
        try {
            client.connect(InetSocketAddress("127.0.0.1", port), 1500)
            client.soTimeout = 10000
            client.tcpNoDelay = true
            MirrorHelperLink.client(client, key)
            return client
        } catch (e: Exception) { client.close(); throw e }
    }

    fun stop() {
        ready = false
        val previous = control; control = null
        MirrorInput.stop()
        audio.stop(); runCatching { previous?.close() }
        localPort = 0; secret = ByteArray(0)
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
                stage(app, MirrorHelperRecovery.Phase.PAIR, "Pairing the built-in helper…")
                require(MirrorHelperProtocol.pairingCode(code)) { "Enter six digits" }
                val port = manualPort ?: pairingPort.takeIf { it in 1..65535 } ?: throw IOException("Keep pairing dialog open")
                check(adb(app).pair("127.0.0.1", port, code))
                val prefs = Variant.prefs(app)
                prefs.edit().putBoolean("helper_paired", true)
                    .putLong("helper_pair_revision", prefs.getLong("helper_pair_revision", 0L) + 1L).apply()
                state(app, "Pairing saved · return to Highland Mirror and tap Start")
                main.post { pairing?.stop(); pairing = null; pairingPort = 0
                    app.getSystemService(NotificationManager::class.java).cancel(NOTICE) }
                // Start is a separate operation. Returning to the app also keeps it in the foreground.
            } catch (e: Exception) {
                failure(app, e)
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
