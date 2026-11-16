package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class MirrorShellLaunchTest {
    @Test fun readinessReturnsBeforeAdbCloseErrorOrEof() {
        val data = (MirrorShellLaunch.READY + "\n").toByteArray()
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Use bulk read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (++reads > 1) throw IOException("Stream closed.") // Reproduces libadb 3.1.1 after a shell closes.
                data.copyInto(bytes, offset)
                return data.size
            }
        }
        MirrorShellLaunch.awaitReady(input)
        assertEquals(1, reads)
    }
    @Test fun recognizesHelperReadinessAcrossPacketBoundaries() {
        val data = ("Runtime warning\n" + MirrorShellLaunch.READY + "\n").toByteArray()
        val input = object : ByteArrayInputStream(data) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(3, length))
        }
        MirrorShellLaunch.awaitReady(input)
    }
    @Test fun earlyEofIsNotMistakenForAStartedHelper() {
        var diagnostic = ""
        assertThrows(IOException::class.java) {
            MirrorShellLaunch.awaitReady("VM failed before listening\n".byteInputStream()) { diagnostic = it }
        }
        assertTrue(diagnostic.contains("VM failed before listening"))
    }
    @Test fun libraryCloseErrorBeforeReadyIsStillAFailure() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Stream closed.")
        }
        assertThrows(IOException::class.java) { MirrorShellLaunch.awaitReady(input) }
    }
    @Test fun startupOutputCannotGrowWithoutLimit() {
        var diagnostic = ""
        assertThrows(IOException::class.java) {
            MirrorShellLaunch.awaitReady(ByteArrayInputStream(ByteArray(20000) { 65 })) { diagnostic = it }
        }
        assertEquals(8192, diagnostic.length)
    }
    @Test fun launchSecretsAreRemovedFromBoundedStartupDiagnostics() {
        val secret = "a1".repeat(32)
        val got = MirrorShellLaunch.safeDiagnostics("Launch error $secret\n" + "z".repeat(4000), secret)
        assertFalse(got.contains(secret))
        assertTrue(got.contains("[launch secret removed]"))
        assertTrue(got.length <= 2048)
    }
}
