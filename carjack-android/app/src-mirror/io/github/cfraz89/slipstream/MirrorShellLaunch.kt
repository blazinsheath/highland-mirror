package io.github.cfraz89.slipstream

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A launch stream's EOF is not a readiness signal. The actual helper must report listening. */
object MirrorShellLaunch {
    const val READY = "HIGHLAND_MIRROR_HELPER_LISTENING_V2"
    private const val MAX_OUTPUT = 8192

    fun command(apk: String, port: Int, secret: String): String {
        require(port in 1..65535)
        MirrorHelperLink.key(secret) // Only a generated full-length secret may enter the command.
        return "CLASSPATH=" + MirrorHelperProtocol.quote(apk) +
            " app_process /system/bin io.github.cfraz89.slipstream.MirrorShellMain " + port +
            " " + MirrorHelperProtocol.quote(secret) + " </dev/null 2>&1 & highland_pid=\$!; " +
            // Keep the ADB parent alive until the helper has detached and the app owns its link.
            "while kill -0 \"\$highland_pid\" 2>/dev/null; do sleep 1; done"
    }

    fun awaitReady(input: InputStream, diagnostics: (String) -> Unit = {}) {
        val collected = ByteArrayOutputStream()
        val bytes = ByteArray(256)
        try {
            while (collected.size() < MAX_OUTPUT) {
                val count = input.read(bytes, 0, minOf(bytes.size, MAX_OUTPUT - collected.size()))
                if (count < 0) throw IOException("Helper exited before reporting readiness")
                if (count == 0) continue
                collected.write(bytes, 0, count)
                val text = collected.toString(Charsets.UTF_8.name())
                if (text.lineSequence().any { it.trimEnd('\r') == READY }) return
            }
            throw IOException("Helper startup output exceeded its limit")
        } finally {
            diagnostics(collected.toString(Charsets.UTF_8.name()))
        }
    }

    fun safeDiagnostics(output: String, secret: String): String = output
        .replace(secret, "[launch secret removed]")
        .replace(Regex("[a-fA-F0-9]{32,}"), "[long identifier removed]")
        .replace(READY, "Helper reported listening")
        .take(2048)
}
