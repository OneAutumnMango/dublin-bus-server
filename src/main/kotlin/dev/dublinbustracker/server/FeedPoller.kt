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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_RETRY_ATTEMPTS = 3
private const val DEFAULT_RETRY_BACKOFF_MS = 1_000L

/** Consecutive failed cycles for a feed before it's reported stale rather than just logged. */
private const val STALE_THRESHOLD = 3

/**
 * Refreshes each configured feed on a fixed cycle. One cycle fetches every enabled feed once,
 * so the upstream call rate is `feeds.size` calls per [intervalMs]. A network-level failure is
 * retried with backoff, since that's often transient; an HTTP error status (e.g. a 429) is not,
 * since retrying it immediately would just repeat it and burn more of the rate-limit budget.
 */
class FeedPoller(
    private val client: RealtimeGtfsClient,
    private val store: FeedStore,
    private val feeds: List<FeedKind>,
    private val intervalMs: Long,
    private val notifier: NtfyNotifier = NtfyNotifier(null, NtfyLevel.OFF),
    private val retryAttempts: Int = DEFAULT_RETRY_ATTEMPTS,
    private val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
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
            fetchWithRetry(kind)
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

    /** Retries only on a network-level failure; an HTTP error status would just repeat. */
    private suspend fun fetchWithRetry(kind: FeedKind): Result<FeedMessage> {
        repeat(retryAttempts - 1) { attempt ->
            val result = withContext(Dispatchers.IO) { runCatching { client.fetch(kind) } }
            if (result.isSuccess || result.exceptionOrNull() !is IOException) return result
            delay(retryBackoffMs * (1L shl attempt))
        }
        return withContext(Dispatchers.IO) { runCatching { client.fetch(kind) } }
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
