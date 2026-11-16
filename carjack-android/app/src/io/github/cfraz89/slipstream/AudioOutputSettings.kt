package io.github.cfraz89.slipstream

import android.content.Context
import android.os.SystemClock

/** The two live outputs share one CarPlay media connection. */
object AudioOutputSettings {
    const val HIGHLAND = "speakers"
    const val PHONE = "off"

    fun read(context: Context): String {
        val saved = Variant.setting(context, "audio", HIGHLAND)
        val output = if (saved == PHONE) PHONE else HIGHLAND
        // Retire old test, compatibility and Bluetooth choices on upgrade.
        if (saved != output) Variant.prefs(context).edit().putString("audio", output).apply()
        return output
    }

    fun select(context: Context, output: String) {
        require(output == HIGHLAND || output == PHONE)
        if (read(context) == output) return
        Variant.prefs(context).edit().putString("audio", output)
            .putLong("audio_revision", SystemClock.elapsedRealtimeNanos()).apply()
    }
}
