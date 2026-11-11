package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedEntity
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import kotlin.test.Test

/** Shared by later tasks that reuse [ArrivalsBenchmarkTest.syntheticFeed]. */
object BenchData {
    const val ROUTE_ID = "BENCH"
    val STOP_IDS: List<String> = (0 until 80).map { "b$it" }
}

/** Timing only, asserts nothing; runs only with env BENCH=1. */
class ArrivalsBenchmarkTest {

    /**
     * ~3000 live trip updates over ~40 routes; route "BENCH" has 80 stops and ~60 live trips.
     * The static bundle has ~25k trips, an always-active service and a few hundred scheduled
     * calls per BENCH stop, so the timetable-dependent paths are exercised.
     */
    fun syntheticFeed(): Pair<StaticDataRef, FeedStore> {
        val zone = ZoneId.of("Europe/Dublin")
        val nowSec = System.currentTimeMillis() / 1000
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
        val nowOfDay = (nowSec - midnight).toInt()
        val rnd = Random(42)
        val benchStops = BenchData.STOP_IDS
        val otherStops = (0 until 400).map { "o$it" }
        val stops = (benchStops + otherStops).joinToString(",") {
            """{"id":"$it","code":"c$it","name":"Stop $it","lat":53.3,"lon":-6.2}"""
        }
        val routes = (0 until 39).map { """{"id":"r$it","shortName":"R$it","longName":"Route $it","agencyId":"a1"}""" } +
            """{"id":"rB","shortName":"${BenchData.ROUTE_ID}","longName":"Bench Route","agencyId":"a1"}"""

        val trips = StringBuilder()
        fun addTrip(id: String, route: String, dir: Int) {
            trips.append(""""$id":{"routeId":"$route","headsign":"H","directionId":"$dir","shapeId":"","serviceId":"svc"},""")
        }
        val entities = ArrayList<FeedEntity>()
        val calls = HashMap<String, MutableList<Pair<String, Int>>>()
        fun addCall(stop: String, trip: String, secs: Int) = calls.getOrPut(stop) { ArrayList() }.add(trip to secs)

        // 60 live BENCH trips, each reporting one stop; scheduled at every stop near their position.
        for (i in 0 until 60) {
            val tripId = "tb$i"
            addTrip(tripId, "rB", 0)
            val at = i % 80
            entities += Fixtures.tripUpdateEntity(
                "eb$i", tripId, vehicleId = "vb$i",
                stopTimeUpdates = listOf(
                    Fixtures.stopTimeUpdate(benchStops[at], nowSec + 60L * (i + 1), stopSequence = at + 1, delaySeconds = 30 + i),
                ),
            )
            for (j in 0 until 80) addCall(benchStops[j], tripId, nowOfDay + 60 * (i + 1) - 30 - i + 90 * (j - at))
        }
        // 2940 live trips on the other routes.
        for (i in 0 until 2940) {
            val tripId = "to$i"
            addTrip(tripId, "r${i % 39}", i % 2)
            val stop = otherStops[(i * 7) % 400]
            entities += Fixtures.tripUpdateEntity(
                "eo$i", tripId, vehicleId = "vo$i",
                stopTimeUpdates = listOf(
                    Fixtures.stopTimeUpdate(stop, nowSec + 30L * (i % 100), stopSequence = i % 50 + 1, delaySeconds = i % 300),
                ),
            )
            addCall(stop, tripId, nowOfDay + 30 * (i % 100) - (i % 300))
        }
        // Not-live static trips: 4000 on BENCH (scheduled fallback work), 18000 on other routes (filter rejects).
        for (k in 0 until 4000) addTrip("sb$k", "rB", k % 2)
        for (k in 0 until 18000) addTrip("so$k", "r${k % 39}", k % 2)
        for (stop in benchStops) {
            repeat(100) { addCall(stop, "sb${rnd.nextInt(4000)}", nowOfDay + rnd.nextInt(6 * 3600) - 3600) }
            repeat(140) { addCall(stop, "so${rnd.nextInt(18000)}", nowOfDay + rnd.nextInt(6 * 3600) - 3600) }
        }
        for (stop in otherStops) repeat(20) { addCall(stop, "so${rnd.nextInt(18000)}", nowOfDay + rnd.nextInt(6 * 3600) - 3600) }

        val stopSchedule = calls.entries.joinToString(",") { (stop, list) ->
            val body = list.sortedBy { it.second }.joinToString(",") { """{"t":"${it.first}","s":${it.second}}""" }
            """"$stop":[$body]"""
        }
        val patternStops = benchStops.joinToString(",") { "\"$it\"" }
        val stopRoutes = benchStops.joinToString(",") { """"$it":["${BenchData.ROUTE_ID}"]""" } + "," +
            otherStops.joinToString(",") { """"$it":["R${it.drop(1).toInt() % 39}"]""" }
        val json = """
            {"version":2,"stops":[$stops],"routes":[${routes.joinToString(",")}],
             "trips":{${trips.toString().trimEnd(',')}},"shapes":{},
             "routePatterns":{"rB":{"0":[$patternStops]}},"stopRoutes":{$stopRoutes},
             "services":{"svc":{"weekdays":"1111111"}},
             "stopSchedule":{$stopSchedule}}
        """.trimIndent()
        val store = FeedStore(historyDepth = 5)
        store.record(FeedKind.TRIP_UPDATES, Fixtures.feedOf(*entities.toTypedArray()))
        return StaticDataRef(GtfsStaticData.parse(json)) to store
    }

