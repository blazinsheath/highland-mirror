package io.github.cfraz89.slipstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Waits for a head unit, pages it until it dials the iAP service, and runs one session at a
 * time. The platform build runs it from boot; the generic one from its activity.
 */
class SlipstreamService : Service() {
    companion object {
        private const val TAG = "carjack"
        const val CHANNEL = "carjack"
        const val NOTIFICATION_ID = 1
        /** The notification's Turn off. */
        const val ACTION_STOP = "io.github.cfraz89.slipstream.STOP"
        /** Try again after giving up on a unit. */
        const val ACTION_RETRY = "io.github.cfraz89.slipstream.RETRY"
        const val WAITING = "Waiting for the head unit"
        private const val BLOCKED_CHANNEL = "blocked"
        private const val BLOCKED_ID = 4

        /** How often to page an absent unit, and how long to wait before cycling a link that is up. */
        private const val OFFER_WAIT_MS = 20_000L
        private const val OFFER_WAIT_MAX_MS = 60_000L
        /** Once the head unit's link is up, how long it gets to dial iAP before we dial it. */
        private const val UNIT_DIAL_GRACE_MS = 8_000L
        /** How long a session on a channel we dialled is still setting up iAP2, and can give way. */
        private const val DIALLED_SETUP_MS = 6_000L
        private const val MAX_CYCLES = 2
        /** At most this many relinks after sessions in any window this long. */
        private const val MAX_RELINKS = 3
        private const val RELINK_WINDOW_MS = 30_000L

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW),
            )
        }

        private fun name(device: BluetoothDevice): String = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

        private fun now() = SystemClock.elapsedRealtime()
    }

    private val thread = HandlerThread("carjack")
    private lateinit var notification: Notification.Builder
    private lateinit var handler: Handler
    private lateinit var bluetooth: Bluetooth
    private lateinit var wifi: AccessoryWifi
    private var session: Session? = null
    @Volatile private var destroying = false
    private var sessionDialled = false
    private var sessionStarted = 0L
    /** Where we can't page, a unit already linked at start gets only the dial grace. */
    private var offerWait = if (Variant.canPage()) OFFER_WAIT_MS else UNIT_DIAL_GRACE_MS
    private var waitingSince = 0L
    /** Bluetooth cycles left before only dialling; refilled by a session. */
    private lateinit var recovery: LinkRecovery
    /** The head unit given up on, for Retry. */
    private var gaveUpOn: BluetoothDevice? = null
    /** When the relinks in the last [RELINK_WINDOW_MS] went, oldest first. */
    private val relinks = ArrayDeque<Long>()
    private var pendingRelink: Runnable? = null
    private var pendingDial: Runnable? = null
    private lateinit var stopAction: Notification.Action
    private lateinit var retryAction: Notification.Action

    override fun onCreate() {
        super.onCreate()
        Variant.prepare(this)
        recovery = LinkRecovery(Variant.setting(this, "bluetooth_reset_limit", "$MAX_CYCLES")
            .toIntOrNull()?.coerceIn(0, MAX_CYCLES) ?: MAX_CYCLES)
        createChannel(this)
        stopAction = action("Turn off", ACTION_STOP, 0)
        retryAction = action("Retry", ACTION_RETRY, 1)
        notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(WAITING)
            .setSmallIcon(Variant.NOTIFICATION_ICON)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(Variant.openApp(this))
            .setActions(stopAction)
        startForeground(NOTIFICATION_ID, notification.build(), Variant.foregroundServiceTypes())
        Variant.onStatus(this, WAITING, false)

        Native.init(File(filesDir, "homekit.bin").path, Variant.REPORTER)
        thread.start()
        handler = Handler(thread.looper)
        wifi = AccessoryWifi(this, handler)
        AccessoryWifi.reset()
        // Cleanup may finish after onDestroy returns while native TEARDOWN is in flight.
        // Register against the application so Android does not forcibly unregister us early.
        bluetooth = Bluetooth(applicationContext, handler) { socket -> handler.post { accept(socket) } }
        if (!Variant.canPage()) {
            // A unit dials iAP only on a fresh link, and may carry it on a channel we dial then
            // too: give it a moment to dial first.
            bluetooth.setOnUnitLinked { unit ->
                if (destroying) return@setOnUnitLinked
                // A fresh link: give it the full wait before renewing it.
                gaveUpOn = null
                waitingSince = now()
                offerWait = OFFER_WAIT_MS
                if (session == null) status("${name(unit)} connected")
                scheduleDial(unit)
            }
            bluetooth.setOnAdapterState { state ->
                if (destroying) return@setOnAdapterState
                val prefs = Variant.prefs(this)
                prefs.edit().putInt("bluetooth_adapter_state", state).apply()
                if (state == BluetoothAdapter.STATE_ON) {
                    prefs.edit().putLong("bluetooth_restore_revision",
                        prefs.getLong("bluetooth_restore_revision", 0) + 1).apply()
                    // Both manual and automatic toggles count. Never immediately cycle again.
                    recovery.adapterRestored()
                    pendingRelink?.let(handler::removeCallbacks)
                    pendingRelink = null
                    gaveUpOn = null
                    waitingSince = now()
                    offerWait = OFFER_WAIT_MS
                    if (session == null) bluetooth.headUnit()?.let { unit ->
                        status("Bluetooth is on · reconnecting to ${name(unit)}")
                        scheduleDial(unit)
                    }
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    pendingDial?.let(handler::removeCallbacks)
                    pendingDial = null
                }
            }
        }
        handler.post {
            if (destroying) return@post
            bluetooth.start()
            waitingSince = now()
            handler.post(watch)
        }
    }

    /** One pending attempt, invalidated when the adapter drops or the target changes. */
    private fun scheduleDial(unit: BluetoothDevice) {
        pendingDial?.let(handler::removeCallbacks)
        val dial = Runnable {
            pendingDial = null
            if (!destroying && session == null && bluetooth.headUnit() == unit && !blocked(unit)) {
                status("Calling ${name(unit)}")
                bluetooth.dial(unit)
            }
        }
        pendingDial = dial
        handler.postDelayed(dial, UNIT_DIAL_GRACE_MS)
    }

    private fun action(title: String, intentAction: String, requestCode: Int): Notification.Action {
        val intent = PendingIntent.getService(
            this, requestCode, Intent(this, SlipstreamService::class.java).setAction(intentAction),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, title, intent).build()
    }

    /** Where things are up to, in the notification; [retryable] offers Retry where it has given up. */
    fun status(text: String, retryable: Boolean = false) {
        Log.i(TAG, "Status: $text")
        Variant.onStatus(this, text, retryable)
        synchronized(notification) {
            notification.setContentText(text)
            if (retryable) {
                notification.setActions(retryAction, stopAction)
            } else {
                notification.setActions(stopAction)
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification.build())
        }
    }

    /**
     * Whether [Variant.blocker] keeps [unit] from a session. If so, says so in a notification that
     * alerts: the connection one is silent.
     */
    private fun blocked(unit: BluetoothDevice): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val blocker = Variant.blocker(this)
        if (blocker == null) {
            manager.cancel(BLOCKED_ID)
            return false
        }
        status("$blocker; can't connect to ${name(unit)}", true)
        manager.createNotificationChannel(
            NotificationChannel(BLOCKED_CHANNEL, "Can't connect", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.notify(
            BLOCKED_ID,
            Notification.Builder(this, BLOCKED_CHANNEL)
                .setSmallIcon(Variant.NOTIFICATION_ICON)
                .setContentTitle("Can't connect to ${name(unit)}")
                .setContentText("$blocker. Start it, then retry.")
                .setOnlyAlertOnce(true)
                .setContentIntent(Variant.openApp(this))
                .setActions(retryAction)
                .build(),
        )
        gaveUpOn = unit
        return true
    }

    /** A fresh start with the head unit given up on: its link renewed. */
    private fun retry() {
        if (destroying) return
        val unit = gaveUpOn ?: bluetooth.headUnit()
        gaveUpOn = null
        if (session != null || unit == null) {
            status(if (session != null) "Already in a session" else WAITING)
            return
        }
        status("Trying ${name(unit)} again")
        waitingSince = now()
        pendingRelink?.let(handler::removeCallbacks)
        pendingRelink = null
        pendingDial?.let(handler::removeCallbacks)
        pendingDial = null
        if (Variant.canPage()) bluetooth.relink(unit) else renewLink(unit, force = true)
    }

    /** Without a session: find the head unit, and page it until it dials in. */
    private val watch = object : Runnable {
        override fun run() {
            if (destroying) return
            handler.postDelayed(this, 2000)
            if (session != null) return
            val unit = bluetooth.headUnit()
            if (!Variant.canPage()) {
                // A restarted service can miss the old ACL event, and CarPlay can suppress
                // ordinary audio profiles. Recover a known bonded unit even without that event.
                if (unit != null && gaveUpOn == null) renewLink(unit)
                return
            }
            if (unit == null) {
                if (now() - waitingSince >= Bluetooth.DISCOVERY_INTERVAL_MS) {
                    bluetooth.discover()
                    waitingSince = now()
                }
                return
            }
            // Android's stack brings its own profiles up on a page, which is the head unit's cue to dial
            // iAP; the link is only cycled if that turns out not to be enough.
            if (now() - waitingSince < offerWait) return
            waitingSince = now()
            if (!Variant.isConnected(unit)) {
                bluetooth.page(unit)
            } else if (Variant.setting(this@SlipstreamService, "relink", "false").toBoolean()) {
                Log.i(TAG, "No iAP channel offered in ${offerWait}ms; cycling the link")
                bluetooth.relink(unit)
                offerWait = minOf(offerWait * 2, OFFER_WAIT_MAX_MS)
            }
        }
    }

    /**
     * A shared budget covers watch, Retry and session failure recovery. Once Bluetooth comes
     * back on, only dial until a session has actually streamed.
     */
    private fun renewLink(unit: BluetoothDevice, force: Boolean = false) {
        if (!force && now() - waitingSince < offerWait) return
        waitingSince = now()
        if (blocked(unit)) return
        if (recovery.takeReset() && Variant.cycleBluetooth()) {
            status("Refreshing Bluetooth for ${name(unit)}")
        } else {
            status("Calling ${name(unit)}")
            bluetooth.dial(unit)
        }
        offerWait = minOf(offerWait * 2, OFFER_WAIT_MAX_MS)
    }

    private fun accept(socket: BluetoothSocket) {
        if (destroying) { Bluetooth.closeQuietly(socket); return }
        val dialled = bluetooth.wasDialled(socket)
        if (session == null && blocked(socket.remoteDevice)) {
            Bluetooth.closeQuietly(socket)
            return
        }
        session?.let { current ->
            // A unit carries iAP2 on the channel it opened over one we dialled, if it opens one.
            if (sessionDialled && !dialled && now() - sessionStarted < DIALLED_SETUP_MS) {
                Log.i(TAG, "The head unit dialled in; dropping the channel we dialled")
                session = null
                current.stop()
            } else {
                Log.w(TAG, "Already in a session; refusing ${socket.remoteDevice}")
                Bluetooth.closeQuietly(socket)
                return
            }
        }
        sessionDialled = dialled
        sessionStarted = now()
        offerWait = OFFER_WAIT_MS
        val unit = socket.remoteDevice
        status("Connecting to ${name(unit)}")
        val started = Session(this, handler, socket, wifi, { status(it) }) { ended ->
            if (session !== ended) return@Session
            session = null
            waitingSince = now()
            if (ended.streamed) recovery.streamed()
            // The head unit only dials iAP on a fresh link, and stays connected after a session
            // ends; cycle it so the next one can start, however many have failed.
            relinkAfterSession(unit)
        }
        session = started
        try {
            started.start(bluetooth.name(), bluetooth.address(), codec())
        } catch (e: IOException) {
            Log.e(TAG, "Could not start a session", e)
            Bluetooth.closeQuietly(socket)
            session = null
        }
    }

    /** [Bluetooth.relink], held back so sessions that fail fast can't cycle the link nonstop. */
    private fun relinkAfterSession(unit: BluetoothDevice) {
        while (relinks.isNotEmpty() && now() - relinks.first() >= RELINK_WINDOW_MS) relinks.removeFirst()
        val delay = if (relinks.size < MAX_RELINKS) 0L else relinks.first() + RELINK_WINDOW_MS - now()
        if (delay > 0) status("Trying ${name(unit)} again in ${(delay + 999) / 1000} s")
        val relink = Runnable {
            pendingRelink = null
            if (destroying || session != null) return@Runnable
            relinks.addLast(now())
            if (Variant.canPage()) bluetooth.relink(unit) else renewLink(unit, force = true)
        }
        pendingRelink = relink
        handler.postDelayed(relink, delay)
    }

    private fun codec() = when (Variant.setting(this, "codec", "auto")) {
        "h264" -> Native.CODEC_H264
        "hevc" -> Native.CODEC_HEVC
        else -> Native.CODEC_AUTO
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Variant.onStopRequested(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETRY -> handler.post(::retry)
        }
        return Variant.onStartCommand(this, intent)
    }

    /** Re-declare the foreground service with more types, once their consent is in hand. */
    fun promote(foregroundServiceTypes: Int) {
        startForeground(NOTIFICATION_ID, notification.build(), foregroundServiceTypes)
    }

    override fun onDestroy() {
        destroying = true
        handler.removeCallbacks(watch)
        pendingRelink?.let(handler::removeCallbacks)
        pendingDial?.let(handler::removeCallbacks)
        handler.post {
            // Cleared first, so the session's end isn't taken for a dropped one and relinked.
            val stopping = session
            session = null
            var cleaned = false
            val cleanup = Runnable {
                if (!cleaned) {
                    cleaned = true
                    bluetooth.stop()
                    wifi.leave()
                    Variant.onServiceDestroyed(this)
                    thread.quitSafely()
                }
            }
            // Let native TEARDOWN reach the vehicle before dropping its Wi-Fi connection.
            if (stopping == null) cleanup.run() else {
                stopping.stop { cleanup.run() }
                handler.postDelayed({ stopping.finishStop(); cleanup.run() }, 1500L)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
