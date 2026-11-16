package io.github.cfraz89.slipstream

/** Pairing authorizes a key; finding adbd and starting the helper are separate, retryable steps. */
object MirrorHelperRecovery {
    enum class Phase { IDENTITY, DISCOVERY, CONNECT, LAUNCH, LINK, PHONE_ID, PAIR }
    enum class Action { PAIR, CONNECT, RETRY }
    fun action(paired: Boolean, phase: Phase, authRejected: Boolean = false): Action = when {
        authRejected -> Action.PAIR
        phase == Phase.PAIR -> if (paired) Action.RETRY else Action.PAIR
        phase == Phase.DISCOVERY || phase == Phase.CONNECT -> if (paired) Action.CONNECT else Action.PAIR
        phase == Phase.IDENTITY || phase == Phase.LAUNCH || phase == Phase.LINK || phase == Phase.PHONE_ID -> Action.RETRY
        else -> Action.RETRY
    }
    fun message(paired: Boolean, phase: Phase, authRejected: Boolean = false): String = when {
        authRejected -> "Android rejected this app’s saved pairing. Pair the built-in helper again."
        phase == Phase.DISCOVERY -> if (paired)
            "Pairing is saved. Could not find the debugging connection. Keep Wireless debugging on and retry, or enter its connection port."
            else "Enable Wireless debugging and pair the built-in helper once."
        phase == Phase.CONNECT -> if (paired)
            "Pairing is saved. Could not connect to Wireless debugging. Retry or enter its current connection port."
            else "Could not connect to Wireless debugging. Enable it and pair once if needed."
        phase == Phase.LAUNCH || phase == Phase.LINK -> "Pairing is saved. The built-in helper did not start. Retry; no new pairing code is needed."
        phase == Phase.PHONE_ID -> "The helper started but could not read phone identity. Enable Bluetooth and retry."
        phase == Phase.IDENTITY -> "The app could not load its saved helper identity. Share the diagnostic log."
        else -> if (paired) "Pairing is already saved. Retry starting the helper."
            else "Pairing did not finish. Keep Android’s pairing dialog open and enter its current code."
    }
}
