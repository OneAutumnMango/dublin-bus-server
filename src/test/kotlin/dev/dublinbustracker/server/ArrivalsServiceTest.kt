package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.TripUpdate
import dev.dublinbustracker.core.Arrival
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
    fun `a time just past clamps to zero rather than going negative`() {
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    entityId = "e1",
                    tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 950)),
                ),
            ),
        )

        assertEquals(0, service.arrivalsForStop("s1").single().dueMinutes)
    }

    @Test
    fun `a time just past is not flagged as a possible ghost`() {
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 950))),
            ),
        )

        assertFalse(service.arrivalsForStop("s1").single().possiblyPassed)
    }

    @Test
    fun `a bus a few minutes past its prediction stays listed but is flagged as possibly passed`() {
        // 200s past: beyond the 2 min grace, well inside the 15 min it stays visible.
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 800))),
            ),
        )

        val arrival = service.arrivalsForStop("s1").single()

        assertTrue(arrival.possiblyPassed)
        assertEquals(0, arrival.dueMinutes)
    }

    @Test
    fun `a stop the bus passed long ago is dropped altogether`() {
        // The feed keeps listing stops a bus has already passed; 950s ago is past the 15 min limit.
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 50))),
            ),
        )

        assertEquals(emptyList(), service.arrivalsForStop("s1"))
    }

    @Test
    fun `possibly passed buses sort after the real ones`() {
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 800))),
                Fixtures.tripUpdateEntity("e2", tripId = "t2", stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", epochSeconds = 1_500))),
            ),
        )

        assertEquals(listOf("t2", "t1"), service.arrivalsForStop("s1").map { it.tripId })
    }

    @Test
    fun `a cascaded estimate that has slipped past is flagged too`() {
        // "mid" was due 10 min ago and "ahead" 7 min ago, on time: the bus should have been through.
        val svc = tripCallsWith(
            listOf(Fixtures.stopTimeUpdate("mid", wednesdayMorning - 600, delaySeconds = 0)),
            "mid" to -600, "ahead" to -420,
        )

        assertTrue(svc.arrivalsForStop("ahead").single().possiblyPassed)
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

    // --- GTFS times on the days the clocks change -------------------------------

    // A call "08:00" is 08:00 local on the service day. Ireland moves to IST at 01:00 UTC on
    // 2024-03-31 and back to GMT at 01:00 UTC on 2024-10-27.
    private fun dueMinutesAt08(now: String): Int =
        scheduledService(bundleWithSchedule("1111111", 8 * 3600), Instant.parse(now).epochSecond)
            .arrivalsForStop("s1").single().dueMinutes

    @Test
    fun `a scheduled time is read correctly on the day the clocks go forward`() {
        // 08:00 IST is 07:00 UTC, so at 06:50 UTC the bus is 10 minutes away.
        assertEquals(10, dueMinutesAt08("2024-03-31T06:50:00Z"))
    }

    @Test
    fun `a scheduled time is read correctly on the day the clocks go back`() {
        // 08:00 GMT is 08:00 UTC, so at 07:50 UTC the bus is 10 minutes away.
        assertEquals(10, dueMinutesAt08("2024-10-27T07:50:00Z"))
    }

    @Test
    fun `an overnight call after midnight still lands on the right day across a clock change`() {
        // 25:30 on Saturday 2024-03-30's service is 01:30 local on the 31st - which doesn't exist
        // (01:00 GMT jumps to 02:00 IST), and noon-minus-12h puts it at 02:30 IST = 01:30 UTC.
        val svc = scheduledService(
            bundleWithSchedule("1111111", 25 * 3600 + 30 * 60),
            Instant.parse("2024-03-31T01:20:00Z").epochSecond,
        )

        assertEquals(10, svc.arrivalsForStop("s1").single().dueMinutes)
    }

    // --- stop ordering and trips that call at a stop twice ---------------------

    /** One t1 bundle from `stop to offset-from-now-in-seconds` calls; a stop may repeat (a loop). */
    private fun tripCalls(vararg calls: Pair<String, Int>): ArrivalsService =
        tripCallsWith(
            // The bus is at "mid", 3 minutes late.
            listOf(Fixtures.stopTimeUpdate("mid", wednesdayMorning + 180, delaySeconds = 180)),
            *calls,
        )

    private fun tripCallsWith(updates: List<TripUpdate.StopTimeUpdate>, vararg calls: Pair<String, Int>): ArrivalsService {
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
                Fixtures.tripUpdateEntity("e1", tripId = "t1", stopTimeUpdates = updates),
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

    // The real feed lists every stop of a trip, including ones the bus is long past.
    @Test
    fun `a bus that has already passed this stop is not shown, even though the feed still lists it`() {
        val svc = tripCallsWith(
            listOf(
                Fixtures.stopTimeUpdate("passed", wednesdayMorning - 1800, delaySeconds = 0),
                Fixtures.stopTimeUpdate("mid", wednesdayMorning + 120, delaySeconds = 120),
            ),
            "passed" to -1800, "mid" to 0,
        )

        assertEquals(emptyList(), svc.arrivalsForStop("passed"))
    }

    @Test
    fun `the cascade uses the delay at the bus's current stop, not an old stale one`() {
        val svc = tripCallsWith(
            listOf(
                Fixtures.stopTimeUpdate("passed", wednesdayMorning - 1800, delaySeconds = 0),
                Fixtures.stopTimeUpdate("mid", wednesdayMorning + 300, delaySeconds = 300),
            ),
            "passed" to -1800, "mid" to 0, "ahead" to 1200,
        )

        val arrival = svc.arrivalsForStop("ahead").single()

        assertEquals(300, arrival.delaySeconds) // from "mid", not the 0 reported at "passed"
        assertEquals(25, arrival.dueMinutes)
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

    // --- scheduledEpochSeconds: the timetable time, independent of any live prediction ---

    @Test
    fun `direct realtime arrival carries scheduledEpochSeconds equal to the timetable call`() {
        val (store, svc) = twoStopService()
        // 100s late at s1, where the timetable says "now"; the delay-less event is matched by time.
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1", tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", wednesdayMorning + 100, delaySeconds = 100)),
                ),
            ),
        )

        val arrival = svc.arrivalsForStop("s1").single()

        assertTrue(arrival.isRealtime)
        assertEquals(wednesdayMorning + 100, arrival.expectedEpochSeconds)
        assertEquals(wednesdayMorning.toLong(), arrival.scheduledEpochSeconds)
    }

    @Test
    fun `direct arrival without a feed delay still finds its timetable call`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1", tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s2", wednesdayMorning + 500)),
                ),
            ),
        )

        val arrival = svc.arrivalsForStop("s2").single()

        assertNull(arrival.delaySeconds)
        assertEquals(wednesdayMorning + 600L, arrival.scheduledEpochSeconds)
    }

    @Test
    fun `cascaded arrival scheduled == timetable epoch (expected - delay)`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1", tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", wednesdayMorning + 180, delaySeconds = 180)),
                ),
            ),
        )

        val arrival = svc.arrivalsForStop("s2").single()

        assertEquals(wednesdayMorning + 600L + 180, arrival.expectedEpochSeconds)
        assertEquals(wednesdayMorning + 600L, arrival.scheduledEpochSeconds)
        assertEquals(arrival.expectedEpochSeconds - arrival.delaySeconds!!, arrival.scheduledEpochSeconds)
    }

    @Test
    fun `static fallback arrival scheduled == expected`() {
        val svc = scheduledService(bundleWithSchedule("1111111", secondsSinceDublinMidnight + 600))

        val arrival = svc.arrivalsForStop("s1").single()

        assertFalse(arrival.isRealtime)
        assertEquals(wednesdayMorning + 600L, arrival.scheduledEpochSeconds)
        assertEquals(arrival.expectedEpochSeconds, arrival.scheduledEpochSeconds)
    }

    @Test
    fun `a loop trip's direct arrivals each get their own scheduled time`() {
        // Scheduled at "loop" +5 min and +30 min; the bus is 3 min late and reports both visits.
        val svc = tripCallsWith(
            listOf(
                Fixtures.stopTimeUpdate("loop", wednesdayMorning + 480, stopSequence = 2, delaySeconds = 180),
                Fixtures.stopTimeUpdate("loop", wednesdayMorning + 1980, stopSequence = 3, delaySeconds = 180),
            ),
            "loop" to 300, "loop" to 1800,
        )

        val arrivals = svc.arrivalsForStop("loop")

        assertEquals(listOf(wednesdayMorning + 480, wednesdayMorning + 1980), arrivals.map { it.expectedEpochSeconds })
        assertEquals(listOf(wednesdayMorning + 300L, wednesdayMorning + 1800L), arrivals.map { it.scheduledEpochSeconds })
    }

    @Test
    fun `a loop trip's cascaded arrivals each get their own scheduled time`() {
        val svc = tripCalls("mid" to 0, "loop" to 300, "loop" to 1800)

        val arrivals = svc.arrivalsForStop("loop")

        assertEquals(listOf(wednesdayMorning + 480, wednesdayMorning + 1980), arrivals.map { it.expectedEpochSeconds })
        assertEquals(listOf(wednesdayMorning + 300L, wednesdayMorning + 1800L), arrivals.map { it.scheduledEpochSeconds })
    }

    @Test
    fun `tripStopCalls include scheduledEpochSeconds`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1", tripId = "t1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", wednesdayMorning + 100, stopSequence = 1, delaySeconds = 100),
                        Fixtures.stopTimeUpdate("s2", wednesdayMorning + 700, stopSequence = 2, delaySeconds = 100),
                    ),
                ),
            ),
        )

        val calls = svc.tripStopCalls("t1")

        assertEquals(listOf(wednesdayMorning.toLong(), wednesdayMorning + 600L), calls.map { it.scheduledEpochSeconds })
    }

    @Test
    fun `a loop trip's stop calls each get their own scheduled time`() {
        val svc = tripCallsWith(
            listOf(
                Fixtures.stopTimeUpdate("loop", wednesdayMorning + 480, stopSequence = 2, delaySeconds = 180),
                Fixtures.stopTimeUpdate("loop", wednesdayMorning + 1980, stopSequence = 3, delaySeconds = 180),
            ),
            "loop" to 300, "loop" to 1800,
        )

        val calls = svc.tripStopCalls("t1")

        assertEquals(listOf(wednesdayMorning + 300L, wednesdayMorning + 1800L), calls.map { it.scheduledEpochSeconds })
    }

    @Test
    fun `scheduledEpochSeconds is null when no timetable call matches`() {
        // The default fixture bundle has no stop schedule, so nothing can be matched.
        recordTripUpdates()

        assertTrue(service.arrivalsForStop("s1").all { it.scheduledEpochSeconds == null })
        assertTrue(service.tripStopCalls("t1").all { it.scheduledEpochSeconds == null })
    }

    @Test
    fun `scheduledEpochSeconds is null for a stop the timetable lacks for that trip`() {
        val (store, svc) = twoStopService()
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1", tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s9", wednesdayMorning + 100, stopSequence = 1)),
                ),
            ),
        )

        assertNull(svc.arrivalsForStop("s9").single().scheduledEpochSeconds)
        assertNull(svc.tripStopCalls("t1").single().scheduledEpochSeconds)
    }

    // --- hardening: a wrong scheduledEpochSeconds is worse than null ---------------

    private fun s1ArrivalAt(offsetSeconds: Int, delaySeconds: Int? = null): Arrival? {
        val (store, svc) = twoStopService() // t1 is scheduled at s1 exactly at "now"
        store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    "e1", tripId = "t1",
                    stopTimeUpdates = listOf(Fixtures.stopTimeUpdate("s1", wednesdayMorning + offsetSeconds, delaySeconds = delaySeconds)),
                ),
            ),
        )
        return svc.arrivalsForStop("s1").singleOrNull()
    }

    @Test
    fun `a nearest timetable call exactly 1800s from the target is accepted`() {
        assertEquals(wednesdayMorning.toLong(), s1ArrivalAt(1800)!!.scheduledEpochSeconds)
        assertEquals(wednesdayMorning.toLong(), s1ArrivalAt(1800 + 100, delaySeconds = 100)!!.scheduledEpochSeconds)
    }

    @Test
    fun `a nearest timetable call 1801s from the target is null`() {
        val arrival = s1ArrivalAt(1801)!!
        assertNull(arrival.scheduledEpochSeconds)
        assertEquals(wednesdayMorning + 1801L, arrival.expectedEpochSeconds) // expected is untouched
        assertNull(s1ArrivalAt(1801 + 100, delaySeconds = 100)!!.scheduledEpochSeconds)
    }

    private fun loopService(delaySeconds: Int?): ArrivalsService = tripCallsWith(
        listOf(
            Fixtures.stopTimeUpdate("loop", wednesdayMorning + 480, stopSequence = 2, delaySeconds = delaySeconds),
            Fixtures.stopTimeUpdate("loop", wednesdayMorning + 1980, stopSequence = 3, delaySeconds = delaySeconds),
        ),
        "loop" to 300, "loop" to 1800,
    )

    @Test
    fun `a delay-less event on a loop trip is ambiguous so its scheduled time is null`() {
        val arrivals = loopService(null).arrivalsForStop("loop")

        assertEquals(2, arrivals.size)
        assertTrue(arrivals.all { it.scheduledEpochSeconds == null })
        assertTrue(loopService(null).tripStopCalls("t1").all { it.scheduledEpochSeconds == null })
    }

    @Test
    fun `a loop trip event with a delay still matches its exact visit`() {
        val arrivals = loopService(180).arrivalsForStop("loop")

        assertEquals(listOf(wednesdayMorning + 300L, wednesdayMorning + 1800L), arrivals.map { it.scheduledEpochSeconds })
        assertEquals(
            listOf(wednesdayMorning + 300L, wednesdayMorning + 1800L),
            loopService(180).tripStopCalls("t1").map { it.scheduledEpochSeconds },
        )
    }

    @Test
    fun `a delay-less event on a single-visit stop is still matched`() {
        assertEquals(wednesdayMorning.toLong(), s1ArrivalAt(100)!!.scheduledEpochSeconds)
    }
}
