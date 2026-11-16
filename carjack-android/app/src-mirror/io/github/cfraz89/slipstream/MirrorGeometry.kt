package io.github.cfraz89.slipstream

/** Map only the letterboxed full-phone image, never the head unit's margins or controls. */
object MirrorGeometry {
    data class Point(val x: Float, val y: Float)

    fun point(x: Float, y: Float, viewWidth: Int, viewHeight: Int,
              phoneWidth: Int, phoneHeight: Int): Point? {
        if (minOf(viewWidth, viewHeight, phoneWidth, phoneHeight) <= 0 ||
            !x.isFinite() || !y.isFinite()) return null
        val scale = minOf(viewWidth.toFloat() / phoneWidth, viewHeight.toFloat() / phoneHeight)
        val left = (viewWidth - phoneWidth * scale) / 2
        val top = (viewHeight - phoneHeight * scale) / 2
        val px = (x - left) / scale
        val py = (y - top) / scale
        if (px < 0 || py < 0 || px >= phoneWidth || py >= phoneHeight) return null
        return Point(px, py)
    }

    fun mac(value: String): String? {
        val address = value.trim().uppercase(java.util.Locale.ROOT)
        if (!Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(address)) return null
        if (address == "00:00:00:00:00:00" || address == "02:00:00:00:00:00" ||
            (address.substring(0, 2).toInt(16) and 1) != 0) return null
        return address
    }
}
