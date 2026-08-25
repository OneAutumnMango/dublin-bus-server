package dev.dublinbustracker.server

import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.NtaArrivalsRepository
import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.data.StopRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

fun main() {
    val config = ServerConfig.fromEnv()
    val gtfsStaticData = GtfsStaticData.parse(
        requireNotNull(object {}.javaClass.classLoader.getResourceAsStream("gtfs_static.json")) {
            "gtfs_static.json not found on the classpath - run scripts/fetch-gtfs-data.sh first"
        }.bufferedReader().readText(),
    )
    val stopRepository = StopRepository(gtfsStaticData)
    val feedCache = FeedCache(NtaArrivalsRepository(RealtimeGtfsClient(config.ntaApiKeys), gtfsStaticData))

    // Serve real data from the first request onward instead of an empty cache.
    runBlocking { feedCache.refresh() }

    embeddedServer(Netty, port = config.port) {
        configureServer(config, feedCache, stopRepository)
    }.start(wait = true)
}

fun Application.configureServer(config: ServerConfig, feedCache: FeedCache, stopRepository: StopRepository) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    feedCache.startPolling(this)
    routing { configureRoutes(config, feedCache, stopRepository) }
}
