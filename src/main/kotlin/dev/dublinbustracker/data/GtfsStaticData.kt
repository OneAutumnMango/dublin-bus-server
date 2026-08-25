package dev.dublinbustracker.data

import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.core.Polyline
import dev.dublinbustracker.core.Route
import dev.dublinbustracker.core.Stop
import dev.dublinbustracker.core.Trip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StopDto(val id: String, val code: String, val name: String, val lat: Double, val lon: Double)

@Serializable
private data class RouteDto(
    val id: String,
    val shortName: String,
    val longName: String = "",
    val agencyId: String = "",
)

@Serializable
private data class TripDto(
    val routeId: String,
    val headsign: String = "",
    val directionId: String = "0",
    val shapeId: String = "",
)

@Serializable
private data class BundleDto(
    val version: Int = 1,
    val stops: List<StopDto> = emptyList(),
    val routes: List<RouteDto> = emptyList(),
    val trips: Map<String, TripDto> = emptyMap(),
    val shapes: Map<String, String> = emptyMap(),
    val routePatterns: Map<String, Map<String, List<String>>> = emptyMap(),
    val stopRoutes: Map<String, List<String>> = emptyMap(),
)

/** A trip's route short name and destination, resolved from the static bundle. */
data class TripInfo(val routeShortName: String, val headsign: String)

/**
 * In-memory index over the filtered static GTFS bundle. Built once at startup and never
 * mutated, so request-path lookups are cheap.
 */
class GtfsStaticData private constructor(
    private val stops: List<Stop>,
    private val routes: List<Route>,
    private val trips: Map<String, Trip>,
    private val shapes: Map<String, String>,
    private val routePatterns: Map<String, Map<String, List<String>>>,
    private val stopRoutes: Map<String, List<String>>,
) {
    private val stopsById: Map<String, Stop> = stops.associateBy { it.id }
    private val stopsByCode: Map<String, Stop> = stops.associateBy { it.code }
    private val routesById: Map<String, Route> = routes.associateBy { it.id }
    private val routesByShortNameIndex: Map<String, List<Route>> = routes.groupBy { it.shortName.lowercase() }
    private val shapeIdsByRoute: Map<String, List<Trip>> = trips.values
        .filter { it.shapeId.isNotEmpty() }
        .groupBy { it.routeId }
    private val decodedShapes = HashMap<String, List<LatLon>>()

    fun allStops(): List<Stop> = stops

    fun allRoutes(): List<Route> = routes

    fun stopById(stopId: String): Stop? = stopsById[stopId]

    /** Stop numbers printed at the roadside are the GTFS stop_code, so accept either form. */
    fun stopByIdOrCode(idOrCode: String): Stop? = stopsById[idOrCode] ?: stopsByCode[idOrCode]

    fun routeById(routeId: String): Route? = routesById[routeId]

    /** A bus number like "15" can map to several route variants, so this returns all of them. */
    fun routesByShortName(shortName: String): List<Route> = routesByShortNameIndex[shortName.lowercase()].orEmpty()

    fun resolveRoutes(idOrShortName: String): List<Route> =
        routesById[idOrShortName]?.let { listOf(it) } ?: routesByShortName(idOrShortName)

    fun trip(tripId: String): Trip? = trips[tripId]

    fun tripInfo(tripId: String): TripInfo? {
        val trip = trips[tripId] ?: return null
        return TripInfo(routeShortName = routeShortName(trip.routeId), headsign = trip.headsign)
    }

    fun routeShortName(routeId: String): String = routesById[routeId]?.shortName ?: routeId

    fun routesServingStop(stopId: String): List<String> = stopRoutes[stopId].orEmpty()

    /** Ordered stop ids for a route, keyed by direction id. */
    fun patternsForRoute(routeId: String): Map<String, List<String>> = routePatterns[routeId].orEmpty()

    fun encodedShape(shapeId: String): String? = shapes[shapeId]

    fun shapePoints(shapeId: String): List<LatLon> {
        val encoded = shapes[shapeId] ?: return emptyList()
        return synchronized(decodedShapes) { decodedShapes.getOrPut(shapeId) { Polyline.decode(encoded) } }
    }

    /** The most detailed shape belonging to a route, optionally restricted to one direction. */
    fun shapeIdForRoute(routeId: String, directionId: String? = null): String? =
        shapeIdsByRoute[routeId]
            ?.asSequence()
            ?.filter { directionId == null || it.directionId == directionId }
            ?.maxByOrNull { shapes[it.shapeId]?.length ?: 0 }
            ?.shapeId

    fun stats(): Map<String, Int> = mapOf(
        "stops" to stops.size,
        "routes" to routes.size,
        "trips" to trips.size,
        "shapes" to shapes.size,
        "routePatterns" to routePatterns.size,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(jsonText: String): GtfsStaticData {
            val dto = json.decodeFromString<BundleDto>(jsonText)
            return GtfsStaticData(
                stops = dto.stops.map { Stop(it.id, it.code, it.name, it.lat, it.lon) },
                routes = dto.routes.map { Route(it.id, it.shortName, it.longName, it.agencyId) },
                trips = dto.trips.mapValues { (id, t) -> Trip(id, t.routeId, t.headsign, t.directionId, t.shapeId) },
                shapes = dto.shapes,
                routePatterns = dto.routePatterns,
                stopRoutes = dto.stopRoutes,
            )
        }
    }
}
