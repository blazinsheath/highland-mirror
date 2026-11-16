package io.github.cfraz89.slipstream

/** Handler-owned recovery budget. Retry never replenishes it; a working session does. */
class LinkRecovery(private val maximum: Int) {
    var remaining = maximum
        private set
    fun takeReset(): Boolean {
        if (remaining <= 0) return false
        remaining--
        return true
    }
    fun adapterRestored() { remaining = 0 }
    fun streamed() { remaining = maximum }
}
