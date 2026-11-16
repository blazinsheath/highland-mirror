package io.github.cfraz89.slipstream

/** Small, versioned protocol on an authenticated loopback-only connection. */
object MirrorHelperProtocol {
    const val VERSION = 2
    const val HELLO = 1
    const val AUDIO = 2
    const val OK = 0
    const val SHELL_UID = 2000
    fun validAudio(rate: Int, channels: Int) = rate in listOf(24000, 44100, 48000) && channels in 1..2
    fun port(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }
    fun pairingCode(value: String) = value.length == 6 && value.all { it in '0'..'9' }
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
