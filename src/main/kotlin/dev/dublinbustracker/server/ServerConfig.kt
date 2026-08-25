package dev.dublinbustracker.server

import dev.dublinbustracker.data.DEFAULT_GTFSR_BASE_URL
import dev.dublinbustracker.data.FeedKind

/** Each NTA key allows one upstream call per this window. */
const val RATE_LIMIT_WINDOW_MS = 60_000L

data class ServerConfig(
    val port: Int,
    val serverApiKey: String,
    val ntaApiKeys: List<String>,
    val feeds: List<FeedKind>,
    val pollIntervalMs: Long,
    val historyDepth: Int,
    val gtfsStaticPath: String?,
    val gtfsBaseUrl: String,
    val allowUnsafePollRate: Boolean,
) {
    /**
     * One cycle spends `feeds.size` calls, and the pool provides `ntaApiKeys.size` calls per
     * window, so cycles cannot be closer together than this without exceeding the fair-usage limit.
     */
    val minimumSafePollIntervalMs: Long
        get() = RATE_LIMIT_WINDOW_MS * feeds.size / ntaApiKeys.size

    val upstreamCallsPerMinute: Double
        get() = feeds.size * RATE_LIMIT_WINDOW_MS.toDouble() / pollIntervalMs

    fun validate(): List<String> {
        val warnings = mutableListOf<String>()
        if (pollIntervalMs < minimumSafePollIntervalMs) {
            warnings += "POLL_INTERVAL_MS=$pollIntervalMs is below the safe minimum of " +
                "${minimumSafePollIntervalMs}ms for ${feeds.size} feed(s) and ${ntaApiKeys.size} key(s); " +
                "expect HTTP 429s from the NTA"
        }
        return warnings
    }

    companion object {
        const val DEFAULT_HISTORY_DEPTH = 30

        fun fromEnv(rawEnv: (String) -> String? = System::getenv): ServerConfig {
            // Compose and shell wrappers routinely pass "" for unset values; treat those as absent.
            val env: (String) -> String? = { name -> rawEnv(name)?.takeIf { it.isNotBlank() } }

            val serverApiKey = env("SERVER_API_KEY")
                ?: error("SERVER_API_KEY is required - clients must send it as the X-Api-Key header")

            val ntaApiKeys = listOfNotNull(env("GTFSR_API_KEY"), env("GTFSR_API_KEY_SECONDARY"))
            require(ntaApiKeys.isNotEmpty()) { "GTFSR_API_KEY is required (GTFSR_API_KEY_SECONDARY is optional)" }

            val feeds = parseFeeds(env("FEEDS"))
            val historyDepth = env("HISTORY_DEPTH")?.let {
                it.toIntOrNull()?.takeIf { depth -> depth >= 1 }
                    ?: error("HISTORY_DEPTH must be a positive integer, got '$it'")
            } ?: DEFAULT_HISTORY_DEPTH

            val defaultInterval = RATE_LIMIT_WINDOW_MS * feeds.size / ntaApiKeys.size
            val pollIntervalMs = env("POLL_INTERVAL_MS")?.let {
                it.toLongOrNull()?.takeIf { interval -> interval > 0 }
                    ?: error("POLL_INTERVAL_MS must be a positive integer, got '$it'")
            } ?: defaultInterval

            return ServerConfig(
                port = env("PORT")?.let {
                    it.toIntOrNull()?.takeIf { port -> port in 1..65535 }
                        ?: error("PORT must be 1-65535, got '$it'")
                } ?: 8080,
                serverApiKey = serverApiKey,
                ntaApiKeys = ntaApiKeys,
                feeds = feeds,
                pollIntervalMs = pollIntervalMs,
                historyDepth = historyDepth,
                gtfsStaticPath = env("GTFS_STATIC_PATH"),
                gtfsBaseUrl = env("GTFSR_BASE_URL") ?: DEFAULT_GTFSR_BASE_URL,
                allowUnsafePollRate = env("ALLOW_UNSAFE_POLL_RATE").toBoolean(),
            )
        }

        private fun parseFeeds(raw: String?): List<FeedKind> {
            if (raw.isNullOrBlank()) return listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES)
            val feeds = raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { name -> FeedKind.parse(name) ?: error("unknown feed '$name'; valid: ${FeedKind.entries.joinToString()}") }
                .distinct()
            require(feeds.isNotEmpty()) { "FEEDS must name at least one feed" }
            return feeds
        }
    }
}
