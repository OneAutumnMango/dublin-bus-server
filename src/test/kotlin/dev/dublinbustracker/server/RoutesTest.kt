package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun services(bundle: String = Fixtures.staticBundleJson()): AppServices {
        val staticData = StaticDataRef(GtfsStaticData.parse(bundle))
        val config = ServerConfig(
            port = 0,
            ntaApiKeys = listOf("nta-key"),
            feeds = listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES),
            // Long enough that the background poller never fires during a test.
            pollIntervalMs = 3_600_000,
            historyDepth = 5,
            gtfsStaticPath = null,
            gtfsBaseUrl = "http://127.0.0.1:1/unused",
            allowUnsafePollRate = true,
            // Long enough that the background static-data refresher never fires during a test.
            gtfsRefreshIntervalHours = 24 * 365,
        )
        val client = RealtimeGtfsClient(listOf("nta-key"), baseUrl = config.gtfsBaseUrl)
        return AppServices.build(config, staticData, client).also { seed(it) }
    }

    private fun seed(services: AppServices) {
        services.store.record(
            FeedKind.TRIP_UPDATES,
            Fixtures.feedOf(
                Fixtures.tripUpdateEntity(
                    entityId = "e1",
                    tripId = "t1",
                    vehicleId = "bus-1",
                    stopTimeUpdates = listOf(
                        Fixtures.stopTimeUpdate("s1", System.currentTimeMillis() / 1000 + 300, stopSequence = 1),
                        Fixtures.stopTimeUpdate("s2", System.currentTimeMillis() / 1000 + 600, stopSequence = 2),
                    ),
                ),
            ),
        )
        repeat(2) { tick ->
            services.store.record(
                FeedKind.VEHICLES,
                Fixtures.feedOf(
                    Fixtures.vehicleEntity(
                        entityId = "v1",
                        vehicleId = "bus-1",
                        tripId = "t1",
                        lat = 53.3441 + tick * 0.001,
                        lon = -6.2578,
                        timestampEpochSeconds = 1_000L + tick,
                    ),
                ),
            )
        }
        services.store.record(
            FeedKind.COMBINED,
            Fixtures.feedOf(Fixtures.alertEntity("a1", "Diversion", routeIds = listOf("r1"), stopIds = listOf("s1"))),
        )
    }

    private fun ApplicationTestBuilder.startServer(bundle: String = Fixtures.staticBundleJson()) {
        application { configureServer(services(bundle)) }
    }

    @Test
    fun `health responds ok`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    // --- legacy endpoints the Android app already ships against ---------------

    @Test
    fun `legacy arrivals endpoint still returns arrivals for a stop`() = testApplication {
        startServer()
        val response = client.get("/arrivals?stopId=s1")
        assertEquals(HttpStatusCode.OK, response.status)
        val arrivals = json.decodeFromString<List<ArrivalResponse>>(response.bodyAsText())
        assertEquals("15", arrivals.single().routeShortName)
    }

    @Test
    fun `legacy arrivals endpoint requires a stopId`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.BadRequest, client.get("/arrivals").status)
    }

    @Test
    fun `legacy stops search still works`() = testApplication {
        startServer()
        val stops = json.decodeFromString<List<StopResponse>>(client.get("/stops?query=trinity").bodyAsText())
        assertEquals("s1", stops.single().id)
    }

    // --- status ---------------------------------------------------------------

    @Test
    fun `status reports rate, backlog and feed freshness`() = testApplication {
        startServer()
        val status = json.decodeFromString<StatusResponse>(client.get("/v1/status").bodyAsText())

        assertEquals(3_600_000, status.pollIntervalMs)
        assertEquals(5, status.historyDepth)
        assertEquals(1, status.apiKeyCount)
        assertEquals(listOf("TRIP_UPDATES", "VEHICLES"), status.configuredFeeds)
        assertEquals(3, status.staticData["stops"])
        assertEquals(2, status.feeds.size)
        assertEquals(2, status.feeds.single { it.feed == "VEHICLES" }.retainedSnapshots)
    }

    // --- stops ---------------------------------------------------------------

    @Test
    fun `stop search returns the routes serving each stop`() = testApplication {
        startServer()
        val stops = json.decodeFromString<List<StopResponse>>(client.get("/v1/stops?query=trinity").bodyAsText())
        assertEquals(listOf("15", "27"), stops.single().routes)
    }

    @Test
    fun `stop search honours the limit`() = testApplication {
        startServer()
        val stops = json.decodeFromString<List<StopResponse>>(client.get("/v1/stops?query=a&limit=1").bodyAsText())
        assertTrue(stops.size <= 1)
    }

    @Test
    fun `nearby stops are returned within the radius`() = testApplication {
        startServer()
        val response = client.get("/v1/stops/nearby?lat=53.3441&lon=-6.2578&radius=3000")
        val stops = json.decodeFromString<List<StopResponse>>(response.bodyAsText())
        assertEquals(listOf("s1", "s2"), stops.map { it.id })
    }

    @Test
    fun `nearby stops requires coordinates`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/stops/nearby?lat=53.3441").status)
    }

    @Test
    fun `a stop can be fetched by roadside code`() = testApplication {
        startServer()
        val stop = json.decodeFromString<StopResponse>(client.get("/v1/stops/1001").bodyAsText())
        assertEquals("s1", stop.id)
    }

    @Test
    fun `a single stop says which way each route heads there`() = testApplication {
        startServer()
        val stop = json.decodeFromString<StopResponse>(client.get("/v1/stops/s1").bodyAsText())

        // Route 15 runs s1 -> s2, i.e. from Trinity College west to Heuston. 27 has no stop sequence.
        assertEquals(listOf(RouteHeadingResponse("15", "W")), stop.headings)
    }

    @Test
    fun `searching stops stays light and leaves out the headings`() = testApplication {
        startServer()
        val found = json.decodeFromString<List<StopResponse>>(client.get("/v1/stops?query=trinity").bodyAsText())

        assertEquals(emptyList(), found.single().headings)
    }

    @Test
    fun `an unknown stop is a 404`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/stops/nope").status)
    }

    @Test
    fun `stop arrivals can be filtered by bus number`() = testApplication {
        startServer()
        val all = json.decodeFromString<List<ArrivalResponse>>(client.get("/v1/stops/s1/arrivals").bodyAsText())
        assertEquals(1, all.size)
        assertEquals("bus-1", all.single().vehicleId)

        val filtered = client.get("/v1/stops/s1/arrivals?route=27").bodyAsText()
        assertEquals(emptyList(), json.decodeFromString<List<ArrivalResponse>>(filtered))
    }

    @Test
    fun `route arrivals come back for every stop on the route in one request`() = testApplication {
        startServer()
        val byStop = json.decodeFromString<Map<String, List<ArrivalResponse>>>(client.get("/v1/routes/15/arrivals?limit=2").bodyAsText())
        assertEquals("bus-1", byStop.getValue("s1").single().vehicleId)
        assertTrue(byStop.all { (_, arrivals) -> arrivals.all { it.routeShortName == "15" } })
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/routes/nope/arrivals").status)
    }

    @Test
    fun `arrivals for an unknown stop are a 404`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/stops/nope/arrivals").status)
    }

    // --- routes ---------------------------------------------------------------

    @Test
    fun `routes can be listed and searched`() = testApplication {
        startServer()
        val all = json.decodeFromString<List<RouteResponse>>(client.get("/v1/routes").bodyAsText())
        assertEquals(3, all.size)

        val filtered = json.decodeFromString<List<RouteResponse>>(client.get("/v1/routes?query=27").bodyAsText())
        assertEquals(listOf("r3"), filtered.map { it.id })
    }

    @Test
    fun `a bus number returns every route variant`() = testApplication {
        startServer()
        val routes = json.decodeFromString<List<RouteResponse>>(client.get("/v1/routes/15").bodyAsText())
        assertEquals(setOf("r1", "r2"), routes.map { it.id }.toSet())
    }

    @Test
    fun `an unknown route is a 404`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/routes/999").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/routes/999/stops").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/routes/999/shape").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/routes/999/vehicles").status)
    }

    @Test
    fun `route stops are returned per direction, in order`() = testApplication {
        startServer()
        val response = json.decodeFromString<RouteStopsResponse>(client.get("/v1/routes/15/stops").bodyAsText())
        assertEquals("r1", response.routeId)
        assertEquals(listOf("s1", "s2"), response.directions.getValue("0").map { it.id })
        assertEquals(listOf("s2", "s1"), response.directions.getValue("1").map { it.id })
    }

    @Test
    fun `route stops can be narrowed to one direction`() = testApplication {
        startServer()
        val response = json.decodeFromString<RouteStopsResponse>(
            client.get("/v1/routes/15/stops?direction=0").bodyAsText(),
        )
        assertEquals(setOf("0"), response.directions.keys)
    }

    @Test
    fun `route shape returns an encoded polyline, and points on request`() = testApplication {
        startServer()
        val encoded = json.decodeFromString<ShapeResponse>(client.get("/v1/routes/15/shape").bodyAsText())
        assertEquals(3, encoded.pointCount)
        assertTrue(encoded.encodedPolyline.isNotEmpty())
        assertNull(encoded.points)

        val expanded = json.decodeFromString<ShapeResponse>(
            client.get("/v1/routes/15/shape?format=points").bodyAsText(),
        )
        assertEquals(3, expanded.points?.size)
        assertEquals(2, expanded.points?.first()?.size)
    }

    // --- vehicles ---------------------------------------------------------------

    @Test
    fun `live vehicles are listed and can be filtered by route`() = testApplication {
        startServer()
        val all = json.decodeFromString<List<VehicleResponse>>(client.get("/v1/vehicles").bodyAsText())
        assertEquals(listOf("bus-1"), all.map { it.vehicleId })

        val onRoute = json.decodeFromString<List<VehicleResponse>>(
            client.get("/v1/routes/15/vehicles").bodyAsText(),
        )
        assertEquals(listOf("bus-1"), onRoute.map { it.vehicleId })
    }

    @Test
    fun `vehicles can be filtered by bounding box`() = testApplication {
        startServer()
        val inside = json.decodeFromString<List<VehicleResponse>>(
            client.get("/v1/vehicles?bbox=53.30,-6.30,53.40,-6.20").bodyAsText(),
        )
        assertEquals(1, inside.size)

        val outside = json.decodeFromString<List<VehicleResponse>>(
            client.get("/v1/vehicles?bbox=51.0,-9.0,52.0,-8.0").bodyAsText(),
        )
        assertEquals(emptyList(), outside)
    }

    @Test
    fun `a malformed bounding box is a 400`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/vehicles?bbox=1,2,3").status)
    }

    @Test
    fun `a single vehicle can be fetched, and unknown ones are a 404`() = testApplication {
        startServer()
        val vehicle = json.decodeFromString<VehicleResponse>(client.get("/v1/vehicles/bus-1").bodyAsText())
        assertEquals("15", vehicle.routeShortName)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/vehicles/ghost").status)
    }

    @Test
    fun `vehicle history returns the retained track as fixes and a polyline`() = testApplication {
        startServer()
        val history = json.decodeFromString<VehicleHistoryResponse>(
            client.get("/v1/vehicles/bus-1/history").bodyAsText(),
        )
        assertEquals(2, history.fixCount)
        assertEquals(listOf(1_000L, 1_001L), history.fixes.map { it.timestampEpochSeconds })
        assertTrue(history.encodedPolyline.isNotEmpty())
    }

    @Test
    fun `history for a vehicle with no retained fixes is a 404`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/vehicles/ghost/history").status)
    }

    // --- trips and alerts -------------------------------------------------------

    @Test
    fun `a trip returns its route, live vehicle and stop calls`() = testApplication {
        startServer()
        val trip = json.decodeFromString<TripResponse>(client.get("/v1/trips/t1").bodyAsText())

        assertEquals("15", trip.routeShortName)
        assertEquals("Merrion Square", trip.headsign)
        assertNotNull(trip.vehicle)
        assertEquals(listOf("s1", "s2"), trip.stopCalls.map { it.stopId })
        assertEquals("Trinity College", trip.stopCalls.first().stopName)
    }

    @Test
    fun `an unknown trip is a 404`() = testApplication {
        startServer()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/trips/nope").status)
    }

    @Test
    fun `a trip shape is returned, and a trip without one is a 404`() = testApplication {
        startServer()
        val shape = json.decodeFromString<ShapeResponse>(client.get("/v1/trips/t1/shape").bodyAsText())
        assertEquals("sh1", shape.shapeId)
        // t3 exists in the bundle but has no shape id.
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/trips/t3/shape").status)
    }

    @Test
    fun `alerts are listed and can be filtered by route or stop`() = testApplication {
        startServer()
        assertEquals(1, json.decodeFromString<List<AlertResponse>>(client.get("/v1/alerts").bodyAsText()).size)
        assertEquals(1, json.decodeFromString<List<AlertResponse>>(client.get("/v1/alerts?route=15").bodyAsText()).size)
        assertEquals(1, json.decodeFromString<List<AlertResponse>>(client.get("/v1/alerts?stop=s1").bodyAsText()).size)
        assertEquals(0, json.decodeFromString<List<AlertResponse>>(client.get("/v1/alerts?stop=s2").bodyAsText()).size)
    }

    // --- the real HTTP bodies, through Main.kt's Json configuration ----------------

    /** t1 is timetabled 5 min after now at s1 and 10 min after at s2, matching the seeded live times. */
    private fun scheduledBundle(now: Long): String {
        val dayStart = java.time.Instant.ofEpochSecond(now).atZone(java.time.ZoneId.of("Europe/Dublin")).toLocalDate()
            .atTime(12, 0).atZone(java.time.ZoneId.of("Europe/Dublin")).minusHours(12).toEpochSecond()
        val secs = now - dayStart
        return Fixtures.staticBundleJson()
            .replace("\"shapeId\":\"sh1\"}", "\"shapeId\":\"sh1\",\"serviceId\":\"svc\"}")
            .replace(
                "\"shapes\":",
                "\"services\":{\"svc\":{\"weekdays\":\"1111111\",\"start\":\"20200101\",\"end\":\"20991231\"}}," +
                    "\"stopSchedule\":{\"s1\":[{\"t\":\"t1\",\"s\":${secs + 300}}],\"s2\":[{\"t\":\"t1\",\"s\":${secs + 600}}]}," +
                    "\"shapes\":",
            )
    }

    private val key = "\"scheduledEpochSeconds\":"

    @Test
    fun `http bodies carry scheduledEpochSeconds equal to the seeded slot`() = testApplication {
        val now = System.currentTimeMillis() / 1000
        startServer(scheduledBundle(now))
        val values = Regex("\"scheduledEpochSeconds\":(\\d+)")

        suspend fun slots(path: String) = values.findAll(client.get(path).bodyAsText()).map { it.groupValues[1].toLong() }.toList()

        assertEquals(listOf(now + 300), slots("/v1/stops/s1/arrivals"))
        assertEquals(listOf(now + 300, now + 600), slots("/v1/routes/15/arrivals"))
        assertEquals(listOf(now + 300, now + 600), slots("/v1/trips/t1"))
    }

    @Test
    fun `http bodies omit scheduledEpochSeconds when nothing matches`() = testApplication {
        startServer()

        val bodies = listOf("/v1/stops/s1/arrivals", "/v1/routes/15/arrivals", "/v1/trips/t1").map { client.get(it).bodyAsText() }

        bodies.forEach { assertTrue(it.contains("expectedEpochSeconds"), it) }
        bodies.forEach { assertTrue(!it.contains("scheduledEpochSeconds"), it) }
    }
}
