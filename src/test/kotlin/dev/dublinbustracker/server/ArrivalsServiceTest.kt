package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- scheduled fallback (no realtime update yet) ---------------------------

    // Wednesday, and Dublin runs no DST in January, so this is also UTC+0 local time.
    private val wednesdayMorning = Instant.parse("2024-01-10T08:00:00Z").epochSecond
    private val secondsSinceDublinMidnight = 8 * 3600 // matches wednesdayMorning, 08:00

    private fun bundleWithSchedule(weekdays: String, scheduledSeconds: Int): String =
        """{"version":3,
           |"routes":[{"id":"r1","shortName":"15","longName":"","agencyId":"a1"}],
           |"trips":{"t1":{"routeId":"r1","headsign":"Merrion Square","directionId":"0","shapeId":"","serviceId":"svc"}},
           |"services":{"svc":{"weekdays":"$weekdays","start":"20240101","end":"20241231"}},
           |"stopSchedule":{"s1":[{"t":"t1","s":$scheduledSeconds}]}}
        """.trimMargin()

    private fun scheduledService(bundleJson: String, now: Long = wednesdayMorning): ArrivalsService =
        ArrivalsService(FeedStore(historyDepth = 5), StaticDataRef(GtfsStaticData.parse(bundleJson)), nowEpochSeconds = { now })

    @Test
    fun `a trip with no realtime update falls back to its scheduled time`() {
        val svc = scheduledService(bundleWithSchedule("1111100", secondsSinceDublinMidnight + 10 * 60))

        val arrival = svc.arrivalsForStop("s1").single()

        assertEquals("t1", arrival.tripId)
        assertFalse(arrival.isRealtime)
        assertEquals(10, arrival.dueMinutes)
        assertEquals("15", arrival.routeShortName)
        assertEquals("Merrion Square", arrival.destination)
    }

    @Test
    fun `a realtime update for the same trip suppresses its scheduled fallback`() {
        val store = FeedStore(historyDepth = 5)
        val staticData = StaticDataRef(GtfsStaticData.parse(bundleWithSchedule("1111100", secondsSinceDublinMidnight + 600)))
        val svc = ArrivalsService(store, staticData, nowEpochSeconds = { wednesdayMorning })
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", wednesdayMorning + 300)),
                ),
            ),
        )

        val arrivals = svc.arrivalsForStop("s1")

        assertEquals(1, arrivals.size)
        assertTrue(arrivals.single().isRealtime)
    }

    @Test
    fun `a scheduled call for a service not running today is excluded`() {
        // "0000011" is Saturday and Sunday only; the fixed "now" above is a Wednesday.
        val svc = scheduledService(bundleWithSchedule("0000011", secondsSinceDublinMidnight + 600))

        assertEquals(emptyList(), svc.arrivalsForStop("s1"))
    }

    @Test
    fun `a scheduled call beyond the lookahead window is excluded`() {
        val svc = scheduledService(bundleWithSchedule("1111100", secondsSinceDublinMidnight + 100 * 60))

        assertEquals(emptyList(), svc.arrivalsForStop("s1"))
    }

    @Test
    fun `a scheduled call just past is still shown within the grace window`() {
        val svc = scheduledService(bundleWithSchedule("1111100", secondsSinceDublinMidnight - 60))

        assertEquals(0, svc.arrivalsForStop("s1").single().dueMinutes)
    }

    @Test
    fun `a scheduled call well past the grace window is excluded`() {
        val svc = scheduledService(bundleWithSchedule("1111100", secondsSinceDublinMidnight - 5 * 60))

        assertEquals(emptyList(), svc.arrivalsForStop("s1"))
    }

    @Test
    fun `scheduled fallback honours the route filter`() {
        val svc = scheduledService(bundleWithSchedule("1111100", secondsSinceDublinMidnight + 600))

        assertEquals(1, svc.arrivalsForStop("s1", routeFilter = "15").size)
        assertEquals(emptyList(), svc.arrivalsForStop("s1", routeFilter = "999"))
    }
}
