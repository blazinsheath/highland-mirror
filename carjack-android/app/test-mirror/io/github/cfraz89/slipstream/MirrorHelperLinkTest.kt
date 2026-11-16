package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MirrorHelperLinkTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private fun socket(port: Int) = Socket(address, port).apply { soTimeout = 2000; tcpNoDelay = true }

    @Test(timeout = 10000) fun authenticatesControlAndSeparateAudioWithoutChangingPcm() {
        val key = MirrorHelperLink.key(MirrorHelperLink.newSecret())
        val pcm = ByteArray(1764) { (it * 31).toByte() }
        ServerSocket(0, 4, address).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val done = worker.submit<Boolean> {
                    repeat(2) {
                        listener.accept().use { peer ->
                            peer.soTimeout = 2000
                            MirrorHelperLink.server(peer, key)
                            val input = DataInputStream(peer.getInputStream())
                            val output = DataOutputStream(peer.getOutputStream())
                            assertEquals(MirrorHelperProtocol.AUDIO, input.readInt())
                            output.write(pcm); output.flush()
                        }
                    }
                    true
                }
                repeat(2) {
                    socket(listener.localPort).use { client ->
                        MirrorHelperLink.client(client, key)
                        DataOutputStream(client.getOutputStream()).writeInt(MirrorHelperProtocol.AUDIO)
                        val got = ByteArray(pcm.size)
                        DataInputStream(client.getInputStream()).readFully(got)
                        assertArrayEquals(pcm, got)
                    }
                }
                assertTrue(done.get(4, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun rejectsAnotherAppWithoutReadingItsCommand() {
        val key = MirrorHelperLink.key(MirrorHelperLink.newSecret())
        val wrongKey = key.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        ServerSocket(0, 1, address).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val done = worker.submit<Boolean> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 2000
                        try { MirrorHelperLink.server(peer, key); true } catch (_: IOException) { false }
                    }
                }
                assertThrows(IOException::class.java) {
                    socket(listener.localPort).use { MirrorHelperLink.client(it, wrongKey) }
                }
                assertFalse(done.get(4, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun clientDoesNotAuthenticateToAnImpostorListener() {
        val key = MirrorHelperLink.key(MirrorHelperLink.newSecret())
        ServerSocket(0, 1, address).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val done = worker.submit<Int> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 2000
                        val input = DataInputStream(peer.getInputStream())
                        assertEquals(MirrorHelperProtocol.VERSION, input.readInt())
                        input.readFully(ByteArray(32))
                        val output = DataOutputStream(peer.getOutputStream())
                        output.writeInt(MirrorHelperProtocol.VERSION)
                        output.write(ByteArray(32)); output.write(ByteArray(32)); output.flush()
                        input.read() // A valid client closes without sending its proof or a command.
                    }
                }
                assertThrows(IOException::class.java) {
                    socket(listener.localPort).use { MirrorHelperLink.client(it, key) }
                }
                assertEquals(-1, done.get(4, TimeUnit.SECONDS).toInt())
            } finally { worker.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun rejectsOldUnprotectedWireProtocol() {
        val key = MirrorHelperLink.key(MirrorHelperLink.newSecret())
        ServerSocket(0, 1, address).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val done = worker.submit<Boolean> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 2000
                        try { MirrorHelperLink.server(peer, key); false } catch (_: IOException) { true }
                    }
                }
                socket(listener.localPort).use {
                    DataOutputStream(it.getOutputStream()).writeInt(MirrorHelperProtocol.VERSION - 1)
                    assertEquals(-1, it.getInputStream().read())
                }
                assertTrue(done.get(4, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun acceptsOnlyFullPerLaunchSecrets() {
        assertEquals(32, MirrorHelperLink.key(MirrorHelperLink.newSecret()).size)
        for (bad in listOf("", "0".repeat(63), "0".repeat(65), "z".repeat(64)))
            assertThrows(IllegalArgumentException::class.java) { MirrorHelperLink.key(bad) }
    }
}
