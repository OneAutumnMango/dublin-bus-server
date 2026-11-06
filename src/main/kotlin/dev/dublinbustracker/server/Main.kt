package dev.dublinbustracker.server

import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.data.StaticDataRef
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

private const val STATIC_RESOURCE_NAME = "gtfs_static.json"

fun main() {
    val logger = LoggerFactory.getLogger("dev.dublinbustracker.server.Main")
    val notifier = NtfyNotifier.fromEnv()

    try {
        val config = ServerConfig.fromEnv()

        config.validate().forEach { warning ->
            if (config.allowUnsafePollRate) {
                logger.warn(warning)
            } else {
                error("$warning. Set ALLOW_UNSAFE_POLL_RATE=true to override.")
            }
        }

        val staticData = StaticDataRef(loadStaticData(config.gtfsStaticPath))
        logger.info("Loaded static GTFS: {}", staticData.current.stats())

        val httpClient = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        val client = RealtimeGtfsClient(config.ntaApiKeys, httpClient, config.gtfsBaseUrl)
        val services = AppServices.build(config, staticData, client, notifier)

        logger.info(
            "Polling {} every {}ms ({} upstream calls/min across {} key(s)); retaining {} snapshots per feed",
            config.feeds.joinToString(),
            config.pollIntervalMs,
            "%.2f".format(config.upstreamCallsPerMinute),
            config.ntaApiKeys.size,
            config.historyDepth,
        )

        // Warm the cache so the first client request is served real data.
        runBlocking { services.poller.refreshAll() }

        embeddedServer(Netty, port = config.port) { configureServer(services) }.start(wait = true)
    } catch (e: Throwable) {
        notifier.notify(NtfyLevel.ERROR, "Dublin Bus Server failed to start", e.message ?: e.toString())
        throw e
    }
}

fun loadStaticData(path: String?): GtfsStaticData {
    if (path != null) {
        val file = File(path)
        require(file.isFile) {
            "GTFS_STATIC_PATH=$path not found - run scripts/fetch-gtfs-data.sh to generate it"
        }
        return file.inputStream().buffered().use { GtfsStaticData.parse(it) }
    }
    val stream = GtfsStaticData::class.java.classLoader.getResourceAsStream(STATIC_RESOURCE_NAME)
        ?: error("$STATIC_RESOURCE_NAME missing from the classpath - run scripts/fetch-gtfs-data.sh first")
    return stream.buffered().use { GtfsStaticData.parse(it) }
}

fun Application.configureServer(services: AppServices) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false }) }
    install(CallLogging)
    services.poller.start(this)
    services.staticDataRefresher.start(this)
    routing { configureRoutes(services) }
}
