package io.github.cfraz89.slipstream

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.bluetooth.BluetoothAdapter
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
import io.github.cfraz89.slipstream.launcher.HomeActivity

class MirrorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Variant.prefs(this).edit().putBoolean("on", false).putBoolean("stopping", false)
            .putBoolean("streaming", false).putBoolean("video_started", false)
            .putString("status", "Ready to connect").apply()
    }
}

/** Connect is one guided flow; advanced identity/network fields stay out of daily use. */
class StartActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private lateinit var state: TextView
    private lateinit var control: TextView
    private lateinit var target: TextView
    private lateinit var setupState: TextView
    private lateinit var start: Button
    private lateinit var retry: Button
    private lateinit var audio: RadioGroup
    private var vehicleId = 0
    private var phoneId = 0
    private var pendingStart = false
    private var helperStarting = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post(::refresh) }
    private val required = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES)
    private val requested = required + arrayOf(Manifest.permission.POST_NOTIFICATIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        val scroll = ScrollView(this).apply { addView(column); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
        text("Highland Mirror", 30f, Color.rgb(88, 214, 201))
        @Suppress("DEPRECATION")
        text("${packageManager.getPackageInfo(packageName, 0).versionName} · Phone → Highland", 14f)
        state = text("Ready to connect", 21f)
        setupState = text("", 14f)
        button("Set up built-in helper") { helperSetup() }
        start = button("Start") {
            if (Variant.prefs(this).getBoolean("on", false)) stopSession()
            else { pendingStart = true; continueStart() }
        }
        retry = button("Retry vehicle connection") {
            startService(Intent(this, SlipstreamService::class.java).setAction(SlipstreamService.ACTION_RETRY))
        }
        button("Highland Home · add your apps") { openHome() }
        text("Your familiar Slipstream home page, with app shortcuts you can add and arrange. It opens automatically when you start mirroring.", 14f)
        text("Audio", 19f)
        audio = RadioGroup(this)
        val vehicle = RadioButton(this).apply { id = View.generateViewId(); text = "Highland speakers" }
        val phone = RadioButton(this).apply { id = View.generateViewId(); text = "Phone" }
        vehicleId = vehicle.id; phoneId = phone.id
        audio.addView(vehicle); audio.addView(phone); column.addView(audio)
        audio.check(if (AudioOutputSettings.read(this) == AudioOutputSettings.PHONE) phoneId else vehicleId)
        audio.setOnCheckedChangeListener { _, checked ->
            AudioOutputSettings.select(this, if (checked == phoneId) AudioOutputSettings.PHONE else AudioOutputSettings.HIGHLAND)
        }
        control = text("", 14f)
        button("Enable vehicle touch control") { controlSettings() }
        button("Share diagnostic log") { MirrorLogs.share(this) }

        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        button("Settings & help") { options.visibility = if (options.visibility == View.GONE) View.VISIBLE else View.GONE }
        column.addView(options)
        val mainColumn = column
        column = options
        text("One-time setup", 20f)
        target = text("Vehicle: automatic", 16f)
        button("Choose paired vehicle") { chooseHeadUnit() }
        button("Pairing code · manual fallback") { manualPairing() }
        button("Bluetooth settings") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        button("Wi-Fi settings") { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        text("CFMOTO Wi-Fi connects automatically. Default password: 12345678. A different password supplied by the vehicle is used automatically.", 14f)
        button("Advanced Wi-Fi address override") { wifiOverride() }
        button("Launcher music controls") {
            AlertDialog.Builder(this).setTitle("Launcher music controls")
                .setMessage("Optional notification access lets the home page show the playing song and its playback controls. Review Android’s permission screen before enabling Highland Mirror.")
                .setPositiveButton("Open notification access") { _, _ -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                .setNegativeButton("Cancel", null).show()
        }
        button("Open-source licenses") {
            val names = arrayOf("NOTICE.txt", "Apache-2.0.txt", "LGPL-3.0.txt", "GPL-3.0.txt", "spake2-c-LICENSE.txt", "Bouncy-Castle.txt", "Conscrypt.txt")
            AlertDialog.Builder(this).setTitle("Open-source licenses").setItems(names) { _, index ->
                val body = TextView(this).apply { text = assets.open("licenses/" + names[index]).bufferedReader().use { it.readText() }; setPadding(dp(20), dp(16), dp(20), dp(16)) }
                AlertDialog.Builder(this).setTitle(names[index]).setView(ScrollView(this).apply { addView(body) })
                    .setPositiveButton("Close", null).show()
            }.show()
        }
        text("How to connect", 20f)
        text("End the old Slipstream session. Pair the built-in helper once, then tap Start, approve Android screen sharing and accept the vehicle Wi-Fi request. The phone’s Bluetooth identity is detected automatically. No separate Shizuku or helper app is needed.", 14f)
        text("Turn on vehicle touch control once to open apps and use Back/Home from the Highland. If Android blocks the accessibility toggle, open App info → ⋮ → Allow restricted settings, then return to Accessibility.", 14f)
        text("The home page uses landscape. Other apps follow their phone orientation. Keep the phone unlocked and screen on; locking ends screen sharing. Vehicle touch controls supported apps but cannot unlock the phone. Protected Netflix video and cellular-call audio are not supported.", 14f)
        text("The built-in helper restores the playback capture used in working Slipstream Highland. Android Wireless debugging must be enabled when starting the helper; Android may turn it off after a restart or network change. Once started, media uses a local connection. This preview still needs a physical vehicle test. Logs contain connection details and numeric audio levels, but no saved phone video or audio.", 14f)
        column = mainColumn
        Variant.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    private fun text(value: String, size: Float = 16f, color: Int = Color.rgb(222, 231, 238)): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(9), 0, dp(7)); column.addView(this) }
    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; column.addView(this, LinearLayout.LayoutParams(-1, -2)); setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (e: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun openHome() { startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    private fun controlSettings() {
        AlertDialog.Builder(this).setTitle("Control apps from the Highland")
            .setMessage(getString(R.string.control_description))
            .setPositiveButton("Open accessibility settings") { _, _ -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun helperSetup() {
        if (MirrorProjection.active) { message("Stop mirroring before pairing the helper."); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9); return
        }
        showHelperGuide()
    }
    private fun showHelperGuide() {
        AlertDialog.Builder(this).setTitle("One app · built-in helper")
            .setMessage("On this Samsung: enable Developer options by tapping Settings → About phone → Software information → Build number seven times.\n\nThen open Developer options → Wireless debugging, turn it on and choose Pair device with pairing code. Keep that dialog open, pull down notifications and enter the six-digit code in the Highland Mirror notification. Return here and tap Start.\n\nPairing is saved in this APK. Wireless debugging may need enabling again after a phone restart. No other app is installed.")
            .setPositiveButton("Open Developer options") { _, _ ->
                MirrorHelper.beginPairing(this)
                openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            }.setNeutralButton("Manual pairing") { _, _ -> manualPairing() }
            .setNegativeButton("Close", null).show()
    }
    private fun manualPairing() {
        if (MirrorProjection.active) { message("Stop mirroring before pairing the helper."); return }
        val port = EditText(this).apply { hint = "Pairing port (after the colon)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val code = EditText(this).apply { hint = "Six-digit pairing code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(port); addView(code) }
        val dialog = AlertDialog.Builder(this).setTitle("Pair built-in helper")
            .setMessage("Use split screen to keep Android’s Pair device with pairing code dialog open. Enter its pairing port and code here. The notification method avoids entering the port.")
            .setView(box).setPositiveButton("Pair", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val number = MirrorHelperProtocol.port(port.text.toString().trim())
            val digits = code.text.toString().trim()
            if (number == null) { port.error = "Enter the pairing port"; return@setOnClickListener }
            if (!MirrorHelperProtocol.pairingCode(digits)) { code.error = "Enter six digits"; return@setOnClickListener }
            MirrorHelper.pair(this, digits, number); dialog.dismiss()
        } }
        dialog.show()
    }
    private fun wifiOverride() {
        if (MirrorProjection.active) { message("Stop mirroring before changing network setup."); return }
        val input = EditText(this).apply { setSingleLine(true); hint = "Automatic (leave blank)"; setText(Variant.setting(this@StartActivity, "phone_wifi", "")) }
        val dialog = AlertDialog.Builder(this).setTitle("Wi-Fi address override")
            .setMessage("The app now detects the joined Wi-Fi address automatically where the phone exposes an EUI-64 link-local ID. Use this override only for troubleshooting.")
            .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = input.text.toString().trim()
            val address = if (entered.isEmpty()) "" else NetworkIdentity.usableMac(entered)
            if (address == null) input.error = "Enter a valid address, or leave blank"
            else { Variant.prefs(this).edit().putString("phone_wifi", address).apply(); dialog.dismiss() }
        } }
        dialog.show()
    }
    private fun chooseHeadUnit() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { requestPermissions(required, 6); return }
        if (MirrorProjection.active) { message("Stop mirroring before changing vehicle."); return }
        val devices = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        val labels = arrayOf("Automatic") + devices.map { it.name ?: it.address }
        AlertDialog.Builder(this).setTitle("Choose your vehicle").setItems(labels) { _, which ->
            Variant.prefs(this).edit().putString("target", if (which == 0) "" else devices[which - 1].address).apply()
        }.show()
    }
    private fun continueStart() {
        if (!pendingStart || MirrorProjection.active) return
        if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(requested, 1); return }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 3); return }
        if (!getSystemService(WifiManager::class.java).isWifiEnabled) { startActivityForResult(Intent(Settings.Panel.ACTION_WIFI), 4); return }
        if (!MirrorHelper.ready) {
            if (helperStarting) return
            helperStarting = true; refresh()
            MirrorHelper.ensure(this) { ok ->
                helperStarting = false
                if (isDestroyed || isFinishing) return@ensure
                refresh()
                if (ok) continueStart() else { pendingStart = false; helperSetup() }
            }
            return
        }
        if (Variant.setting(this, "target", "").isEmpty()) {
            val units = adapter.bondedDevices.filter { it.name?.startsWith("CFMOTO", true) == true }
            if (units.size == 1) Variant.prefs(this).edit().putString("target", units.single().address).apply()
        }
        pendingStart = false
        startActivityForResult(getSystemService(MediaProjectionManager::class.java)
            .createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), 2)
    }
    private fun stopSession() {
        Variant.prefs(this).edit().putBoolean("stopping", true).apply()
        if (!stopService(Intent(this, SlipstreamService::class.java))) {
            MirrorProjection.stop()
            MirrorHelper.stop()
            Variant.prefs(this).edit().putBoolean("stopping", false).putBoolean("on", false).apply()
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            2 -> if (resultCode == RESULT_OK && data != null) try {
                startForegroundService(Intent(this, SlipstreamService::class.java)
                    .putExtra(MirrorProjection.RESULT_CODE, resultCode).putExtra(MirrorProjection.RESULT_DATA, data))
                openHome()
            } catch (e: Exception) { MirrorHelper.stop(); message("Could not start mirroring: ${e.message}") }
            else { MirrorHelper.stop(); Variant.prefs(this).edit().putString("helper_status", "Pairing saved · tap Start when ready").apply() }
            3 -> if (resultCode == RESULT_OK) continueStart() else pendingStart = false
            4 -> if (getSystemService(WifiManager::class.java).isWifiEnabled) continueStart() else pendingStart = false
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (requestCode == 9) {
            showHelperGuide()
        } else if (requestCode == 6) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) chooseHeadUnit()
        } else if (requestCode == 1) {
            if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { pendingStart = false; message("Nearby-device permission is needed to connect.") }
            else continueStart()
        }
    }
    private fun refresh() {
        if (!::state.isInitialized || !::target.isInitialized) return
        val prefs = Variant.prefs(this)
        val running = prefs.getBoolean("on", false)
        val stopping = prefs.getBoolean("stopping", false)
        state.text = if (stopping) "Ending the vehicle session…" else prefs.getString("status", "Ready to connect")
        setupState.text = prefs.getString("helper_status", "One app · set up the built-in helper once")
        control.text = if (MirrorControlService.available()) "Vehicle touch: enabled" else "Vehicle touch: enable once to control apps from the Highland"
        target.text = "Vehicle: " + Variant.setting(this, "target", "").ifEmpty { "automatic" }
        start.text = if (running) "Stop" else "Start"
        start.isEnabled = !stopping && !helperStarting
        retry.visibility = if (running && !prefs.getBoolean("video_started", false) && !stopping) View.VISIBLE else View.GONE
        val wanted = if (AudioOutputSettings.read(this) == AudioOutputSettings.PHONE) phoneId else vehicleId
        if (audio.checkedRadioButtonId != wanted) audio.check(wanted)
    }
    override fun onResume() { super.onResume(); if (::state.isInitialized) refresh() }
    override fun onDestroy() { Variant.prefs(this).unregisterOnSharedPreferenceChangeListener(listener); super.onDestroy() }
}
