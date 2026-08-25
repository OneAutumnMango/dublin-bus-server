package dev.dublinbustracker.data

import dev.dublinbustracker.core.Stop
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StopDto(val id: String, val code: String, val name: String, val lat: Double, val lon: Double)

@Serializable
private data class TripDto(val routeId: String, val headsign: String)

@Serializable
private data class GtfsStaticDto(
    val stops: List<StopDto>,
    val routes: Map<String, String>,
    val trips: Map<String, TripDto>,
)

/** A trip's route short name and destination, resolved from the bundled static GTFS. */
data class TripInfo(val routeShortName: String, val headsign: String)

class GtfsStaticData private constructor(
    private val stops: List<Stop>,
    private val routes: Map<String, String>,
    private val trips: Map<String, TripDto>,
) {

    fun allStops(): List<Stop> = stops

    fun tripInfo(tripId: String): TripInfo? {
        val trip = trips[tripId] ?: return null
        return TripInfo(routeShortName = routes[trip.routeId] ?: trip.routeId, headsign = trip.headsign)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(jsonText: String): GtfsStaticData {
            val dto = json.decodeFromString<GtfsStaticDto>(jsonText)
            val stops = dto.stops.map { Stop(it.id, it.code, it.name, it.lat, it.lon) }
            return GtfsStaticData(stops, dto.routes, dto.trips)
        }
    }
}