    @Test
    fun benchmarkPerStopLoop() {
        assumeTrue(System.getenv("BENCH") == "1", "set BENCH=1 to run")
        val (ref, store) = syntheticFeed()
        val service = ArrivalsService(store, ref)
        val stopIds = BenchData.STOP_IDS
        service.arrivalsForStop(stopIds.first(), BenchData.ROUTE_ID, 50) // warm up
        val sample = service.arrivalsForStop("b5", BenchData.ROUTE_ID, 50)
        println("BENCH sample b5: ${sample.size} arrivals, scheduled-only=${sample.count { !it.isRealtime }}; stats=${ref.current.stats()}")
        val times = (1..3).map {
            val start = System.nanoTime()
            stopIds.forEach { service.arrivalsForStop(it, BenchData.ROUTE_ID, 50) }
            (System.nanoTime() - start) / 1_000_000
        }
        println("BENCH per-stop loop ms: best=${times.min()} worst=${times.max()} all=$times")
    }

    /**
     * Both numbers from the same JVM and data. Every run uses a fresh [ArrivalsService] (cold: no per-instance state
     * from an earlier run). The per-stop loop here therefore includes first-touch cost, unlike [benchmarkPerStopLoop],
     * which warms one stop. The handler timing replicates the route handler body (cache + compute + toResponse) without
     * a Ktor host or JSON encoding.
     */
    @Test
    fun benchmarkArrivalsForRoute() {
        assumeTrue(System.getenv("BENCH") == "1", "set BENCH=1 to run")
        val (ref, store) = syntheticFeed()
        val stopIds = BenchData.STOP_IDS
        fun timed(block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1_000_000
        }
        val loop = (1..3).map {
            val service = ArrivalsService(store, ref)
            timed { stopIds.forEach { service.arrivalsForStop(it, BenchData.ROUTE_ID, 50) } }
        }
        println("BENCH per-stop loop (cold service) ms: best=${loop.min()} worst=${loop.max()} all=$loop")
        val route = (1..3).map {
            val service = ArrivalsService(store, ref)
            timed { service.arrivalsForRoute(BenchData.ROUTE_ID, stopIds, 50) }
        }
        println("BENCH route ms: best=${route.min()} worst=${route.max()} all=$route")

        val service = ArrivalsService(store, ref)
        val cache = ResponseCache<Map<String, List<ArrivalResponse>>>()
        fun request() = kotlinx.coroutines.runBlocking {
            cache.getOrCompute("${BenchData.ROUTE_ID}|50", service.feedStamp(), ref.current) {
                service.arrivalsForRoute(BenchData.ROUTE_ID, stopIds, 50).mapValues { (_, v) -> v.map { it.toResponse() } }
            }
        }
        val cold = timed { request() }
        val warm = timed { request() }
        println("BENCH handler cold/warm ms: $cold/$warm")
    }
}
