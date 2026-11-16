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
        Variant.initializeDiagnostics(this)
        Variant.prefs(this).edit().putBoolean("on", false).putBoolean("stopping", false)
            .putBoolean("streaming", false).putBoolean("video_started", false).putBoolean("input_ready", false)
            .putBoolean("bluetooth_recovering", false)
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
    private var setupGuiding = false
    private var setupDialog: AlertDialog? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post(::refresh) }
    private val required = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES)
    private val requested = required + arrayOf(Manifest.permission.POST_NOTIFICATIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == MirrorInputProtocol.ACTION_HOME && MirrorProjection.attached) {
            openHome(); finish(); return
        }
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
        start = button("Start") {
            if (Variant.prefs(this).getBoolean("on", false)) stopSession()
            else { pendingStart = true; continueStart() }
        }
        retry = button("Retry vehicle connection") {
            startService(Intent(this, SlipstreamService::class.java).setAction(SlipstreamService.ACTION_RETRY))
        }
        button("Highland Home · add your apps") { openHome() }
        text("Your Highland home page, with app shortcuts you can add and arrange. It opens automatically when you start mirroring.", 14f)
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

        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        button("Settings & help") { options.visibility = if (options.visibility == View.GONE) View.VISIBLE else View.GONE }
        column.addView(options)
        val mainColumn = column
        column = options
        text("One-time setup", 20f)
        button("Set up phone connection") { helperSetup() }
        target = text("Vehicle: automatic", 16f)
        button("Choose paired vehicle") { chooseHeadUnit() }
        button("Pairing code · manual fallback") { manualPairing() }
        button("Debugging connection port · fallback") { connectionPort() }
        button("Pair built-in helper again") { helperSetup(true) }
        button("Touch fallback · optional") { controlSettings() }
        button("Share diagnostic log") { MirrorLogs.share(this) }
        button("Bluetooth settings") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        button("Wi-Fi settings") { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        text("CFMOTO Wi-Fi connects automatically. Default password: 12345678. A different password supplied by the vehicle is used automatically.", 14f)
        text("If the vehicle does not reconnect, the app briefly switches phone Bluetooth off and on. Other Bluetooth devices may briefly disconnect too.", 14f)
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
        text("Tap Start for guided phone setup. After pairing once, tap Start, approve Android screen sharing and accept the vehicle Wi-Fi request. Your phone is detected automatically. Everything is included in Highland Mirror.", 14f)
        text("Touch, swipes and Back/Home connect automatically through the built-in helper. Accessibility is optional and is kept only as a troubleshooting fallback.", 14f)
        text("The home page uses landscape. Other apps follow their phone orientation. Keep the phone unlocked and screen on; locking ends screen sharing. Vehicle touch controls supported apps but cannot unlock the phone. Protected Netflix video and cellular-call audio are not supported.", 14f)
        text("Wireless debugging must be enabled when starting the connection; Android may turn it off after a restart or network change. Pairing is remembered. Diagnostic logs contain connection details and numeric audio levels, but no saved phone video or audio.", 14f)
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
        AlertDialog.Builder(this).setTitle("Optional touch fallback")
            .setMessage("Automatic vehicle touch uses the built-in helper and needs no Accessibility setup. Enable this fallback only if automatic touch is unavailable.\n\n" + getString(R.string.control_description))
            .setPositiveButton("Open accessibility settings") { _, _ -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun helperSetup(forcePair: Boolean = false) {
        if (MirrorProjection.active) { message("Stop mirroring before pairing the helper."); return }
        if (!forcePair && Variant.prefs(this).getBoolean("helper_paired", false)) { helperProblem(); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9); return
        }
        showHelperGuide()
    }
    private fun helperProblem() {
        val prefs = Variant.prefs(this)
        val recovery = prefs.getString("helper_recovery", "").orEmpty()
        if (recovery == "PAIR" || (recovery.isEmpty() && !prefs.getBoolean("helper_paired", false))) {
            helperSetup(true); return
        }
        AlertDialog.Builder(this).setTitle("Pairing saved · start the helper")
            .setMessage(prefs.getString("helper_status", "Keep Wireless debugging enabled and retry.") +
                "\n\nA new pairing code is not needed. If automatic connection discovery fails, use the IP address & Port on the main Wireless debugging screen. Its connection port is different from the pairing-code dialog’s port.")
            .setPositiveButton("Retry Start") { _, _ -> pendingStart = true; continueStart() }
            .setNeutralButton("Connection port") { _, _ -> connectionPort() }
            .setNegativeButton("Wireless debugging") { _, _ -> openDebuggingSettings() }
            .show()
    }
    private fun connectionPort() {
        if (MirrorProjection.active) { message("Stop mirroring before changing the debugging connection."); return }
        val saved = Variant.prefs(this).getInt("helper_connect_override", 0)
        val input = EditText(this).apply {
            hint = "Automatic (leave blank)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (saved in 1..65535) setText(saved.toString())
        }
        val dialog = AlertDialog.Builder(this).setTitle("Debugging connection port")
            .setMessage("Enter only the number after the colon in IP address & Port on the MAIN Wireless debugging screen. Do not use the pairing-code dialog’s port. Leave blank for automatic discovery. This connects using the pairing already saved in this APK.")
            .setView(input).setPositiveButton("Save and start", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = input.text.toString().trim()
            val port = if (entered.isEmpty()) 0 else MirrorHelperProtocol.port(entered)
            if (port == null) { input.error = "Enter a port from 1 to 65535, or leave blank"; return@setOnClickListener }
            Variant.prefs(this).edit().putInt("helper_connect_override", port).apply()
            dialog.dismiss(); pendingStart = true; continueStart()
        } }
        dialog.show()
    }
    private fun showHelperGuide() {
        setupGuiding = true
        setupDialog?.dismiss()
        val step = MirrorSetup.step(false,
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1)
        val title: String
        val detail: String
        val button: String
        when (step) {
            MirrorSetup.Step.ENABLE_DEVELOPER -> {
                title = "Phone setup · 1 of 3"; button = "Open phone information"
                detail = "On Samsung, open Software information, then tap Build number seven times. Enter your phone PIN if Android asks. Return here for the next step.\n\nAndroid requires this one-time authorization for vehicle sound and touch."
            }
            MirrorSetup.Step.ENABLE_WIRELESS -> {
                title = "Phone setup · 2 of 3"; button = "Open debugging settings"
                detail = "Turn on Wireless debugging and approve your Wi-Fi network. Return here when it is on. Your phone must be connected to Wi-Fi."
            }
            else -> {
                title = "Phone setup · 3 of 3"; button = "Enter pairing code"
                detail = "Open Wireless debugging → Pair device with pairing code. Keep that dialog open, pull down notifications and reply to Highland Mirror with its six-digit code.\n\nNo address or port is needed. Return here when pairing finishes; setup continues automatically."
            }
        }
        setupDialog = AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setPositiveButton(button) { _, _ ->
                if (step == MirrorSetup.Step.ENABLE_DEVELOPER) openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
                else { MirrorHelper.beginPairing(this); openDebuggingSettings() }
            }.setNeutralButton("Manual fallback") { _, _ -> setupGuiding = false; manualPairing() }
            .setNegativeButton("Later") { _, _ -> setupGuiding = false }
            .setOnCancelListener { setupGuiding = false }.show()
    }
    private fun openDebuggingSettings() {
        // Supported Developer options entry; Settings may highlight the Wireless debugging row.
        // OEMs that ignore the highlight still show the correct parent page.
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })) }
        catch (_: android.content.ActivityNotFoundException) { openSettings(Settings.ACTION_SETTINGS) }
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
        if (MirrorHelper.bluetoothResetting) { main.postDelayed({ continueStart() }, 300); return }
        if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(requested, 1); return }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 3); return }
        if (!getSystemService(WifiManager::class.java).isWifiEnabled) { startActivityForResult(Intent(Settings.Panel.ACTION_WIFI), 4); return }
        if (!Variant.prefs(this).getBoolean("helper_paired", false)) { pendingStart = false; helperSetup(true); return }
        if (!MirrorHelper.ready) {
            if (helperStarting) return
            helperStarting = true; refresh()
            MirrorHelper.ensure(this) { ok ->
                helperStarting = false
                if (isDestroyed || isFinishing) return@ensure
                refresh()
                if (ok) continueStart() else { pendingStart = false; helperProblem() }
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
        setupState.text = when {
            helperStarting -> "Connecting your phone…"
            !prefs.getBoolean("helper_paired", false) -> "Tap Start for one-time phone setup"
            !running -> "Phone setup saved"
            else -> "Picture, sound and touch use one connection"
        }
        control.text = when {
            MirrorInput.available -> "Vehicle touch: automatic"
            MirrorControlService.available() -> "Vehicle touch: fallback enabled"
            prefs.getString("input_error", "").orEmpty().isNotEmpty() && running -> "Touch unavailable · see Settings & help"
            else -> "Touch connects automatically with the vehicle"
        }
        target.text = "Vehicle: " + Variant.setting(this, "target", "").ifEmpty { "automatic" }
        start.text = if (running) "Stop" else "Start"
        start.isEnabled = !stopping && !helperStarting
        retry.visibility = if (running && !prefs.getBoolean("video_started", false) && !stopping) View.VISIBLE else View.GONE
        retry.isEnabled = !prefs.getBoolean("bluetooth_recovering", false)
        val wanted = if (AudioOutputSettings.read(this) == AudioOutputSettings.PHONE) phoneId else vehicleId
        if (audio.checkedRadioButtonId != wanted) audio.check(wanted)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == MirrorInputProtocol.ACTION_HOME && MirrorProjection.attached) openHome()
    }
    override fun onResume() {
        super.onResume()
        if (::state.isInitialized) refresh()
        if (setupGuiding) {
            if (Variant.prefs(this).getBoolean("helper_paired", false)) {
                setupGuiding = false; setupDialog?.dismiss(); pendingStart = true; continueStart()
            } else showHelperGuide()
        }
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); setupDialog?.dismiss(); Variant.prefs(this).unregisterOnSharedPreferenceChangeListener(listener); super.onDestroy() }
}
