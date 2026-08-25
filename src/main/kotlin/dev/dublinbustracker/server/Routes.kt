package dev.dublinbustracker.server

import dev.dublinbustracker.data.StopRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class ArrivalResponse(val routeShortName: String, val destination: String, val dueMinutes: Int, val isRealtime: Boolean)

@Serializable
data class StopResponse(val id: String, val code: String, val name: String, val lat: Double, val lon: Double)

private suspend fun ApplicationCall.requireApiKey(expected: String): Boolean {
    if (request.header("X-Api-Key") != expected) {
        respond(HttpStatusCode.Unauthorized, "missing or invalid X-Api-Key")
        return false
    }
    return true
}

fun Routing.configureRoutes(config: ServerConfig, feedCache: FeedCache, stopRepository: StopRepository) {
    get("/health") {
        call.respondText("ok")
    }

    get("/arrivals") {
        if (!call.requireApiKey(config.serverApiKey)) return@get
        val stopId = call.request.queryParameters["stopId"]
        if (stopId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "stopId query parameter is required")
            return@get
        }
        val arrivals = feedCache.arrivalsForStop(stopId)
            .map { ArrivalResponse(it.routeShortName, it.destination, it.dueMinutes, it.isRealtime) }
        call.respond(arrivals)
    }

    get("/stops") {
        if (!call.requireApiKey(config.serverApiKey)) return@get
        val query = call.request.queryParameters["query"].orEmpty()
        val stops = stopRepository.search(query).map { StopResponse(it.id, it.code, it.name, it.lat, it.lon) }
        call.respond(stops)
    }
}
