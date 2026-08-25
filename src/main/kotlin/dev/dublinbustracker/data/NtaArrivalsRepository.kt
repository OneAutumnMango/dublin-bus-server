package dev.dublinbustracker.data

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import dev.dublinbustracker.core.Arrival
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SINGLE_KEY_POLL_INTERVAL_MS = 60_000L
private const val DUAL_KEY_POLL_INTERVAL_MS = 30_000L

/** Fetches the NTA feed once per poll and reuses it (via [arrivalsFromFeed]) for every stop a client asks about. */
class NtaArrivalsRepository(
    private val client: RealtimeGtfsClient,
    private val gtfsStaticData: GtfsStaticData,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Each key has its own 60s rate limit; alternating two keys halves the safe poll interval. */
    val pollIntervalMs: Long
        get() = if (client.keyCount >= 2) DUAL_KEY_POLL_INTERVAL_MS else SINGLE_KEY_POLL_INTERVAL_MS

    suspend fun arrivalsForStop(stopId: String): List<Arrival> = arrivalsFromFeed(fetchFeed(), stopId)

    suspend fun fetchFeed(): FeedMessage = withContext(Dispatchers.IO) { client.fetchTripUpdates() }

    fun arrivalsFromFeed(feed: FeedMessage, stopId: String, now: Long = nowEpochSeconds()): List<Arrival> =
        feed.entityList
            .asSequence()
            .filter { it.hasTripUpdate() }
            .flatMap { entity ->
                entity.tripUpdate.stopTimeUpdateList.asSequence()
                    .filter { it.stopId == stopId }
                    .mapNotNull { stopTimeUpdate ->
                        val etaSeconds = when {
                            stopTimeUpdate.hasArrival() && stopTimeUpdate.arrival.hasTime() -> stopTimeUpdate.arrival.time
                            stopTimeUpdate.hasDeparture() && stopTimeUpdate.departure.hasTime() -> stopTimeUpdate.departure.time
                            else -> null
                        } ?: return@mapNotNull null
                        val tripInfo = gtfsStaticData.tripInfo(entity.tripUpdate.trip.tripId)
                        Arrival(
                            routeShortName = tripInfo?.routeShortName ?: entity.tripUpdate.trip.routeId,
                            destination = tripInfo?.headsign.orEmpty(),
                            dueMinutes = ((etaSeconds - now) / 60).toInt().coerceAtLeast(0),
                            isRealtime = true,
                        )
                    }
            }
            .sortedBy { it.dueMinutes }
            .toList()
}
