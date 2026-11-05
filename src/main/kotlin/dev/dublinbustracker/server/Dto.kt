package dev.dublinbustracker.server

import dev.dublinbustracker.core.Arrival
import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.core.Route
import dev.dublinbustracker.core.ServiceAlert
import dev.dublinbustracker.core.Stop
import dev.dublinbustracker.core.VehicleFix
import dev.dublinbustracker.core.VehiclePosition
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class StopResponse(
    val id: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val routes: List<String> = emptyList(),
)

@Serializable
data class ArrivalResponse(
    val routeShortName: String,
    val destination: String,
    val dueMinutes: Int,
    val isRealtime: Boolean,
    val tripId: String = "",
    val routeId: String = "",
    val expectedEpochSeconds: Long = 0,
    val delaySeconds: Int? = null,
    val vehicleId: String? = null,
    val possiblyPassed: Boolean = false,
)

@Serializable
data class RouteResponse(
    val id: String,
    val shortName: String,
    val longName: String,
    val agencyId: String,
)

@Serializable
data class RouteStopsResponse(
    val routeId: String,
    val shortName: String,
    val directions: Map<String, List<StopResponse>>,
)

@Serializable
data class ShapeResponse(
    val shapeId: String,
    val pointCount: Int,
    val encodedPolyline: String,
    val points: List<List<Double>>? = null,
)

@Serializable
data class VehicleResponse(
    val vehicleId: String,
    val label: String,
    val tripId: String,
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val directionId: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float? = null,
    val speed: Float? = null,
    val timestampEpochSeconds: Long,
    val currentStopId: String? = null,
    val currentStatus: String? = null,
)

@Serializable
data class VehicleFixResponse(
    val timestampEpochSeconds: Long,
    val lat: Double,
    val lon: Double,
    val bearing: Float? = null,
    val speed: Float? = null,
)

@Serializable
data class VehicleHistoryResponse(
    val vehicleId: String,
    val fixCount: Int,
    val fixes: List<VehicleFixResponse>,
    val encodedPolyline: String,
)

@Serializable
data class TripStopCallResponse(
    val stopSequence: Int,
    val stopId: String,
    val stopName: String,
    val expectedEpochSeconds: Long,
    val delaySeconds: Int? = null,
)

@Serializable
data class TripResponse(
    val tripId: String,
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val directionId: String,
    val shapeId: String,
    val vehicle: VehicleResponse? = null,
    val stopCalls: List<TripStopCallResponse> = emptyList(),
)

@Serializable
data class AlertResponse(
    val id: String,
    val cause: String,
    val effect: String,
    val header: String,
    val description: String,
    val url: String,
    val routeIds: List<String>,
    val stopIds: List<String>,
    val startEpochSeconds: Long? = null,
    val endEpochSeconds: Long? = null,
)

@Serializable
data class FeedStatusResponse(
    val feed: String,
    val lastFetchedEpochMs: Long?,
    val ageSeconds: Long?,
    val entityCount: Int,
    val retainedSnapshots: Int,
)

@Serializable
data class StatusResponse(
    val pollIntervalMs: Long,
    val minimumSafePollIntervalMs: Long,
    val upstreamCallsPerMinute: Double,
    val historyDepth: Int,
    val apiKeyCount: Int,
    val configuredFeeds: List<String>,
    val upstreamRequests: Long,
    val refreshSuccesses: Long,
    val refreshFailures: Long,
    val staticData: Map<String, Int>,
    val feeds: List<FeedStatusResponse>,
)

fun Stop.toResponse(routes: List<String> = emptyList()) =
    StopResponse(id, code, name, lat, lon, routes)

fun Arrival.toResponse() = ArrivalResponse(
    routeShortName = routeShortName,
    destination = destination,
    dueMinutes = dueMinutes,
    isRealtime = isRealtime,
    tripId = tripId,
    routeId = routeId,
    expectedEpochSeconds = expectedEpochSeconds,
    delaySeconds = delaySeconds,
    vehicleId = vehicleId,
    possiblyPassed = possiblyPassed,
)

fun Route.toResponse() = RouteResponse(id, shortName, longName, agencyId)

fun VehiclePosition.toResponse() = VehicleResponse(
    vehicleId = vehicleId,
    label = label,
    tripId = tripId,
    routeId = routeId,
    routeShortName = routeShortName,
    headsign = headsign,
    directionId = directionId,
    lat = position.lat,
    lon = position.lon,
    bearing = bearing,
    speed = speed,
    timestampEpochSeconds = timestampEpochSeconds,
    currentStopId = currentStopId,
    currentStatus = currentStatus,
)

fun VehicleFix.toResponse() = VehicleFixResponse(timestampEpochSeconds, position.lat, position.lon, bearing, speed)

fun ServiceAlert.toResponse() = AlertResponse(
    id, cause, effect, header, description, url, routeIds, stopIds, startEpochSeconds, endEpochSeconds,
)

fun TripStopCall.toResponse() = TripStopCallResponse(stopSequence, stopId, stopName, expectedEpochSeconds, delaySeconds)

fun List<LatLon>.toPointPairs(): List<List<Double>> = map { listOf(it.lat, it.lon) }
