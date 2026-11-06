package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.RealtimeGtfsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Consecutive failed cycles for a feed before it's reported stale rather than just logged. */
private const val STALE_THRESHOLD = 3

/**
 * Refreshes each configured feed on a fixed cycle. One cycle fetches every enabled feed once,
 * so the upstream call rate is `feeds.size` calls per [intervalMs]. A failed fetch is never
 * retried within the cycle: the NTA may already have counted it against the per-key budget, so a
 * retry risks a 429. The next cycle is the retry.
 */
class FeedPoller(
    private val client: RealtimeGtfsClient,
    private val store: FeedStore,
    private val feeds: List<FeedKind>,
    private val intervalMs: Long,
    private val notifier: NtfyNotifier = NtfyNotifier(null, NtfyLevel.OFF),
) {
    private val logger = LoggerFactory.getLogger(FeedPoller::class.java)
    private val successes = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val consecutiveFailures = ConcurrentHashMap<FeedKind, Int>()

    val successCount: Long get() = successes.get()
    val failureCount: Long get() = failures.get()

    /** Fetches every enabled feed once. Failures are logged and counted, never thrown. */
    suspend fun refreshAll() {
        for (kind in feeds) {
            fetch(kind)
                .onSuccess { feed ->
                    store.record(kind, feed)
                    successes.incrementAndGet()
                    logger.debug("Refreshed {} ({} entities)", kind, feed.entityCount)
                    if ((consecutiveFailures.remove(kind) ?: 0) >= STALE_THRESHOLD) {
                        alert(NtfyLevel.WARN, "$kind feed recovered")
                    }
                }
                .onFailure { error ->
                    failures.incrementAndGet()
                    logger.warn("Refresh of {} failed: {}", kind, error.message)
                    alert(NtfyLevel.INFO, "$kind poll failed: ${error.message}")
                    val count = consecutiveFailures.merge(kind, 1, Int::plus)
                    if (count == STALE_THRESHOLD) {
                        alert(NtfyLevel.WARN, "$kind has failed $count times in a row; data may be stale")
                    }
                }
        }
    }

    private suspend fun alert(required: NtfyLevel, message: String) =
        withContext(Dispatchers.IO) { notifier.notify(required, "Dublin Bus Server", message) }

    private suspend fun fetch(kind: FeedKind): Result<FeedMessage> =
        withContext(Dispatchers.IO) { runCatching { client.fetch(kind) } }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                refreshAll()
            }
        }
    }
}
