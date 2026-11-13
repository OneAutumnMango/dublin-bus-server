package dev.dublinbustracker.server

import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleServiceTest {
    private val staticData = StaticDataRef(GtfsStaticData.parse(Fixtures.staticBundleJson()))
    private val store = FeedStore(historyDepth = 5)
    private val service = VehicleService(store, staticData)

    private fun recordVehicles() {
        store.record(
            FeedKind.VEHICLES,
            Fixtures.feedOf(
                Fixtures.vehicleEntity(
                    entityId = "e1",
                    vehicleId = "bus-1",
                    tripId = "t1",
                    lat = 53.3441,
                    lon = -6.2578,
                    timestampEpochSeconds = 1_000,
                    bearing = 90f,
                    speed = 5.5f,
                    label = "DB-1",
                    currentStopId = "s1",
                ),
                Fixtures.vehicleEntity(
                    entityId = "e2",
                    vehicleId = "bus-2",
                    tripId = "t2",
                    lat = 53.4000,
                    lon = -6.1700,
                    timestampEpochSeconds = 1_000,
                ),
            ),
        )
    }

    @Test
    fun `live vehicles are enriched with route and headsign from static data`() {
        recordVehicles()

        val vehicle = service.vehicleById("bus-1")

        assertNotNull(vehicle)
        assertEquals("15", vehicle.routeShortName)
        assertEquals("r1", vehicle.routeId)
        assertEquals("Merrion Square", vehicle.headsign)
        assertEquals("DB-1", vehicle.label)
        assertEquals(90f, vehicle.bearing)
        assertEquals(5.5f, vehicle.speed)
        assertEquals("s1", vehicle.currentStopId)
    }

    @Test
    fun `all vehicles are returned when no filter is applied`() {
        recordVehicles()

        assertEquals(setOf("bus-1", "bus-2"), service.allVehicles().map { it.vehicleId }.toSet())
    }

    @Test
    fun `vehicles can be filtered to a bus number`() {
        recordVehicles()

        assertEquals(listOf("bus-1"), service.vehiclesForRoute("15").map { it.vehicleId })
        assertEquals(listOf("bus-2"), service.vehiclesForRoute("27").map { it.vehicleId })
    }

    @Test
    fun `the fresh-trip set is built once per snapshot`() {
        recordVehicles()

        assertTrue(service.hasFreshFix("t1", now = 1_100))
        assertTrue(service.hasFreshFix("t2", now = 1_100))
        assertEquals(1, service.fixIndexBuilds)

        recordVehicles()
        service.hasFreshFix("t1", now = 1_100)
        assertEquals(2, service.fixIndexBuilds)
    }

    @Test
    fun `an unknown route has no vehicles`() {
        recordVehicles()

        assertEquals(emptyList(), service.vehiclesForRoute("999"))
    }

    @Test
    fun `a vehicle can be found by the trip it is running`() {
        recordVehicles()

        assertEquals("bus-2", service.vehicleForTrip("t2")?.vehicleId)
        assertNull(service.vehicleForTrip("t3"))
    }

    @Test
    fun `history reconstructs a track from the retained backlog, oldest first`() {
        listOf(
            Triple(1_000L, 53.3441, -6.2578),
            Triple(1_030L, 53.3450, -6.2600),
            Triple(1_060L, 53.3468, -6.2951),
        ).forEach { (timestamp, lat, lon) ->
            store.record(
                FeedKind.VEHICLES,
                Fixtures.feedOf(
                    Fixtures.vehicleEntity("e1", "bus-1", "t1", lat, lon, timestamp),
                ),
            )
        }

        val fixes = service.vehicleHistory("bus-1")

        assertEquals(listOf(1_000L, 1_030L, 1_060L), fixes.map { it.timestampEpochSeconds })
        assertTrue(kotlin.math.abs(fixes.first().position.lat - 53.3441) < 1e-4)
    }

    @Test
    fun `repeated snapshots of an unmoved vehicle collapse to one fix per timestamp`() {
        repeat(3) {
            store.record(
                FeedKind.VEHICLES,
                Fixtures.feedOf(Fixtures.vehicleEntity("e1", "bus-1", "t1", 53.3441, -6.2578, 1_000)),
            )
        }

        assertEquals(1, service.vehicleHistory("bus-1").size)
    }

    @Test
    fun `history is bounded by the configured backlog depth`() {
        val shallowStore = FeedStore(historyDepth = 2)
        val shallowService = VehicleService(shallowStore, staticData)
        (1L..5L).forEach { tick ->
            shallowStore.record(
                FeedKind.VEHICLES,
                Fixtures.feedOf(Fixtures.vehicleEntity("e1", "bus-1", "t1", 53.0 + tick, -6.0, tick)),
            )
        }

        assertEquals(listOf(4L, 5L), shallowService.vehicleHistory("bus-1").map { it.timestampEpochSeconds })
    }

    @Test
    fun `an unknown vehicle has no position or history`() {
        recordVehicles()

        assertNull(service.vehicleById("ghost"))
        assertEquals(emptyList(), service.vehicleHistory("ghost"))
    }

    @Test
    fun `with no feed recorded there are no vehicles`() {
        assertEquals(emptyList(), service.allVehicles())
    }

    @Test
    fun `bounding box parses and rejects malformed input`() {
        assertEquals(BoundingBox(1.0, 2.0, 3.0, 4.0), BoundingBox.parse("1,2,3,4"))
        assertNull(BoundingBox.parse("1,2,3"))
        assertNull(BoundingBox.parse("a,b,c,d"))
        assertNull(BoundingBox.parse("3,2,1,4"))
    }

    @Test
    fun `bounding box containment is inclusive of its edges`() {
        val box = BoundingBox(53.0, -6.5, 54.0, -6.0)
        assertTrue(box.contains(LatLon(53.0, -6.5)))
        assertTrue(box.contains(LatLon(53.5, -6.25)))
        assertTrue(!box.contains(LatLon(52.9, -6.25)))
    }
}
