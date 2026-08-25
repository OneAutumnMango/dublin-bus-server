package dev.dublinbustracker.data

import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.FeedHeader
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NtaArrivalsRepositoryTest {
    private val staticData = GtfsStaticData.parse(
        """{"stops":[],"routes":{"r1":"15"},"trips":{"t1":{"routeId":"r1","headsign":"Clongriffin"}}}""",
    )

    @Test
    fun `maps trip updates for the requested stop, sorted by due time`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(feedWithTwoArrivals().toByteArray())))
        server.start()

        val client = RealtimeGtfsClient(apiKey = "test-key", feedUrl = server.url("/").toString())
        val repository = NtaArrivalsRepository(client, staticData, nowEpochSeconds = { 1_000L })

        val arrivals = repository.arrivalsForStop("s1")

        assertEquals(2, arrivals.size)
        assertEquals(listOf(2, 10), arrivals.map { it.dueMinutes })
        assertEquals("15", arrivals[0].routeShortName)
        assertEquals("Clongriffin", arrivals[0].destination)
        assertTrue(arrivals.all { it.isRealtime })

        server.shutdown()
    }

    @Test
    fun `arrivalsFromFeed maps a pre-fetched feed without any network call, for reuse across stops`() {
        val repository = NtaArrivalsRepository(RealtimeGtfsClient(apiKey = "unused"), staticData)

        val stopOne = repository.arrivalsFromFeed(feedWithTwoArrivals(), "s1", now = 1_000L)
        val otherStop = repository.arrivalsFromFeed(feedWithTwoArrivals(), "other-stop", now = 1_000L)

        assertEquals(2, stopOne.size)
        assertEquals(1, otherStop.size)
    }

    @Test
    fun `poll interval is 60s with one key, 30s when two keys are configured`() {
        val single = NtaArrivalsRepository(RealtimeGtfsClient(apiKey = "key-1"), staticData)
        assertEquals(60_000L, single.pollIntervalMs)

        val dual = NtaArrivalsRepository(RealtimeGtfsClient(listOf("key-1", "key-2")), staticData)
        assertEquals(30_000L, dual.pollIntervalMs)
    }

    private fun feedWithTwoArrivals(): FeedMessage {
        fun stopTimeUpdate(stopId: String, arrivalEpoch: Long) =
            TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId(stopId)
                .setArrival(TripUpdate.StopTimeEvent.newBuilder().setTime(arrivalEpoch))
                .build()

        val tripUpdate = TripUpdate.newBuilder()
            .setTrip(TripDescriptor.newBuilder().setTripId("t1").setRouteId("r1"))
            .addStopTimeUpdate(stopTimeUpdate("s1", 1_120L))
            .addStopTimeUpdate(stopTimeUpdate("s1", 1_600L))
            .addStopTimeUpdate(stopTimeUpdate("other-stop", 1_050L))
            .build()

        return FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(FeedEntity.newBuilder().setId("1").setTripUpdate(tripUpdate))
            .build()
    }
}
