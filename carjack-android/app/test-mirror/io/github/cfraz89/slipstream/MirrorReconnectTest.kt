package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test

class MirrorReconnectTest {
    private val headphones = HeadUnitSelection.Device("headphones", "TONE-T60Q", true, true)
    private val vehicle = HeadUnitSelection.Device("vehicle", "CFMOTO-cZh31O", true)
    private val otherVehicle = HeadUnitSelection.Device("other", "Car display", true)

    @Test fun restartChoosesCfmotoEvenWhenHeadphonesAdvertiseIapFirst() {
        assertEquals(listOf(vehicle), HeadUnitSelection.candidates(listOf(headphones, vehicle), "", ""))
    }
    @Test fun rememberedWorkingVehicleSurvivesNameAndUuidCacheLoss() {
        val cached = vehicle.copy(name = null, iap = false)
        assertEquals(listOf(cached), HeadUnitSelection.candidates(listOf(headphones, cached), "", "vehicle"))
    }
    @Test fun explicitVehicleOverridesRememberedVehicle() {
        assertEquals(listOf(otherVehicle), HeadUnitSelection.candidates(listOf(vehicle, otherVehicle), "other", "vehicle"))
    }
    @Test fun missingExplicitVehicleDoesNotFallBackToHeadphones() {
        assertTrue(HeadUnitSelection.candidates(listOf(headphones), "vehicle", "vehicle").isEmpty())
    }
    @Test fun forgettingOldVehicleAllowsAnotherCfmoto() {
        assertEquals(listOf(vehicle), HeadUnitSelection.candidates(listOf(headphones, vehicle), "auto", "forgotten"))
    }
    @Test fun headphonesAloneAreNotTreatedAsACarplayVehicle() {
        assertTrue(HeadUnitSelection.candidates(listOf(headphones), "", "").isEmpty())
    }
    @Test fun failedResetAndRepeatedRetryCannotCycleBluetoothAgain() {
        val recovery = LinkRecovery(1)
        assertTrue(recovery.takeReset())
        repeat(10) { assertFalse(recovery.takeReset()) }
    }
    @Test fun manualBluetoothToggleConsumesRecoveryWithoutAnotherReset() {
        val recovery = LinkRecovery(1)
        recovery.adapterRestored()
        assertFalse(recovery.takeReset())
    }
    @Test fun restoredAdapterStopsFurtherAutomaticCycles() {
        val recovery = LinkRecovery(2)
        assertTrue(recovery.takeReset())
        recovery.adapterRestored()
        assertFalse(recovery.takeReset())
    }
    @Test fun workingSessionAllowsRecoveryFromALaterDrop() {
        val recovery = LinkRecovery(1)
        assertTrue(recovery.takeReset())
        assertFalse(recovery.takeReset())
        recovery.streamed()
        assertTrue(recovery.takeReset())
    }
}
