package io.github.cfraz89.slipstream

/** Saved authorization wins over toggles that Android can turn off after pairing. */
object MirrorSetup {
    enum class Step { ENABLE_DEVELOPER, ENABLE_WIRELESS, PAIR, READY }
    fun step(paired: Boolean, developer: Boolean, wireless: Boolean) = when {
        paired -> Step.READY
        !developer -> Step.ENABLE_DEVELOPER
        !wireless -> Step.ENABLE_WIRELESS
        else -> Step.PAIR
    }
}
