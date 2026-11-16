package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test

class MirrorRecoverySetupTest {
    @Test fun resetWaitsForOffBeforeRestoringOn() {
        val calls = mutableListOf<String>()
        assertTrue(MirrorBluetoothReset.reset { calls.add(it); true })
        assertEquals(listOf("disable", "wait-for-state:STATE_OFF", "enable", "wait-for-state:STATE_ON"), calls)
    }
    @Test fun failedDisableStillRestoresBluetooth() {
        val calls = mutableListOf<String>()
        assertFalse(MirrorBluetoothReset.reset { calls.add(it); it != "disable" })
        assertEquals(listOf("disable", "enable", "wait-for-state:STATE_ON"), calls)
    }
    @Test fun timedOutOffStillRestoresBluetooth() {
        val calls = mutableListOf<String>()
        assertFalse(MirrorBluetoothReset.reset { calls.add(it); it != "wait-for-state:STATE_OFF" })
        assertTrue(calls.contains("enable"))
        assertEquals("wait-for-state:STATE_ON", calls.last())
    }
    @Test fun exceptionWhileDisablingStillAttemptsRestore() {
        val calls = mutableListOf<String>()
        try {
            MirrorBluetoothReset.reset { calls.add(it); if (it == "disable") throw IllegalStateException(); true }
            fail("Expected exception")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("disable", "enable", "wait-for-state:STATE_ON"), calls)
    }
    @Test fun failedEnableIsNotReportedAsSuccessfulRecovery() {
        assertFalse(MirrorBluetoothReset.reset { it != "enable" })
    }
    @Test fun setupSkipsOnlyCompletedAuthorizationSteps() {
        assertEquals(MirrorSetup.Step.ENABLE_DEVELOPER, MirrorSetup.step(false, false, false))
        assertEquals(MirrorSetup.Step.ENABLE_WIRELESS, MirrorSetup.step(false, true, false))
        assertEquals(MirrorSetup.Step.PAIR, MirrorSetup.step(false, true, true))
    }
    @Test fun savedPairingSurvivesAndroidDisablingDebugging() {
        for (developer in listOf(false, true)) for (wireless in listOf(false, true)) {
            assertEquals(MirrorSetup.Step.READY, MirrorSetup.step(true, developer, wireless))
        }
    }
}
