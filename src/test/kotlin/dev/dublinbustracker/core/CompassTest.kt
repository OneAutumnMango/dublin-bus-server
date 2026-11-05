package dev.dublinbustracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompassTest {
    private val origin = LatLon(53.30, -6.30)

    @Test
    fun `due north, east, south and west`() {
        assertEquals(0.0, bearingDegrees(origin, LatLon(53.31, -6.30)), 0.5)
        assertEquals(90.0, bearingDegrees(origin, LatLon(53.30, -6.29)), 0.5)
        assertEquals(180.0, bearingDegrees(origin, LatLon(53.29, -6.30)), 0.5)
        assertEquals(270.0, bearingDegrees(origin, LatLon(53.30, -6.31)), 0.5)
    }

    @Test
    fun `the opposite way is 180 degrees round`() {
        val there = LatLon(53.31, -6.28)

        val out = bearingDegrees(origin, there)
        val back = bearingDegrees(there, origin)

        assertEquals(180.0, (back - out + 360.0) % 360.0, 0.5)
    }

    @Test
    fun `bearings are always within a turn`() {
        val points = listOf(LatLon(53.31, -6.31), LatLon(53.29, -6.29), LatLon(53.30, -6.35), LatLon(53.4, -6.2))

        points.forEach { assertTrue(bearingDegrees(origin, it) in 0.0..360.0) }
    }

    @Test
    fun `bearings become the nearest of eight points`() {
        assertEquals("N", compassPoint(0.0))
        assertEquals("N", compassPoint(22.4))
        assertEquals("NE", compassPoint(22.6))
        assertEquals("E", compassPoint(90.0))
        assertEquals("SE", compassPoint(135.0))
        assertEquals("S", compassPoint(180.0))
        assertEquals("SW", compassPoint(225.0))
        assertEquals("W", compassPoint(270.0))
        assertEquals("NW", compassPoint(315.0))
        assertEquals("N", compassPoint(350.0)) // wraps round to the top
    }

    @Test
    fun `out-of-range bearings are normalised`() {
        assertEquals("E", compassPoint(450.0))
        assertEquals("W", compassPoint(-90.0))
    }
}
