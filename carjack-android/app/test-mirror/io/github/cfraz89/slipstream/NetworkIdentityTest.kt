package io.github.cfraz89.slipstream

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NetworkIdentityTest {
    @Test fun recoversUniversalEui64() {
        assertEquals("00:11:22:33:44:55", NetworkIdentity.macFromLinkLocal(
            InetAddress.getByName("fe80::211:22ff:fe33:4455").address))
    }
    @Test fun recoversRandomizedUnicastMac() {
        assertEquals("A2:B3:C4:D5:E6:F7", NetworkIdentity.macFromLinkLocal(
            InetAddress.getByName("fe80::a0b3:c4ff:fed5:e6f7").address))
    }
    @Test fun doesNotGuessPrivacyIid() {
        assertNull(NetworkIdentity.macFromLinkLocal(InetAddress.getByName("fe80::abcd:1234:5678:9abc").address))
    }
    @Test fun rejectsOtherAddressFamiliesAndGlobalPrefixes() {
        assertNull(NetworkIdentity.macFromLinkLocal(InetAddress.getByName("192.0.2.1").address))
        assertNull(NetworkIdentity.macFromLinkLocal(InetAddress.getByName("2001:db8::211:22ff:fe33:4455").address))
    }
    @Test fun rejectsPlaceholderAndMulticast() {
        assertNull(NetworkIdentity.usableMac("02:00:00:00:00:00"))
        assertNull(NetworkIdentity.macFromLinkLocal(InetAddress.getByName("fe80::311:22ff:fe33:4455").address))
    }
    @Test fun cfmotoDefaultNeedsNoPasswordEntry() {
        assertEquals("12345678", NetworkIdentity.passphrase("CFMOTO-example", null))
        assertEquals("12345678", NetworkIdentity.passphrase("cfmoto-example", ""))
    }
    @Test fun keepsCustomCredential() {
        assertEquals("custom-password", NetworkIdentity.passphrase("CFMOTO-example", "custom-password"))
    }
    @Test fun doesNotAssignCfmotoPasswordToOtherUnits() {
        assertNull(NetworkIdentity.passphrase("AnotherHeadUnit", null))
        assertNull(NetworkIdentity.passphrase("CFMOTOHome", null))
    }
}
