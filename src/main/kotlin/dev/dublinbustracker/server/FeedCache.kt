package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import dev.dublinbustracker.core.Arrival
import dev.dublinbustracker.data.NtaArrivalsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/** Polls the NTA feed on a timer and serves every stop request from the one cached snapshot. */
class FeedCache(private val repository: NtaArrivalsRepository) {
    private val logger = LoggerFactory.getLogger(FeedCache::class.java)
    private val latestFeed = AtomicReference<FeedMessage?>(null)

    val pollIntervalMs: Long get() = repository.pollIntervalMs

    suspend fun refresh() {
        latestFeed.set(repository.fetchFeed())
        logger.info("Refreshed GTFS-R feed")
    }

    fun arrivalsForStop(stopId: String): List<Arrival> {
        val feed = latestFeed.get() ?: return emptyList()
        return repository.arrivalsFromFeed(feed, stopId)
    }

    /** Assumes the caller already did an initial [refresh] before serving requests. */
    fun startPolling(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                runCatching { refresh() }.onFailure { logger.warn("Feed refresh failed: ${it.message}") }
            }
        }
    }
}
