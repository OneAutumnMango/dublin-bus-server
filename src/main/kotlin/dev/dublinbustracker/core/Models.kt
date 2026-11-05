package dev.dublinbustracker.core

data class LatLon(val lat: Double, val lon: Double)

data class Stop(
    val id: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

data class Route(
    val id: String,
    val shortName: String,
    val longName: String,
    val agencyId: String,
)

/** A scheduled trip, from the static feed. */
data class Trip(
    val id: String,
    val routeId: String,
    val headsign: String,
    val directionId: String,
    val shapeId: String,
    val serviceId: String = "",
)

data class Arrival(
    val routeShortName: String,
    val destination: String,
    val dueMinutes: Int,
    val isRealtime: Boolean,
    val tripId: String = "",
    val routeId: String = "",
    val stopId: String = "",
    val expectedEpochSeconds: Long = 0,
    val delaySeconds: Int? = null,
    val vehicleId: String? = null,
    /** Its predicted time has gone by without the feed moving on, so it may already have left. */
    val possiblyPassed: Boolean = false,
)

/** Where a bus is right now, from the Vehicles feed. */
data class VehiclePosition(
    val vehicleId: String,
    val label: String,
    val tripId: String,
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val directionId: String,
    val position: LatLon,
    val bearing: Float?,
    val speed: Float?,
    val timestampEpochSeconds: Long,
    val currentStopId: String?,
    val currentStatus: String?,
)

/** One historical position for a vehicle, reconstructed from retained feed snapshots. */
data class VehicleFix(
    val timestampEpochSeconds: Long,
    val position: LatLon,
    val bearing: Float?,
    val speed: Float?,
)

data class ServiceAlert(
    val id: String,
    val cause: String,
    val effect: String,
    val header: String,
    val description: String,
    val url: String,
    val routeIds: List<String>,
    val stopIds: List<String>,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)
