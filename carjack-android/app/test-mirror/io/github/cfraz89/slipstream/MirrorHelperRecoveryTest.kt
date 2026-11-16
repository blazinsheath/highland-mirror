package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test
import io.github.cfraz89.slipstream.MirrorHelperRecovery.Action
import io.github.cfraz89.slipstream.MirrorHelperRecovery.Phase

class MirrorHelperRecoveryTest {
    @Test fun savedPairingSurvivesDebuggingDiscoveryAndConnectionFailures() {
        for (phase in listOf(Phase.DISCOVERY, Phase.CONNECT)) {
            assertEquals(Action.CONNECT, MirrorHelperRecovery.action(true, phase))
            assertTrue(MirrorHelperRecovery.message(true, phase).startsWith("Pairing is saved."))
        }
    }
    @Test fun helperStartupFailureDoesNotRequestAnotherPairingCode() {
        for (phase in listOf(Phase.LAUNCH, Phase.LINK, Phase.PHONE_ID, Phase.IDENTITY))
            assertEquals(Action.RETRY, MirrorHelperRecovery.action(true, phase))
    }
    @Test fun onlyMissingOrRejectedAuthorizationRequestsPairing() {
        assertEquals(Action.PAIR, MirrorHelperRecovery.action(false, Phase.DISCOVERY))
        assertEquals(Action.PAIR, MirrorHelperRecovery.action(true, Phase.CONNECT, authRejected = true))
    }
    @Test fun failedExplicitPairingAttemptDoesNotDiscardExistingAuthorization() {
        assertEquals(Action.RETRY, MirrorHelperRecovery.action(true, Phase.PAIR))
        assertEquals(Action.PAIR, MirrorHelperRecovery.action(false, Phase.PAIR))
    }
}
