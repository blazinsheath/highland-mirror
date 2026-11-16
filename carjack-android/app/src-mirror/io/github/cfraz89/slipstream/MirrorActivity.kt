package io.github.cfraz89.slipstream

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*

class MirrorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Variant.prefs(this).edit().putBoolean("on", false).putBoolean("stopping", false)
            .putBoolean("streaming", false).putString("status", "Ready to set up").apply()
    }
}

/** Small, self-contained setup. Android owns pairing, Wi-Fi consent and screen-sharing consent. */
class StartActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private lateinit var phoneBt: EditText
    private lateinit var phoneWifi: EditText
    private lateinit var state: TextView
    private lateinit var control: TextView
    private lateinit var target: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post(::refresh) }
    private val required = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES)
    private val requested = required + arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(column); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        text("HIGHLAND MIRROR", 28f, Color.rgb(88, 214, 201))
        text("0.1.0-preview.1 · Phone screen → CarPlay", 14f)
        text("Mirror your Android phone to the CFMOTO. No Shizuku, wireless debugging or second app.")
        state = text("Ready", 20f)
        text("1. Phone identity", 20f)
        text("Enter this Samsung phone’s Bluetooth address once. Find it in Settings → About phone → Status information (turn Bluetooth on first). Android hides it from apps.")
        phoneBt = field("Phone Bluetooth address · AA:BB:CC:DD:EE:FF", "phone_bt")
        button("Open phone information") { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }
        text("Optional Wi-Fi address override: leave blank first. If the vehicle rejects the Wi-Fi handover, use the MAC shown for the phone’s connection to the CFMOTO network.", 14f)
        phoneWifi = field("Wi-Fi MAC override (optional)", "phone_wifi")
        button("Save phone identity") { saveIdentity() }
        text("2. Vehicle connection", 20f)
        target = text("Head unit: automatic", 16f)
        button("Choose paired head unit") { chooseHeadUnit() }
        button("Bluetooth pairing settings") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        button("Wi-Fi settings") { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        button("Allow nearby devices and audio") { requestPermissions(requested, 1) }
        text("3. Audio", 20f)
        val audio = RadioGroup(this)
        val vehicle = RadioButton(this).apply { id = View.generateViewId(); text = "CarPlay speakers" }
        val phone = RadioButton(this).apply { id = View.generateViewId(); text = "Phone only" }
        audio.addView(vehicle); audio.addView(phone)
        audio.check(if (AudioOutputSettings.read(this) == AudioOutputSettings.PHONE) phone.id else vehicle.id)
        audio.setOnCheckedChangeListener { _, checked ->
            AudioOutputSettings.select(this, if (checked == phone.id) AudioOutputSettings.PHONE else AudioOutputSettings.HIGHLAND)
        }
        column.addView(audio)
        text("Switch outputs during a connection. CarPlay copies media from apps that allow capture; phone playback may also remain audible. Calls and apps that block capture are not included.", 14f)
        text("4. Optional vehicle touch control", 20f)
        control = text("Touch control: off", 16f)
        button("Set up touch control") {
            AlertDialog.Builder(this).setTitle("Allow vehicle touch control?")
                .setMessage(getString(R.string.control_description))
                .setPositiveButton("Open accessibility settings") { _, _ -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                .setNegativeButton("Cancel", null).show()
        }
        text("Taps and one-finger swipes replay when you lift your finger. Viewing works without this permission.", 14f)
        text("5. Start mirroring", 20f)
        text("End Slipstream’s session first. Turn on Bluetooth and Wi-Fi, then tap Start and approve Android’s screen-sharing prompt. Keep this screen open until the vehicle connects and accept its Wi-Fi request.")
        start = button("Start mirroring") { begin() }
        stop = button("Stop mirroring") {
            Variant.prefs(this).edit().putBoolean("stopping", true).apply()
            if (!stopService(Intent(this, SlipstreamService::class.java))) {
                MirrorProjection.stop()
                Variant.prefs(this).edit().putBoolean("stopping", false).putBoolean("on", false).apply()
            }
        }
        button("Open phone Home screen") {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
        text("The screen mirrors its actual orientation. Rotate your phone for a wider picture. Keep it unlocked: locking or stopping Android screen sharing ends mirroring. Protected video, including Netflix, can appear black.", 14f)
        button("Share diagnostic log") { MirrorLogs.share(this) }
        text("Preview build: the ordinary Android Wi-Fi handover still needs testing on your Highland. Logs contain connection details, but this app does not save phone video or audio.", 14f)
        Variant.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    private fun text(value: String, size: Float = 16f, color: Int = Color.rgb(222, 231, 238)): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(0, dp(10), 0, dp(8)); column.addView(this)
        }
    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false
        column.addView(this, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { click() }
    }
    private fun field(hintText: String, key: String): EditText = EditText(this).apply {
        hint = hintText; setText(Variant.setting(this@StartActivity, key, "")); setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        column.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
    private fun saveIdentity(): Boolean {
        if (MirrorProjection.active) { message("Stop mirroring before changing phone identity."); return false }
        val bt = MirrorGeometry.mac(phoneBt.text.toString())
        val wifiText = phoneWifi.text.toString().trim()
        val wifi = if (wifiText.isEmpty()) "" else MirrorGeometry.mac(wifiText)
        if (bt == null) { phoneBt.error = "Enter a valid phone Bluetooth address"; return false }
        if (wifi == null) { phoneWifi.error = "Enter a valid Wi-Fi MAC, or leave blank"; return false }
        Variant.prefs(this).edit().putString("phone_bt", bt).putString("phone_wifi", wifi).apply()
        phoneBt.setText(bt); phoneWifi.setText(wifi)
        return true
    }
    private fun chooseHeadUnit() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(requested, 1); return
        }
        if (MirrorProjection.active) { message("Stop mirroring before changing head unit."); return }
        val devices = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        val labels = arrayOf("Automatic (CarPlay devices)") + devices.map { "${it.name ?: "Bluetooth device"} · ${it.address}" }
        AlertDialog.Builder(this).setTitle("Choose the CFMOTO")
            .setItems(labels) { _, which ->
                Variant.prefs(this).edit().putString("target", if (which == 0) "" else devices[which - 1].address).apply()
            }.show()
    }
    private fun begin() {
        if (MirrorProjection.active || Variant.prefs(this).getBoolean("stopping", false)) return
        if (!saveIdentity()) return
        if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(requested, 1); return
        }
        if (!getSystemService(BluetoothManager::class.java).adapter.isEnabled) {
            message("Turn Bluetooth on, then tap Start again."); openSettings(Settings.ACTION_BLUETOOTH_SETTINGS); return
        }
        if (!getSystemService(WifiManager::class.java).isWifiEnabled) {
            message("Turn Wi-Fi on, then tap Start again."); openSettings(Settings.ACTION_WIFI_SETTINGS); return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), 2)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK || data == null) return
        try {
            startForegroundService(Intent(this, SlipstreamService::class.java)
                .putExtra(MirrorProjection.RESULT_CODE, resultCode).putExtra(MirrorProjection.RESULT_DATA, data))
        } catch (e: Exception) { message("Could not start mirroring: ${e.message}") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            message("Nearby-device permission is needed to connect.")
        else message("Permissions updated. Tap Start when ready.")
    }
    private fun refresh() {
        if (!::state.isInitialized) return
        val prefs = Variant.prefs(this)
        val running = prefs.getBoolean("on", false)
        val stopping = prefs.getBoolean("stopping", false)
        state.text = if (stopping) "Ending the vehicle session…" else prefs.getString("status", "Ready")
        control.text = if (MirrorControlService.available()) "Touch control: enabled" else "Touch control: off (viewing still works)"
        target.text = "Head unit: " + Variant.setting(this, "target", "").ifEmpty { "automatic" }
        start.isEnabled = !running && !stopping
        stop.isEnabled = running && !stopping
    }
    override fun onResume() { super.onResume(); if (::state.isInitialized) refresh() }
    override fun onDestroy() {
        Variant.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }
}
