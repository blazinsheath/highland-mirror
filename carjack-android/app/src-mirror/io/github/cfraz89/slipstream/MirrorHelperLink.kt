package io.github.cfraz89.slipstream

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Loopback IPC with mutual per-launch authentication; no externally reachable listener. */
object MirrorHelperLink {
    private const val NONCE_SIZE = 32
    fun newSecret(): String = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun key(value: String): ByteArray {
        require(Regex("[a-f0-9]{64}").matches(value))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun nonce() = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    private fun proof(key: ByteArray, role: String, client: ByteArray, server: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update("Highland Mirror $role v${MirrorHelperProtocol.VERSION}".toByteArray(Charsets.US_ASCII))
            update(client); doFinal(server)
        }
    private fun readNonce(input: DataInputStream) = ByteArray(NONCE_SIZE).also { input.readFully(it) }
    private fun version(input: DataInputStream) {
        if (input.readInt() != MirrorHelperProtocol.VERSION) throw IOException("Helper protocol mismatch")
    }
    fun client(socket: Socket, key: ByteArray) {
        require(key.size == NONCE_SIZE)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val client = nonce()
        output.writeInt(MirrorHelperProtocol.VERSION); output.write(client); output.flush()
        version(input)
        val server = readNonce(input)
        val received = readNonce(input)
        if (!MessageDigest.isEqual(received, proof(key, "server", client, server)))
            throw IOException("Helper authentication failed")
        // Never disclose a client proof to a listener that cannot first prove the launch secret.
        output.write(proof(key, "client", client, server)); output.flush()
        if (input.readInt() != MirrorHelperProtocol.OK) throw IOException("Helper authentication rejected")
    }
    fun server(socket: Socket, key: ByteArray) {
        require(key.size == NONCE_SIZE)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        version(input)
        val client = readNonce(input)
        val server = nonce()
        output.writeInt(MirrorHelperProtocol.VERSION); output.write(server)
        output.write(proof(key, "server", client, server)); output.flush()
        if (!MessageDigest.isEqual(readNonce(input), proof(key, "client", client, server)))
            throw IOException("Helper authentication failed")
        output.writeInt(MirrorHelperProtocol.OK); output.flush()
    }
}
