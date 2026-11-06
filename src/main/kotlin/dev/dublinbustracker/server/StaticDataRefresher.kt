package dev.dublinbustracker.server

import dev.dublinbustracker.data.StaticDataRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

private const val DEFAULT_SCRIPT_PATH = "scripts/fetch-gtfs-data.sh"

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
) {
    private val logger = LoggerFactory.getLogger(StaticDataRefresher::class.java)

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
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                refresh()
            }
        }
    }
}
