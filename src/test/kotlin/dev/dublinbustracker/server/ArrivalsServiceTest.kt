package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrivalsServiceTest {
    private val staticData = StaticDataRef(GtfsStaticData.parse(Fixtures.staticBundleJson()))
    private val store = FeedStore(historyDepth = 5)
    private val service = ArrivalsService(store, staticData, nowEpochSeconds = { 1_000L })

    private fun recordTripUpdates() {
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    entityId = "e1",
                    tripId = "t1",
                    vehicleId = "bus-1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", epochSeconds = 1_600, stopSequence = 2, delaySeconds = 120),
                        Fixtures.stopTimeUpdate("s2", epochSeconds = 1_900, stopSequence = 3),
                    ),
                ),
                Fixtures.tripUpdateEntity(
                    entityId = "e2",
                    tripId = "t2",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", epochSeconds = 1_120, stopSequence = 1),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `arrivals for a stop are sorted by expected time and enriched from static data`() {
        recordTripUpdates()

        val arrivals = service.arrivalsForStop("s1")

        assertEquals(2, arrivals.size)
        assertEquals(listOf("27", "15"), arrivals.map { it.routeShortName })
        assertEquals(listOf(2, 10), arrivals.map { it.dueMinutes })
        assertEquals("Clare Hall", arrivals[0].destination)
        assertTrue(arrivals.all { it.isRealtime })
    }

    @Test
    fun `arrival carries trip, vehicle and delay details`() {
        recordTripUpdates()

        val arrival = service.arrivalsForStop("s1").single { it.tripId == "t1" }

        assertEquals("bus-1", arrival.vehicleId)
        assertEquals(120, arrival.delaySeconds)
        assertEquals("r1", arrival.routeId)
        assertEquals(1_600, arrival.expectedEpochSeconds)
        assertEquals("s1", arrival.stopId)
    }

    @Test
    fun `arrivals can be filtered by bus number`() {
        recordTripUpdates()

        val arrivals = service.arrivalsForStop("s1", routeFilter = "15")

        assertEquals(listOf("t1"), arrivals.map { it.tripId })
    }

    @Test
    fun `arrivals can be filtered by route id`() {
        recordTripUpdates()

        assertEquals(listOf("t2"), service.arrivalsForStop("s1", routeFilter = "r3").map { it.tripId })
    }

    @Test
    fun `filtering by an unknown route returns nothing`() {
        recordTripUpdates()

        assertEquals(emptyList(), service.arrivalsForStop("s1", routeFilter = "999"))
    }

    @Test
    fun `the limit caps how many arrivals are returned`() {
        recordTripUpdates()

        assertEquals(1, service.arrivalsForStop("s1", limit = 1).size)
    }

    @Test
    fun `a stop with no updates returns nothing`() {
        recordTripUpdates()

        assertEquals(emptyList(), service.arrivalsForStop("s3"))
    }

    @Test
    fun `with no feed recorded there are no arrivals`() {
        assertEquals(emptyList(), service.arrivalsForStop("s1"))
    }

    @Test
    fun `departure time is used when no arrival time is present`() {
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    entityId = "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", epochSeconds = 1_300, useDeparture = true),
                    ),
                ),
            ),
        )

        assertEquals(5, service.arrivalsForStop("s1").single().dueMinutes)
    }

    @Test
    fun `times already in the past clamp to zero rather than going negative`() {
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    entityId = "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 100)),
                ),
            ),
        )

        assertEquals(0, service.arrivalsForStop("s1").single().dueMinutes)
    }

    @Test
    fun `trip stop calls are ordered by stop sequence and named from static data`() {
        recordTripUpdates()

        val calls = service.tripStopCalls("t1")

        assertEquals(listOf(2, 3), calls.map { it.stopSequence })
        assertEquals(listOf("Trinity College", "Heuston Station"), calls.map { it.stopName })
        assertEquals(120, calls.first().delaySeconds)
    }

    @Test
    fun `trip stop calls for an unknown trip are empty`() {
        recordTripUpdates()

        assertEquals(emptyList(), service.tripStopCalls("nope"))
    }

    @Test
    fun `active trip ids can be listed and filtered by route`() {
        recordTripUpdates()

        assertEquals(setOf("t1", "t2"), service.activeTripIds().toSet())
        assertEquals(listOf("t1"), service.activeTripIds(routeFilter = "15"))
    }

    @Test
    fun `the combined feed is read when trip updates are not polled separately`() {
        store.record(
            FeedKind.COMBINED,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    entityId = "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 1_600)),
                ),
            ),
        )

        assertEquals(1, service.arrivalsForStop("s1").size)
        assertNull(store.latest(FeedKind.TRIP_UPDATES))
    }
}
