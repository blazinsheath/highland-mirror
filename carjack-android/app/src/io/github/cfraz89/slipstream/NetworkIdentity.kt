package io.github.cfraz89.slipstream

import java.util.Locale

/** Network setup helpers with no privileged API or saved device identifier. */
object NetworkIdentity {
    fun usableMac(value: String?): String? {
        val mac = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (!Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)) return null
        if (mac == "00:00:00:00:00:00" || mac == "02:00:00:00:00:00" ||
            (mac.substring(0, 2).toInt(16) and 1) != 0) return null
        return mac
    }

    /** Recover only a recognizable modified EUI-64 link-local IID (RFC 4291 appendix A). */
    fun macFromLinkLocal(bytes: ByteArray): String? {
        if (bytes.size != 16 || (bytes[0].toInt() and 255) != 254 ||
            (bytes[1].toInt() and 192) != 128 || (bytes[11].toInt() and 255) != 255 ||
            (bytes[12].toInt() and 255) != 254) return null
        val mac = intArrayOf((bytes[8].toInt() and 255) xor 2, bytes[9].toInt() and 255,
            bytes[10].toInt() and 255, bytes[13].toInt() and 255,
            bytes[14].toInt() and 255, bytes[15].toInt() and 255)
            .joinToString(":") { "%02X".format(Locale.ROOT, it) }
        return usableMac(mac)
    }

    /** Respect the paired unit's advertised/custom credential; CFMOTO defaults only if absent. */
    fun passphrase(ssid: String, advertised: String?): String? {
        if (!advertised.isNullOrBlank()) return advertised
        return if (Regex("^CFMOTO(?:[-_ ].*|$)", RegexOption.IGNORE_CASE).matches(ssid)) "12345678" else advertised
    }
}
