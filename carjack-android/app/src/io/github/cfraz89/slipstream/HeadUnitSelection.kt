package io.github.cfraz89.slipstream

/** iAP is also advertised by headphones: it alone does not identify the user's vehicle. */
object HeadUnitSelection {
    data class Device(val address: String, val name: String?, val iap: Boolean, val headphones: Boolean = false)

    fun candidates(devices: List<Device>, configured: String, verified: String): List<Device> {
        val target = configured.trim().takeUnless { it.equals("auto", true) }.orEmpty()
        if (target.isNotEmpty()) return devices.filter {
            it.address.equals(target, true) || it.name == target
        }
        devices.firstOrNull { it.address.equals(verified, true) }?.let { return listOf(it) }
        val cfmoto = devices.filter { it.name?.startsWith("CFMOTO", true) == true }
        if (cfmoto.isNotEmpty()) return cfmoto
        return devices.filter { it.iap && !it.headphones }
    }
}
