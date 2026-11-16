package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test

class MirrorHelperProtocolTest {
    @Test fun acceptsOnlySixAsciiDigits() {
        assertTrue(MirrorHelperProtocol.pairingCode("001234"))
        for (value in listOf("12345", "1234567", "12 456", "１２３４５６", "12345\n"))
            assertFalse(MirrorHelperProtocol.pairingCode(value))
    }
    @Test fun restrictsPortsToTcpRange() {
        assertEquals(37001, MirrorHelperProtocol.port("37001"))
        for (value in listOf("0", "-1", "65536", "999999999999", "1;id", ""))
            assertNull(MirrorHelperProtocol.port(value))
    }
    @Test fun restrictsCaptureFormatsToNegotiatedPcm() {
        for (rate in listOf(24000, 44100, 48000)) for (channels in 1..2)
            assertTrue(MirrorHelperProtocol.validAudio(rate, channels))
        for ((rate, channels) in listOf(0 to 2, 44100 to 0, 44100 to 3, 192000 to 2))
            assertFalse(MirrorHelperProtocol.validAudio(rate, channels))
    }
    @Test fun shellQuoteProtectsTheInstalledApkPath() {
        assertEquals("'/data/app/a b/base.apk'", MirrorHelperProtocol.quote("/data/app/a b/base.apk"))
        assertEquals("'a'\"'\"'b'", MirrorHelperProtocol.quote("a'b"))
        assertEquals("'a\u0024(b)`id`'", MirrorHelperProtocol.quote("a\u0024(b)`id`"))
    }
}
