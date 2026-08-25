package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.RealtimeGtfsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Refreshes each configured feed on a fixed cycle. One cycle fetches every enabled feed once,
 * so the upstream call rate is `feeds.size` calls per [intervalMs].
 */
class FeedPoller(
    private val client: RealtimeGtfsClient,
    private val store: FeedStore,
    private val feeds: List<FeedKind>,
    private val intervalMs: Long,
) {
    private val logger = LoggerFactory.getLogger(FeedPoller::class.java)
    private val successes = AtomicLong(0)
    private val failures = AtomicLong(0)

    val successCount: Long get() = successes.get()
    val failureCount: Long get() = failures.get()

    /** Fetches every enabled feed once. Failures are logged and counted, never thrown. */
    suspend fun refreshAll() {
        for (kind in feeds) {
            withContext(Dispatchers.IO) {
                runCatching { client.fetch(kind) }
            }.onSuccess { feed ->
                store.record(kind, feed)
                successes.incrementAndGet()
                logger.debug("Refreshed {} ({} entities)", kind, feed.entityCount)
            }.onFailure { error ->
                failures.incrementAndGet()
                logger.warn("Refresh of {} failed: {}", kind, error.message)
            }
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                refreshAll()
            }
        }
    }
}
