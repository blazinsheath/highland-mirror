package io.github.cfraz89.slipstream

import org.junit.Assert.*
import org.junit.Test

class MirrorGeometryTest {
    @Test fun portraitPhoneHasSideMargins() {
        val middle = MirrorGeometry.point(640f, 360f, 1280, 720, 1080, 2340)!!
        assertEquals(540f, middle.x, 0.01f)
        assertEquals(1170f, middle.y, 0.01f)
        assertNull(MirrorGeometry.point(100f, 360f, 1280, 720, 1080, 2340))
        assertNull(MirrorGeometry.point(1180f, 360f, 1280, 720, 1080, 2340))
    }
    @Test fun landscapeRotationHasTopMargins() {
        val middle = MirrorGeometry.point(640f, 360f, 1280, 720, 2340, 1080)!!
        assertEquals(1170f, middle.x, 0.01f)
        assertEquals(540f, middle.y, 0.01f)
        assertNull(MirrorGeometry.point(640f, 10f, 1280, 720, 2340, 1080))
    }
    @Test fun rejectsOutsideOrInvalidGeometry() {
        assertNull(MirrorGeometry.point(-1f, 100f, 1280, 720, 1280, 720))
        assertNull(MirrorGeometry.point(1280f, 720f, 1280, 720, 1280, 720))
        assertNull(MirrorGeometry.point(Float.NaN, 100f, 1280, 720, 1280, 720))
        assertNull(MirrorGeometry.point(1f, 1f, 0, 0, 1080, 2340))
    }
    @Test fun acceptsOnlyUsableUnicastMacs() {
        assertEquals("A0:B1:C2:D3:E4:F5", MirrorGeometry.mac(" a0:b1:c2:d3:e4:f5 "))
        for (bad in listOf("", "phone", "02:00:00:00:00:00", "00:00:00:00:00:00",
                           "FF:FF:FF:FF:FF:FF", "A0:B1:C2:D3:E4")) assertNull(MirrorGeometry.mac(bad))
    }
}
