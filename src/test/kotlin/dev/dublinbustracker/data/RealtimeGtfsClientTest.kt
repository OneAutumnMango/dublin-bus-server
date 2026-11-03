package dev.dublinbustracker.data

import dev.dublinbustracker.support.Fixtures
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealtimeGtfsClientTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun protobufResponse(entityId: String = "e1") = MockResponse()
        .setBody(Buffer().write(Fixtures.feedOf(Fixtures.tripUpdateEntity(entityId, tripId = "t1")).toByteArray()))

    private fun client(vararg keys: String) =
        RealtimeGtfsClient(keys.toList(), baseUrl = server.url("/gtfsr/v2").toString())

    @Test
    fun `fetch requests the correct upstream path for each feed kind`() {
        FeedKind.entries.forEach { server.enqueue(protobufResponse()) }
        val gtfsClient = client("k1")

        FeedKind.entries.forEach { gtfsClient.fetch(it) }

        val paths = FeedKind.entries.map { server.takeRequest().path }
        assertEquals(FeedKind.entries.map { "/gtfsr/v2/${it.path}" }, paths)
    }

    @Test
    fun `fetch sends the api key in the x-api-key header`() {
        server.enqueue(protobufResponse())
        val gtfsClient = RealtimeGtfsClient("secret-key", baseUrl = server.url("/gtfsr/v2").toString())

        gtfsClient.fetch(FeedKind.TRIP_UPDATES)

        assertEquals("secret-key", server.takeRequest().getHeader("x-api-key"))
    }

    @Test
    fun `calls rotate across configured keys in order`() {
        repeat(4) { server.enqueue(protobufResponse()) }
        val gtfsClient = client("k1", "k2")

        repeat(4) { gtfsClient.fetch(FeedKind.TRIP_UPDATES) }

        val used = (1..4).map { server.takeRequest().getHeader("x-api-key") }
        assertEquals(listOf("k1", "k2", "k1", "k2"), used)
    }

    @Test
    fun `blank keys are ignored`() {
        server.enqueue(protobufResponse())
        val gtfsClient = RealtimeGtfsClient(listOf("", "  ", "k1"), baseUrl = server.url("/gtfsr/v2").toString())

        assertEquals(1, gtfsClient.keyCount)
        gtfsClient.fetch(FeedKind.TRIP_UPDATES)
        assertEquals("k1", server.takeRequest().getHeader("x-api-key"))
    }

    @Test
    fun `at least one non-blank key is required`() {
        assertFailsWith<IllegalArgumentException> { RealtimeGtfsClient(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RealtimeGtfsClient(listOf("", "  ")) }
    }

    @Test
    fun `totalRequests counts every attempt, including failed ones`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(protobufResponse())
        val gtfsClient = client("k1")

        runCatching { gtfsClient.fetch(FeedKind.TRIP_UPDATES) }
        gtfsClient.fetch(FeedKind.TRIP_UPDATES)

        assertEquals(2, gtfsClient.totalRequests)
    }

    @Test
    fun `a non-2xx response throws, naming the feed path and status`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val gtfsClient = client("k1")

        val error = assertFailsWith<IllegalStateException> { gtfsClient.fetch(FeedKind.VEHICLES) }

        assertTrue(error.message!!.contains("429"))
        assertTrue(error.message!!.contains("Vehicles"))
    }

    @Test
    fun `the response body is parsed into a feed message`() {
        server.enqueue(protobufResponse("e7"))
        val gtfsClient = client("k1")

        val feed = gtfsClient.fetch(FeedKind.TRIP_UPDATES)

        assertEquals(1, feed.entityCount)
        assertEquals("e7", feed.getEntity(0).id)
    }

    @Test
    fun `a trailing slash on the base url does not produce a double slash`() {
        server.enqueue(protobufResponse())
        val gtfsClient = RealtimeGtfsClient(listOf("k1"), baseUrl = server.url("/gtfsr/v2/").toString())

        gtfsClient.fetch(FeedKind.TRIP_UPDATES)

        assertEquals("/gtfsr/v2/TripUpdates", server.takeRequest().path)
    }
}
