package dev.dublinbustracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HeadingTest {
    // A road running due east, then turning due north.
    private val path = listOf(LatLon(53.0, -6.00), LatLon(53.0, -5.99), LatLon(53.0, -5.98), LatLon(53.01, -5.98))

    @Test
    fun `a bus on a stretch of path faces along it`() {
        assertEquals(90f, headingAlongPath(path, LatLon(53.0, -5.9951))!!, 1f)
        assertEquals(0f, headingAlongPath(path, LatLon(53.005, -5.98))!!, 1f)
    }

    @Test
    fun `at the very end it keeps facing the way it came`() {
        assertNotNull(headingAlongPath(path, LatLon(53.01, -5.98)))
        assertEquals(0f, headingAlongPath(path, LatLon(53.01, -5.98))!!, 1f)
    }

    @Test
    fun `a bus nowhere near the path, or no path at all, has no heading`() {
        assertNull(headingAlongPath(path, LatLon(53.2, -6.5)))
        assertNull(headingAlongPath(emptyList(), LatLon(53.0, -6.0)))
        assertNull(headingAlongPath(listOf(LatLon(53.0, -6.0)), LatLon(53.0, -6.0)))
    }
}
