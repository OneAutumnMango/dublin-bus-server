package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerConfigTest {
    private val required = mapOf("SERVER_API_KEY" to "secret", "GTFSR_API_KEY" to "nta-1")

    private fun config(vararg overrides: Pair<String, String?>): ServerConfig {
        val env = required + overrides.toMap()
        return ServerConfig.fromEnv { env[it] }
    }

    @Test
    fun `defaults to trip updates and vehicles at the safe rate for one key`() {
        val config = config()
        assertEquals(listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES), config.feeds)
        // Two feeds on one key means one cycle per two minutes.
        assertEquals(120_000L, config.pollIntervalMs)
        assertEquals(120_000L, config.minimumSafePollIntervalMs)
        assertEquals(8080, config.port)
        assertEquals(ServerConfig.DEFAULT_HISTORY_DEPTH, config.historyDepth)
    }

    @Test
    fun `a second key halves the safe interval`() {
        val config = config("GTFSR_API_KEY_SECONDARY" to "nta-2")
        assertEquals(2, config.ntaApiKeys.size)
        assertEquals(60_000L, config.minimumSafePollIntervalMs)
        assertEquals(60_000L, config.pollIntervalMs)
    }

    @Test
    fun `a single feed on two keys polls every thirty seconds`() {
        val config = config("FEEDS" to "TRIP_UPDATES", "GTFSR_API_KEY_SECONDARY" to "nta-2")
        assertEquals(30_000L, config.pollIntervalMs)
        assertEquals(2.0, config.upstreamCallsPerMinute)
    }

    @Test
    fun `feeds are parsed by name or by feed path, case-insensitively`() {
        val config = config("FEEDS" to "trip_updates, Vehicles ,gtfsr")
        assertEquals(listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES, FeedKind.COMBINED), config.feeds)
    }

    @Test
    fun `duplicate feeds are collapsed so they are not polled twice`() {
        assertEquals(listOf(FeedKind.TRIP_UPDATES), config("FEEDS" to "TRIP_UPDATES,TRIP_UPDATES").feeds)
    }

    @Test
    fun `blank values are treated as unset rather than invalid`() {
        val config = config(
            "POLL_INTERVAL_MS" to "",
            "HISTORY_DEPTH" to "",
            "PORT" to "",
            "GTFS_STATIC_PATH" to "",
            "FEEDS" to "",
        )
        assertEquals(120_000L, config.pollIntervalMs)
        assertEquals(ServerConfig.DEFAULT_HISTORY_DEPTH, config.historyDepth)
        assertEquals(8080, config.port)
        assertNull(config.gtfsStaticPath)
    }

    @Test
    fun `explicit overrides are honoured`() {
        val config = config(
            "PORT" to "9000",
            "HISTORY_DEPTH" to "120",
            "POLL_INTERVAL_MS" to "180000",
            "GTFS_STATIC_PATH" to "/data/gtfs.json",
        )
        assertEquals(9000, config.port)
        assertEquals(120, config.historyDepth)
        assertEquals(180_000L, config.pollIntervalMs)
        assertEquals("/data/gtfs.json", config.gtfsStaticPath)
    }

    @Test
    fun `polling faster than the rate limit is reported as a warning`() {
        val config = config("POLL_INTERVAL_MS" to "1000")
        val warnings = config.validate()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("below the safe minimum"), warnings.single())
    }

    @Test
    fun `a safe poll rate produces no warnings`() {
        assertEquals(emptyList(), config("POLL_INTERVAL_MS" to "120000").validate())
    }

    @Test
    fun `missing server api key is fatal`() {
        val error = assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnv { mapOf("GTFSR_API_KEY" to "nta-1")[it] }
        }
        assertTrue(error.message!!.contains("SERVER_API_KEY"))
    }

    @Test
    fun `missing nta key is fatal`() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv { mapOf("SERVER_API_KEY" to "secret")[it] }
        }
    }

    @Test
    fun `unknown feed name is fatal`() {
        val error = assertFailsWith<IllegalStateException> { config("FEEDS" to "TRAINS") }
        assertTrue(error.message!!.contains("unknown feed"))
    }

    @Test
    fun `non-numeric or non-positive tuning values are fatal`() {
        assertFailsWith<IllegalStateException> { config("POLL_INTERVAL_MS" to "soon") }
        assertFailsWith<IllegalStateException> { config("POLL_INTERVAL_MS" to "0") }
        assertFailsWith<IllegalStateException> { config("HISTORY_DEPTH" to "0") }
        assertFailsWith<IllegalStateException> { config("PORT" to "70000") }
    }
}
