package io.github.cfraz89.slipstream

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import java.io.File

/** Highland Mirror: public Android APIs only, with no helper APK or debugging connection. */
object Variant {
    const val APP_ID = "io.github.blazinsheath.highlandmirror"
    const val PREFS = "highland-mirror"
    const val LOG_FILE = "mirror.log"
    val NOTIFICATION_ICON = R.drawable.ic_mirror
    val REPORTER = Native.Reporter { _, target, message ->
        if (target == "libcarjack::airplay" && message.contains("RECORD sent, streaming video")) {
            app?.let { prefs(it).edit().putBoolean("video_started", true)
                .putString("status", "Connected — mirroring your phone").apply() }
        }
    }
    @Volatile private var app: Context? = null
    private var logcat: java.lang.Process? = null

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun setting(context: Context, key: String, fallback: String): String = when (key) {
        "record_screen_samples" -> "false" // Never save the mirrored phone's pixels to diagnostics.
        "nav_bar" -> prefs(context).getString(key, "true") ?: "true"
        else -> prefs(context).getString(key, fallback) ?: fallback
    }
    fun prepare(context: Context) {
        app = context.applicationContext
        prefs(context).edit().putBoolean("on", true).putBoolean("linked", false)
            .putBoolean("video_started", false).apply()
        if (logcat == null) try {
            logcat = ProcessBuilder("logcat", "-v", "time", "-T", "1", "-f",
                File(context.filesDir, LOG_FILE).path, "-r", "1024", "-n", "2", "*:I").start()
        } catch (e: Exception) { Log.w("carjack", "No diagnostic log", e) }
    }
    fun foregroundServiceTypes() = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    fun onStartCommand(service: Service, intent: Intent?): Int {
        if (!MirrorProjection.active) try {
            if (!MirrorProjection.start(service as SlipstreamService, intent)) {
                onStatus(service, "Tap Start and approve screen sharing.", false)
                service.stopSelf()
            }
        } catch (e: Exception) {
            Log.e("carjack", "Screen sharing could not start", e)
            onStatus(service, "Screen sharing failed. Tap Start to approve it again.", false)
            service.stopSelf()
        }
        return Service.START_NOT_STICKY
    }
    fun onStatus(context: Context, text: String, retryable: Boolean) {
        val shown = when {
            text.startsWith("Streaming at") -> "Preparing your phone picture…"
            text.startsWith("Session ended") -> "Connection interrupted. Retry the vehicle connection."
            else -> text
        }
        val edit = prefs(context).edit().putString("status", shown).putBoolean("retryable", retryable)
        if (text.startsWith("Session ended")) edit.putBoolean("video_started", false).putString("last_connection_status", text)
        edit.apply()
    }
    fun onLinked(context: Context, linked: Boolean) {
        prefs(context).edit().putBoolean("linked", linked).apply()
    }
    fun onStopRequested(context: Context) { prefs(context).edit().putBoolean("on", false).apply() }
    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, StartActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    fun onServiceDestroyed(service: Service) {
        MirrorProjection.stop()
        val prefs = prefs(service)
        val edit = prefs.edit().putBoolean("on", false).putBoolean("streaming", false)
            .putBoolean("video_started", false).putBoolean("stopping", false)
        if (prefs.getBoolean("stopping", false)) edit.putString("status", "Stopped. Tap Start for a new session.")
        edit.apply()
    }
    fun blocker(context: Context): String? = when {
        !MirrorProjection.active -> "Approve screen sharing in Highland Mirror"
        MirrorGeometry.mac(setting(context, "phone_bt", "")) == null -> "Enter the phone Bluetooth address"
        else -> null
    }
    fun bluetoothAddress(context: Context, adapter: BluetoothAdapter): String? =
        MirrorGeometry.mac(setting(context, "phone_bt", ""))
    fun interfaceMac(iface: String): String? = app?.let {
        MirrorGeometry.mac(setting(it, "phone_wifi", ""))
    }
    fun joinPrimary(ssid: String, passphrase: String?) = false // Android's network approval instead.
    fun leavePrimary(ssid: String?) {}
    fun makeDiscoverable(adapter: BluetoothAdapter) {}
    fun confirmPairing(device: BluetoothDevice, intent: Intent) = false
    fun canPage() = false
    fun isConnected(device: BluetoothDevice) = true
    fun connect(device: BluetoothDevice) = false
    fun disconnect(device: BluetoothDevice) {}
    fun cycleBluetooth() = false
    fun openAudio(token: Long, sampleRate: Int, channels: Int) =
        MirrorProjection.audio.start(token, sampleRate, channels)
    fun closeAudio(token: Long) = MirrorProjection.audio.stop(token)
    fun openDisplay(context: Context, width: Int, height: Int, density: Int, target: Surface): Runnable =
        MirrorProjection.open(context, width, height, density, target)
    fun touch(context: Context, viewX: Int, viewY: Int, viewWidth: Int, viewHeight: Int) =
        Touch { action, pointer, x, y ->
            val (pw, ph) = MirrorProjection.phoneSize
            MirrorControlService.touch(action, pointer,
                MirrorGeometry.point(x - viewX, y - viewY, viewWidth, viewHeight, pw, ph))
        }
    fun key(context: Context, code: Int, down: Boolean) {
        if (!down) return
        when (code) {
            KeyEvent.KEYCODE_BACK -> MirrorControlService.global(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> goHome(context)
            KeyEvent.KEYCODE_APP_SWITCH -> MirrorControlService.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }
    fun key(context: Context, code: Int) = key(context, code, true)
    fun switchgear(context: Context, code: Int, down: Boolean) = key(context, code, down)
    fun goHome(context: Context) = MirrorControlService.home()
}

// Encoder preview hooks intentionally discard pixels; this app mirrors private phone content.
object Choices { fun current(context: Context) = "mirror" }
object Previews {
    fun save(context: Context, app: String, bitmap: android.graphics.Bitmap) { bitmap.recycle() }
}
