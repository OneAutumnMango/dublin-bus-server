package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import dev.dublinbustracker.data.StaticDataRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal const val DEFAULT_SCRIPT_PATH = "scripts/fetch-gtfs-data.sh"
private const val STREAK_POLLS = 5
// Measured on the Pi: ~9% of live trip updates are unmatched on a correct bundle (operators the filter drops),
// 83% when NTA changed its ids. 20% leaves margin over a possibly higher-than-usual 9%; the minimum count keeps quiet night polls from tripping it.
private const val MIN_UNMATCHED_FRACTION = 0.2
private const val MIN_TRIP_UPDATES = 100
private const val COOLDOWN_MS = 6 * 3_600_000L
private val NOT_IN_STATIC = setOf("ADDED", "NEW")

/**
 * Periodically re-downloads and re-filters the static GTFS bundle by shelling out to
 * scripts/fetch-gtfs-data.sh - the same pipeline used on first boot - then hot-swaps the result
 * into [target]. Re-running the real pipeline (rather than reimplementing it) keeps this the
 * only place that logic lives. A failure just logs and keeps the previous bundle; the old data
 * is still better than crashing the server over it.
 */
class StaticDataRefresher(
    private val target: StaticDataRef,
    private val outputPath: String,
    private val notifier: NtfyNotifier = NtfyNotifier(null, NtfyLevel.OFF),
    private val intervalMs: Long,
    private val scriptPath: String = DEFAULT_SCRIPT_PATH,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val streakPolls: Int = STREAK_POLLS,
    private val minUnmatchedFraction: Double = MIN_UNMATCHED_FRACTION,
    private val minTripUpdates: Int = MIN_TRIP_UPDATES,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private val logger = LoggerFactory.getLogger(StaticDataRefresher::class.java)
    private var scope: CoroutineScope? = null
    private var streak = 0
    private val running = AtomicBoolean(false)

    /** Trip updates in the latest observed feed that the current bundle has no trip for. */
    @Volatile var unmatchedTripUpdates = 0
        private set

    /** When the last refresh attempt (timer or detector) started; 0 if none yet. */
    @Volatile var lastRefreshEpochMs = 0L
        private set

    /**
     * Called after each TRIP_UPDATES poll. A bundle the live feed no longer matches (NTA changed its
     * ids) shows up as trips we don't know; after [streakPolls] such polls in a row, refresh.
     */
    @Synchronized
    fun observe(feed: FeedMessage) {
        val data = target.current
        // Added trips are not in the static data by design. Compared by name so a newer protobuf
        // enum (NEW) needs no code change.
        val considered = feed.entityList.filter { it.hasTripUpdate() && it.tripUpdate.trip.scheduleRelationship.name !in NOT_IN_STATIC }
        unmatchedTripUpdates = considered.count { data.trip(it.tripUpdate.trip.tripId) == null }
        val bad = considered.size >= minTripUpdates && unmatchedTripUpdates >= considered.size * minUnmatchedFraction
        streak = if (bad) streak + 1 else 0
        val scope = scope ?: return
        if (streak < streakPolls || nowMs() - lastRefreshEpochMs < cooldownMs) return
        streak = 0
        logger.warn("{} live trip updates have no trip in the static bundle for {} polls; refreshing it", unmatchedTripUpdates, streakPolls)
        scope.launch { attempt() }
    }

    /** The caller must have won the [running] flag. */
    private suspend fun runLocked() {
        try {
            lastRefreshEpochMs = nowMs()
            refresh()
        } finally {
            running.set(false)
        }
    }

    private suspend fun attempt() {
        if (running.compareAndSet(false, true)) runLocked()
    }

    val isRefreshing: Boolean get() = running.get()

    /**
     * Starts a refresh now, ignoring the cooldown and the bundle's age. False if one is already
     * running or the refresher has not been started.
     */
    fun requestRefresh(): Boolean {
        val scope = scope ?: return false
        if (!running.compareAndSet(false, true)) return false
        logger.info("Static GTFS refresh requested")
        scope.launch { runLocked() }
        return true
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!File(scriptPath).isFile) {
            logger.debug("{} not found; skipping static GTFS refresh", scriptPath)
            return@withContext
        }
        runCatching {
            val process = ProcessBuilder("bash", scriptPath, "--out", outputPath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "exit $exitCode: ${output.trim()}" }
            val fresh = loadStaticData(outputPath)
            target.current = fresh
            logger.info("Refreshed static GTFS: {}", fresh.stats())
        }.onFailure { error ->
            logger.warn("Static GTFS refresh failed, keeping the current bundle: {}", error.message)
            notifier.notify(NtfyLevel.WARN, "Dublin Bus Server", "Static GTFS refresh failed: ${error.message}")
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            // The first wait is what is left of the bundle's age, so restarts don't push a stale bundle
            // another full interval out (0 if it's already older: refresh straight away).
            val ageMs = System.currentTimeMillis() - File(outputPath).lastModified()
            var wait = if (File(outputPath).isFile) (intervalMs - ageMs).coerceIn(0, intervalMs) else intervalMs
            while (isActive) {
                delay(wait)
                attempt()
                wait = intervalMs
            }
        }
    }
}
