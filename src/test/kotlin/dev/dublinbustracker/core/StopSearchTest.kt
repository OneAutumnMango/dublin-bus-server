package dev.dublinbustracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopSearchTest {
    private val trinity = Stop("s1", "1001", "Trinity College", 53.3441, -6.2578)
    private val heuston = Stop("s2", "1002", "Heuston Station", 53.3468, -6.2951)
    private val clareHall = Stop("s3", "7788", "Clare Hall", 53.4000, -6.1700)
    private val stops = listOf(trinity, heuston, clareHall)

    @Test
    fun `matches by name, case-insensitive`() {
        assertEquals(listOf(trinity), StopSearch.search(stops, "trinity"))
    }

    @Test
    fun `matches by stop code prefix`() {
        assertEquals(listOf(heuston), StopSearch.search(stops, "1002"))
    }

    @Test
    fun `blank query returns nothing`() {
        assertEquals(emptyList(), StopSearch.search(stops, "   "))
    }

    @Test
    fun `radius search excludes stops beyond the radius`() {
        val within = StopSearch.stopsWithinRadius(53.3441, -6.2578, stops, radiusMeters = 3000.0, limit = 10)
        assertEquals(listOf(trinity, heuston), within)
    }

    @Test
    fun `radius search respects the limit`() {
        val within = StopSearch.stopsWithinRadius(53.3441, -6.2578, stops, radiusMeters = 100_000.0, limit = 1)
        assertEquals(listOf(trinity), within)
    }

    @Test
    fun `haversine matches a known Dublin distance`() {
        // Trinity College to Heuston Station is roughly 2.5km.
        val meters = haversineMeters(53.3441, -6.2578, 53.3468, -6.2951)
        assertTrue(meters in 2_000.0..3_200.0, "unexpected distance: $meters")
    }
}
