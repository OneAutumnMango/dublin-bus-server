package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.support.Fixtures
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `a network-level failure is retried with backoff until it succeeds`() = runTest {
        server.enqueue(protobufResponse("a"))
        var attempts = 0
        val flaky = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts++
                if (attempts == 1) throw IOException("connection reset") else chain.proceed(chain.request())
            }
            .build()
        val gtfsClient = RealtimeGtfsClient(listOf("k1"), flaky, baseUrl = server.url("/gtfsr/v2").toString())
        val poller = FeedPoller(
            gtfsClient,
            FeedStore(historyDepth = 5),
            listOf(FeedKind.TRIP_UPDATES),
            intervalMs = 60_000,
            retryBackoffMs = 1,
        )

        poller.refreshAll()

        assertEquals(2, attempts)
        assertEquals(1, poller.successCount)
        assertEquals(0, poller.failureCount)
    }

    @Test
    fun `an http error status is not retried, unlike a network failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(protobufResponse("b"))
        val poller = FeedPoller(client("k1"), FeedStore(historyDepth = 5), listOf(FeedKind.TRIP_UPDATES), intervalMs = 60_000)

        poller.refreshAll()

        // The enqueued success response is still waiting for the next cycle, not consumed as a retry.
        assertEquals(1, server.requestCount)
        assertEquals(1, poller.failureCount)
    }

    @Test
    fun `three consecutive failures trigger a stale alert, and recovery sends a notice`() = runTest {
        val ntfyServer = MockWebServer()
        ntfyServer.start()
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
            server.enqueue(protobufResponse("ok"))
            val notifier = NtfyNotifier(ntfyServer.url("/topic").toString(), NtfyLevel.WARN)
            val poller = FeedPoller(
                client("k1"),
                FeedStore(historyDepth = 5),
                listOf(FeedKind.TRIP_UPDATES),
                intervalMs = 60_000,
                notifier = notifier,
            )

            repeat(3) { poller.refreshAll() }
            // WARN excludes the per-failure INFO alert, so only the stale alert at the threshold fires.
            assertEquals(1, ntfyServer.requestCount)
            assertTrue(ntfyServer.takeRequest().body.readUtf8().contains("stale"))

            poller.refreshAll()
            assertEquals(2, ntfyServer.requestCount)
            assertTrue(ntfyServer.takeRequest().body.readUtf8().contains("recovered"))
        } finally {
            ntfyServer.shutdown()
        }
    }
}
