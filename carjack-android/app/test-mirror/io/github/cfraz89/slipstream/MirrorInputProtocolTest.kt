package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MirrorInputProtocolTest {
    private fun stream(events: List<MirrorInputProtocol.Event>): DataInputStream {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        events.forEach { MirrorInputProtocol.write(output, it) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }
    @Test fun preservesGestureCoordinatesAndPointerOrder() {
        val events = listOf(
            MirrorInputProtocol.Event(1, 0, 2, 100.25f, 200.5f),
            MirrorInputProtocol.Event(1, 2, 2, 155.75f, 250.5f),
            MirrorInputProtocol.Event(1, 1, 2, 160f, 260f),
        )
        val input = stream(events)
        assertEquals(events, events.map { MirrorInputProtocol.read(input) })
        assertEquals(0, input.available())
    }
    @Test fun homeCancelAndKeyFramesDoNotConsumeTheNextCommand() {
        val events = listOf(
            MirrorInputProtocol.Event(MirrorInputProtocol.KEY, code = 4, down = true),
            MirrorInputProtocol.Event(MirrorInputProtocol.KEY, code = 4, down = false),
            MirrorInputProtocol.Event(MirrorInputProtocol.HOME),
            MirrorInputProtocol.Event(MirrorInputProtocol.CANCEL),
        )
        val input = stream(events)
        assertEquals(events, events.map { MirrorInputProtocol.read(input) })
        assertEquals(0, input.available())
    }
    @Test fun rejectsInvalidCoordinatesPointersAndKeys() {
        val bad = listOf(
            MirrorInputProtocol.Event(1, 0, 0, Float.NaN, 10f),
            MirrorInputProtocol.Event(1, 0, 0, 10f, Float.POSITIVE_INFINITY),
            MirrorInputProtocol.Event(1, 0, 0, -1f, 10f),
            MirrorInputProtocol.Event(1, 0, 0, 32768f, 10f),
            MirrorInputProtocol.Event(1, 0, 32, 10f, 10f),
            MirrorInputProtocol.Event(1, 8, 0, 10f, 10f),
            MirrorInputProtocol.Event(2, code = 0),
            MirrorInputProtocol.Event(2, code = 512),
        )
        for (event in bad) assertFalse(MirrorInputProtocol.valid(event))
    }
    @Test fun refusesUnknownCommandsWithoutReadingAnArbitraryPayload() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(500)
        assertThrows(IOException::class.java) { MirrorInputProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }
    @Test fun truncatedGestureIsAClosedConnectionRatherThanAnInputEvent() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(1); writeInt(0); writeInt(0) }
        assertThrows(EOFException::class.java) { MirrorInputProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }
    @Test(timeout = 10000) fun acknowledgedInputsKeepTheirOrderOnTheAuthenticatedLink() {
        val key = MirrorHelperLink.key(MirrorHelperLink.newSecret())
        val address = InetAddress.getByName("127.0.0.1")
        val events = listOf(MirrorInputProtocol.Event(1, 0, 0, 10f, 20f),
            MirrorInputProtocol.Event(1, 2, 0, 30f, 40f), MirrorInputProtocol.Event(1, 1, 0, 30f, 40f),
            MirrorInputProtocol.Event(MirrorInputProtocol.CANCEL), MirrorInputProtocol.Event(MirrorInputProtocol.HOME))
        ServerSocket(0, 1, address).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val received = worker.submit<List<MirrorInputProtocol.Event>> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 2000
                        MirrorHelperLink.server(peer, key)
                        val input = DataInputStream(peer.getInputStream())
                        val output = DataOutputStream(peer.getOutputStream())
                        events.map { MirrorInputProtocol.read(input).also { output.writeInt(0); output.flush() } }
                    }
                }
                Socket(address, listener.localPort).use { client ->
                    client.soTimeout = 2000
                    MirrorHelperLink.client(client, key)
                    val input = DataInputStream(client.getInputStream())
                    val output = DataOutputStream(client.getOutputStream())
                    for (event in events) { MirrorInputProtocol.write(output, event); assertEquals(0, input.readInt()) }
                }
                assertEquals(events, received.get(4, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }
}
