package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedEntity
import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.core.headingAlongPath
import dev.dublinbustracker.core.VehicleFix
import dev.dublinbustracker.core.VehiclePosition
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.StaticDataRef

data class BoundingBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(point: LatLon): Boolean =
        point.lat in minLat..maxLat && point.lon in minLon..maxLon

    companion object {
        /** Parses "minLat,minLon,maxLat,maxLon". */
        fun parse(raw: String): BoundingBox? {
            val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != 4) return null
            val (minLat, minLon, maxLat, maxLon) = parts
            if (minLat > maxLat || minLon > maxLon) return null
            return BoundingBox(minLat, minLon, maxLat, maxLon)
        }
    }
}

/** A vehicle fix older than this no longer counts as the bus having GPS. */
private const val FRESH_FIX_SECONDS = 180L

/** Reads live bus positions, and reconstructs recent tracks from the retained backlog. */
class VehicleService(
    private val store: FeedStore,
    private val staticData: StaticDataRef,
) {
    fun allVehicles(): List<VehiclePosition> =
        latestEntities().mapNotNull { it.toVehiclePosition() }

    fun vehiclesForRoute(routeIdOrShortName: String): List<VehiclePosition> {
        val wanted = staticData.current.resolveRoutes(routeIdOrShortName).map { it.id }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return allVehicles().filter { it.routeId in wanted }
    }

    fun vehicleById(vehicleId: String): VehiclePosition? =
        allVehicles().firstOrNull { it.vehicleId == vehicleId }

    fun vehicleForTrip(tripId: String): VehiclePosition? =
        allVehicles().firstOrNull { it.tripId == tripId }

    /**
     * The vehicle's recent path, one fix per retained snapshot that mentioned it. How far back
     * this reaches is bounded by the configured history depth times the poll interval.
     */
    fun vehicleHistory(vehicleId: String): List<VehicleFix> =
        store.history(FeedKind.VEHICLES)
            .asSequence()
            .flatMap { snapshot -> snapshot.feed.entityList.asSequence() }
            .filter { it.hasVehicle() && it.vehicle.vehicle.id == vehicleId && it.vehicle.hasPosition() }
            .map { entity ->
                val position = entity.vehicle.position
                VehicleFix(
                    timestampEpochSeconds = entity.vehicle.timestamp,
                    position = LatLon(position.latitude.toDouble(), position.longitude.toDouble()),
                    bearing = if (position.hasBearing()) position.bearing else null,
                    speed = if (position.hasSpeed()) position.speed else null,
                )
            }
            .distinctBy { it.timestampEpochSeconds }
            .sortedBy { it.timestampEpochSeconds }
            .toList()

    /** Latest fix time per trip, built once per pair of VEHICLES/COMBINED snapshots (compared by identity). */
    private class FixIndex(val vehicles: FeedSnapshot?, val combined: FeedSnapshot?, val latestByTrip: Map<String, Long>)

    @Volatile private var fixIndex: FixIndex? = null

    /** How many times the fix index was rebuilt; lets tests check it is once per snapshot. */
    @Volatile internal var fixIndexBuilds = 0
        private set

    /** True when a vehicle reported [tripId] with a fix under [FRESH_FIX_SECONDS] old at [now]. */
    fun hasFreshFix(tripId: String, now: Long): Boolean {
        val vehicles = store.latest(FeedKind.VEHICLES)
        val combined = store.latest(FeedKind.COMBINED)
        val index = fixIndex?.takeIf { it.vehicles === vehicles && it.combined === combined }
            ?: FixIndex(
                vehicles, combined,
                latestEntities().filter { it.vehicle.hasPosition() && it.vehicle.trip.tripId.isNotEmpty() }
                    .groupBy({ it.vehicle.trip.tripId }, { it.vehicle.timestamp })
                    .mapValues { (_, timestamps) -> timestamps.max() },
            ).also { fixIndex = it; fixIndexBuilds++ }
        val timestamp = index.latestByTrip[tripId] ?: return false
        return timestamp > 0 && timestamp > now - FRESH_FIX_SECONDS
    }

    private fun headingOn(shapeId: String, here: LatLon): Float? =
        if (shapeId.isEmpty()) null else headingAlongPath(staticData.current.shapePoints(shapeId), here)

    private fun latestEntities(): List<FeedEntity> =
        listOfNotNull(store.latest(FeedKind.VEHICLES), store.latest(FeedKind.COMBINED))
            .flatMap { it.feed.entityList }
            .filter { it.hasVehicle() }
            .distinctBy { it.vehicle.vehicle.id }

    private fun FeedEntity.toVehiclePosition(): VehiclePosition? {
        val vehicle = vehicle
        if (!vehicle.hasPosition()) return null
        val trip = staticData.current.trip(vehicle.trip.tripId)
        val here = LatLon(vehicle.position.latitude.toDouble(), vehicle.position.longitude.toDouble())
        val routeId = trip?.routeId ?: vehicle.trip.routeId
        return VehiclePosition(
            vehicleId = vehicle.vehicle.id,
            label = vehicle.vehicle.label,
            tripId = vehicle.trip.tripId,
            routeId = routeId,
            routeShortName = staticData.current.routeShortName(routeId),
            headsign = trip?.headsign.orEmpty(),
            directionId = trip?.directionId ?: vehicle.trip.directionId.toString(),
            position = LatLon(vehicle.position.latitude.toDouble(), vehicle.position.longitude.toDouble()),
            // Most buses report no bearing, so fall back to the way their trip's path runs where they are.
            bearing = if (vehicle.position.hasBearing()) vehicle.position.bearing else trip?.let { headingOn(it.shapeId, here) },
            speed = if (vehicle.position.hasSpeed()) vehicle.position.speed else null,
            timestampEpochSeconds = vehicle.timestamp,
            currentStopId = vehicle.stopId.orNullIfBlank(),
            currentStatus = if (vehicle.hasCurrentStatus()) vehicle.currentStatus.name else null,
        )
    }
}
