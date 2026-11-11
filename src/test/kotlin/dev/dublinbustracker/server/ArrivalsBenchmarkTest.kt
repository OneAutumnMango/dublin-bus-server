package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedEntity
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import kotlin.test.Test

/** Timing only, asserts nothing; runs only with env BENCH=1. */
class ArrivalsBenchmarkTest {

    /** ~3000 trip updates over ~40 routes; route "BENCH" has 80 stops and ~60 live trips. */
    fun syntheticFeed(): Pair<StaticDataRef, FeedStore> {
        val now = System.currentTimeMillis() / 1000
        val benchStops = (0 until 80).map { "b$it" }
        val otherStops = (0 until 400).map { "o$it" }
        val stops = (benchStops + otherStops).joinToString(",") {
            """{"id":"$it","code":"c$it","name":"Stop $it","lat":53.3,"lon":-6.2}"""
        }
        val routes = (0 until 39).map { """{"id":"r$it","shortName":"R$it","longName":"Route $it","agencyId":"a1"}""" } +
            """{"id":"rB","shortName":"BENCH","longName":"Bench Route","agencyId":"a1"}"""
        val trips = StringBuilder()
        val entities = ArrayList<FeedEntity>()
        // 60 live BENCH trips, each reporting one stop along the 80-stop pattern.
        for (i in 0 until 60) {
            val tripId = "tb$i"
            trips.append(""""$tripId":{"routeId":"rB","headsign":"Bench End","directionId":"0","shapeId":"","serviceId":"svc"},""")
            entities += Fixtures.tripUpdateEntity(
                "eb$i", tripId, vehicleId = "vb$i",
                stopTimeUpdates = listOf(
                    Fixtures.stopTimeUpdate(benchStops[i % 80], now + 60L * (i + 1), stopSequence = i % 80 + 1, delaySeconds = 30 + i),
                ),
            )
        }
        // ~2940 trips over the other 39 routes.
        for (i in 0 until 2940) {
            val tripId = "to$i"
            val route = i % 39
            trips.append(""""$tripId":{"routeId":"r$route","headsign":"H$route","directionId":"${i % 2}","shapeId":"","serviceId":"svc"},""")
            entities += Fixtures.tripUpdateEntity(
                "eo$i", tripId, vehicleId = "vo$i",
                stopTimeUpdates = listOf(
                    Fixtures.stopTimeUpdate(otherStops[(i * 7) % 400], now + 30L * (i % 100), stopSequence = i % 50 + 1, delaySeconds = i % 300),
                ),
            )
        }
        val patternStops = benchStops.joinToString(",") { "\"$it\"" }
        val stopRoutes = benchStops.joinToString(",") { """"$it":["BENCH"]""" } + "," +
            otherStops.joinToString(",") { """"$it":["R${it.drop(1).toInt() % 39}"]""" }
        val json = """
            {"version":2,"stops":[$stops],"routes":[${routes.joinToString(",")}],
             "trips":{${trips.toString().trimEnd(',')}},"shapes":{},
             "routePatterns":{"rB":{"0":[$patternStops]}},"stopRoutes":{$stopRoutes}}
        """.trimIndent()
        val store = FeedStore(historyDepth = 5)
        store.record(FeedKind.TRIP_UPDATES, Fixtures.feedOf(*entities.toTypedArray()))
        return StaticDataRef(GtfsStaticData.parse(json)) to store
    }

    @Test
    fun benchmarkPerStopLoop() {
        if (System.getenv("BENCH") != "1") return
        val (ref, store) = syntheticFeed()
        val service = ArrivalsService(store, ref)
        val stopIds = (0 until 80).map { "b$it" }
        service.arrivalsForStop(stopIds.first(), "BENCH", 50) // warm up
        val times = (1..3).map {
            val start = System.nanoTime()
            stopIds.forEach { service.arrivalsForStop(it, "BENCH", 50) }
            (System.nanoTime() - start) / 1_000_000
        }
        println("BENCH per-stop loop ms: best=${times.min()} worst=${times.max()} all=$times")
    }
}
