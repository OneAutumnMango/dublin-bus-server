package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.support.Fixtures
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedPollerTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun protobufResponse(entityId: String) = MockResponse()
        .setBody(Buffer().write(Fixtures.feedOf(Fixtures.tripUpdateEntity(entityId, tripId = "t1")).toByteArray()))

    private fun client(vararg keys: String) =
        RealtimeGtfsClient(keys.toList(), baseUrl = server.url("/gtfsr/v2").toString())

    @Test
    fun `one cycle fetches each configured feed exactly once`() = runTest {
        server.enqueue(protobufResponse("a"))
        server.enqueue(protobufResponse("b"))
        val store = FeedStore(historyDepth = 5)
        val poller = FeedPoller(
            client("k1"),
            store,
            listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES),
            intervalMs = 60_000,
        )

        poller.refreshAll()

        assertEquals(2, server.requestCount)
        assertEquals(
            setOf("/gtfsr/v2/TripUpdates", "/gtfsr/v2/Vehicles"),
            setOf(server.takeRequest().path, server.takeRequest().path),
        )
        assertNotNull(store.latest(FeedKind.TRIP_UPDATES))
        assertNotNull(store.latest(FeedKind.VEHICLES))
        assertEquals(2, poller.successCount)
        assertEquals(0, poller.failureCount)
    }

    @Test
    fun `only the configured feeds are polled`() = runTest {
        server.enqueue(protobufResponse("a"))
        val store = FeedStore(historyDepth = 5)
        val poller = FeedPoller(client("k1"), store, listOf(FeedKind.TRIP_UPDATES), intervalMs = 60_000)

        poller.refreshAll()

        assertEquals(1, server.requestCount)
        assertNull(store.latest(FeedKind.VEHICLES))
    }

    @Test
    fun `the api key rotates between cycles so the per-key budget is shared`() = runTest {
        repeat(4) { server.enqueue(protobufResponse("a")) }
        val poller = FeedPoller(
            client("k1", "k2"),
            FeedStore(historyDepth = 2),
            listOf(FeedKind.TRIP_UPDATES),
            intervalMs = 30_000,
        )

        repeat(4) { poller.refreshAll() }

        val usedKeys = (1..4).map { server.takeRequest().getHeader("x-api-key") }
        assertEquals(listOf("k1", "k2", "k1", "k2"), usedKeys)
    }

    @Test
    fun `an upstream failure is counted and does not stop the other feeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(protobufResponse("ok"))
        val store = FeedStore(historyDepth = 5)
        val poller = FeedPoller(
            client("k1"),
            store,
            listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES),
            intervalMs = 60_000,
        )

        poller.refreshAll()

        assertEquals(1, poller.failureCount)
        assertEquals(1, poller.successCount)
        assertNull(store.latest(FeedKind.TRIP_UPDATES))
        assertNotNull(store.latest(FeedKind.VEHICLES))
    }

    @Test
    fun `a failed refresh leaves the previous snapshot in place`() = runTest {
        server.enqueue(protobufResponse("good"))
        server.enqueue(MockResponse().setResponseCode(500))
        val store = FeedStore(historyDepth = 5)
        val poller = FeedPoller(client("k1"), store, listOf(FeedKind.TRIP_UPDATES), intervalMs = 60_000)

        poller.refreshAll()
        poller.refreshAll()

        assertEquals("good", store.latest(FeedKind.TRIP_UPDATES)?.feed?.getEntity(0)?.id)
    }
}
