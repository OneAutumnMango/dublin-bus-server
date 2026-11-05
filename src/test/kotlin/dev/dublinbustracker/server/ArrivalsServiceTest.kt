package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.TripUpdate
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

    // --- cascading a live trip's delay to a stop it hasn't reported yet --------

    // t1 is scheduled at s1 at "now", and 10 minutes later at s2 - used to verify a delay
    // reported live at s1 carries forward to a cascaded estimate at s2.
    private fun twoStopBundle(): String =
        """{"version":3,
           |"routes":[{"id":"r1","shortName":"15","longName":"","agencyId":"a1"}],
           |"trips":{"t1":{"routeId":"r1","headsign":"Merrion Square","directionId":"0","shapeId":"","serviceId":"svc"}},
           |"services":{"svc":{"weekdays":"1111111","start":"20240101","end":"20241231"}},
           |"stopSchedule":{
           |  "s1":[{"t":"t1","s":$secondsSinceDublinMidnight}],
           |  "s2":[{"t":"t1","s":${secondsSinceDublinMidnight + 600}}]
           |}}
        """.trimMargin()

    private fun twoStopService(): Pair<FeedStore, ArrivalsService> {
        val store = FeedStore(historyDepth = 5)
        val staticData = StaticDataRef(GtfsStaticData.parse(twoStopBundle()))
        return store to ArrivalsService(store, staticData, nowEpochSeconds = { wednesdayMorning })
    }

    @Test
    fun `a trip reported live at one stop cascades its delay to another stop's schedule`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", wednesdayMorning + 180, delaySeconds = 180),
                    ),
                ),
            ),
        )

        val arrival = svc.arrivalsForStop("s2").single()

        assertTrue(arrival.isRealtime)
        assertEquals(180, arrival.delaySeconds)
        // s2 is scheduled 10 min after s1, plus the 3 min delay reported at s1.
        assertEquals(13, arrival.dueMinutes)
    }

    @Test
    fun `cascading derives the delay from schedule when the feed reports none explicitly`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    // No delaySeconds given: 120s later than s1's own scheduled time.
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", wednesdayMorning + 120)),
                ),
            ),
        )

        val arrival = svc.arrivalsForStop("s2").single()

        assertEquals(120, arrival.delaySeconds)
        assertEquals(12, arrival.dueMinutes)
    }

    @Test
    fun `a direct report for the queried stop takes priority over a cascade`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", wednesdayMorning + 100),
                        Fixtures.stopTimeUpdate("s2", wednesdayMorning + 500),
                    ),
                ),
            ),
        )

        val arrivals = svc.arrivalsForStop("s2")

        assertEquals(1, arrivals.size)
        assertEquals(wednesdayMorning + 500, arrivals.single().expectedEpochSeconds)
    }

    // The spec lets a feed send only a delay (no absolute time) - the lateness must still count.
    private fun delayOnlyUpdate(stopId: String, delaySeconds: Int): TripUpdate.StopTimeUpdate =
        TripUpdate.StopTimeUpdate.newBuilder()
            .setStopId(stopId)
            .setArrival(TripUpdate.StopTimeEvent.newBuilder().setDelay(delaySeconds))
            .build()

    @Test
    fun `a delay-only update is turned into a time using the trip's own schedule`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = listOf(delayOnlyUpdate("s1", 180)))),
        )

        val arrival = svc.arrivalsForStop("s1").single()

        assertTrue(arrival.isRealtime)
        assertEquals(180, arrival.delaySeconds)
        assertEquals(wednesdayMorning + 180, arrival.expectedEpochSeconds)
    }

    @Test
    fun `a delay-only update still cascades its lateness to a later stop`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = listOf(delayOnlyUpdate("s1", 180)))),
        )

        val arrival = svc.arrivalsForStop("s2").single()

        assertTrue(arrival.isRealtime)
        assertEquals(13, arrival.dueMinutes) // s2 is 10 min after s1, plus the 3 min delay
    }

    // --- stop ordering and trips that call at a stop twice ---------------------

    /** One t1 bundle from `stop to offset-from-now-in-seconds` calls; a stop may repeat (a loop). */
    private fun tripCalls(vararg calls: Pair<String, Int>): ArrivalsService {
        val schedule = calls.groupBy({ it.first }, { it.second }).entries.joinToString(",") { (stop, offsets) ->
            "\"$stop\":[" + offsets.joinToString(",") { """{"t":"t1","s":${secondsSinceDublinMidnight + it}}""" } + "]"
        }
        val bundle = """{"version":3,
            |"routes":[{"id":"r1","shortName":"15","longName":"","agencyId":"a1"}],
            |"trips":{"t1":{"routeId":"r1","headsign":"Merrion Square","directionId":"0","shapeId":"","serviceId":"svc"}},
            |"services":{"svc":{"weekdays":"1111111","start":"20240101","end":"20241231"}},
            |"stopSchedule":{$schedule}}
        """.trimMargin()
        val store = FeedStore(historyDepth = 5)
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    // The bus is at "mid", 3 minutes late.
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("mid", wednesdayMorning + 180, delaySeconds = 180)),
                ),
            ),
        )
        return ArrivalsService(store, StaticDataRef(GtfsStaticData.parse(bundle)), nowEpochSeconds = { wednesdayMorning })
    }

    @Test
    fun `a stop the bus has already passed is not given the bus's delay`() {
        // "behind" was scheduled a minute ago; without an ordering check the cascade would
        // report it as due now, and the timetable fallback would too.
        val svc = tripCalls("behind" to -60, "mid" to 0, "ahead" to 600)

        assertEquals(emptyList(), svc.arrivalsForStop("behind"))
        assertEquals(13, svc.arrivalsForStop("ahead").single().dueMinutes)
    }

    @Test
    fun `a loop trip's later visit to a stop is cascaded and its earlier visit is not`() {
        // Visits "loop" at -10 min (already passed) and +20 min (still to come).
        val svc = tripCalls("loop" to -600, "mid" to 0, "loop" to 1200)

        val arrival = svc.arrivalsForStop("loop").single()

        assertTrue(arrival.isRealtime)
        assertEquals(wednesdayMorning + 1200 + 180, arrival.expectedEpochSeconds)
    }

    @Test
    fun `a loop trip with two visits still to come shows both`() {
        val svc = tripCalls("mid" to 0, "loop" to 300, "loop" to 1800)

        val arrivals = svc.arrivalsForStop("loop")

        assertEquals(listOf(wednesdayMorning + 480, wednesdayMorning + 1980), arrivals.map { it.expectedEpochSeconds })
        assertTrue(arrivals.all { it.isRealtime })
    }

    @Test
    fun `a delay reported at a stop missing from the timetable still cascades`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("unscheduled", wednesdayMorning + 120, delaySeconds = 120)),
                ),
            ),
        )

        assertEquals(12, svc.arrivalsForStop("s2").single().dueMinutes)
    }

    // --- cancelled trips and skipped stops -------------------------------------

    private val skipped = TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
    private val noData = TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA

    @Test
    fun `a cancelled trip shows nowhere, not even as a scheduled fallback`() {
        val (store, svc) = twoStopService()
        // Cancellations commonly arrive with no stop_time_updates at all.
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(Fixtures.tripUpdateEntity("e1", tripId = "t1", cancelled = true)),
        )

        assertEquals(emptyList(), svc.arrivalsForStop("s1"))
        assertEquals(emptyList(), svc.arrivalsForStop("s2"))
        assertEquals(emptyList(), svc.activeTripIds())
        assertEquals(emptyList(), svc.tripStopCalls("t1"))
    }

    @Test
    fun `a cancelled trip's own stop times are ignored too`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    cancelled = true,
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", wednesdayMorning + 60)),
                ),
            ),
        )

        assertEquals(emptyList(), svc.arrivalsForStop("s1"))
        assertEquals(emptyList(), svc.arrivalsForStop("s2"))
    }

    @Test
    fun `a stop the trip skips shows no arrival, cascaded or scheduled`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", wednesdayMorning + 60, relationship = skipped),
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), svc.arrivalsForStop("s1"))
    }

    @Test
    fun `skipping one stop does not hide the trip at a stop it still serves`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", wednesdayMorning + 60, relationship = skipped),
                        Fixtures.stopTimeUpdate("s2", wednesdayMorning + 700),
                    ),
                ),
            ),
        )

        val arrival = svc.arrivalsForStop("s2").single()

        assertTrue(arrival.isRealtime)
        assertEquals(wednesdayMorning + 700, arrival.expectedEpochSeconds)
    }

    @Test
    fun `a no-data stop time update is not used as a prediction`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", wednesdayMorning + 999, relationship = noData),
                    ),
                ),
            ),
        )

        // No usable prediction, so s1 is left to the untouched timetable.
        val arrival = svc.arrivalsForStop("s1").single()
        assertFalse(arrival.isRealtime)
        assertEquals(wednesdayMorning, arrival.expectedEpochSeconds)
    }
}
