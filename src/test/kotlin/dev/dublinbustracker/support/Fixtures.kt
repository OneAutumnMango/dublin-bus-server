package dev.dublinbustracker.support

import com.google.transit.realtime.GtfsRealtime.Alert
import com.google.transit.realtime.GtfsRealtime.EntitySelector
import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.FeedHeader
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.Position
import com.google.transit.realtime.GtfsRealtime.TimeRange
import com.google.transit.realtime.GtfsRealtime.TranslatedString
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor
import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.core.Polyline

/** Builders for GTFS-Realtime fixtures, so tests read as data rather than protobuf ceremony. */
object Fixtures {

    fun feedOf(vararg entities: FeedEntity): FeedMessage =
        FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addAllEntity(entities.toList())
            .build()

    fun stopTimeUpdate(
        stopId: String,
        epochSeconds: Long,
        stopSequence: Int = 0,
        delaySeconds: Int? = null,
        useDeparture: Boolean = false,
    ): TripUpdate.StopTimeUpdate {
        val event = TripUpdate.StopTimeEvent.newBuilder().setTime(epochSeconds)
        if (delaySeconds != null) event.delay = delaySeconds
        val builder = TripUpdate.StopTimeUpdate.newBuilder()
            .setStopId(stopId)
            .setStopSequence(stopSequence)
        return if (useDeparture) builder.setDeparture(event).build() else builder.setArrival(event).build()
    }

    fun tripUpdateEntity(
        entityId: String,
        tripId: String,
        routeId: String = "",
        vehicleId: String? = null,
        stopTimeUpdates: List<TripUpdate.StopTimeUpdate> = emptyList(),
    ): FeedEntity {
        val trip = TripDescriptor.newBuilder().setTripId(tripId)
        if (routeId.isNotEmpty()) trip.routeId = routeId
        val update = TripUpdate.newBuilder()
            .setTrip(trip)
            .addAllStopTimeUpdate(stopTimeUpdates)
        if (vehicleId != null) update.vehicle = VehicleDescriptor.newBuilder().setId(vehicleId).build()
        return FeedEntity.newBuilder().setId(entityId).setTripUpdate(update).build()
    }

    fun vehicleEntity(
        entityId: String,
        vehicleId: String,
        tripId: String,
        lat: Double,
        lon: Double,
        timestampEpochSeconds: Long,
        routeId: String = "",
        bearing: Float? = null,
        speed: Float? = null,
        label: String = "",
        currentStopId: String? = null,
    ): FeedEntity {
        val position = Position.newBuilder()
            .setLatitude(lat.toFloat())
            .setLongitude(lon.toFloat())
        if (bearing != null) position.bearing = bearing
        if (speed != null) position.speed = speed

        val trip = TripDescriptor.newBuilder().setTripId(tripId)
        if (routeId.isNotEmpty()) trip.routeId = routeId

        val vehicle = com.google.transit.realtime.GtfsRealtime.VehiclePosition.newBuilder()
            .setVehicle(VehicleDescriptor.newBuilder().setId(vehicleId).setLabel(label))
            .setTrip(trip)
            .setPosition(position)
            .setTimestamp(timestampEpochSeconds)
        if (currentStopId != null) vehicle.stopId = currentStopId

        return FeedEntity.newBuilder().setId(entityId).setVehicle(vehicle).build()
    }

    fun alertEntity(
        entityId: String,
        header: String,
        description: String = "",
        routeIds: List<String> = emptyList(),
        stopIds: List<String> = emptyList(),
        startEpochSeconds: Long? = null,
        endEpochSeconds: Long? = null,
    ): FeedEntity {
        val alert = Alert.newBuilder()
            .setCause(Alert.Cause.CONSTRUCTION)
            .setEffect(Alert.Effect.DETOUR)
            .setHeaderText(translated(header))
            .setDescriptionText(translated(description))

        routeIds.forEach { alert.addInformedEntity(EntitySelector.newBuilder().setRouteId(it)) }
        stopIds.forEach { alert.addInformedEntity(EntitySelector.newBuilder().setStopId(it)) }

        if (startEpochSeconds != null || endEpochSeconds != null) {
            val range = TimeRange.newBuilder()
            if (startEpochSeconds != null) range.start = startEpochSeconds
            if (endEpochSeconds != null) range.end = endEpochSeconds
            alert.addActivePeriod(range)
        }
        return FeedEntity.newBuilder().setId(entityId).setAlert(alert).build()
    }

    private fun translated(text: String): TranslatedString =
        TranslatedString.newBuilder()
            .addTranslation(TranslatedString.Translation.newBuilder().setText(text).setLanguage("en"))
            .build()

    /** A small but structurally complete v2 static bundle. */
    fun staticBundleJson(): String {
        val shapeOne = Polyline.encode(
            listOf(LatLon(53.3441, -6.2578), LatLon(53.3450, -6.2600), LatLon(53.3468, -6.2951)),
        )
        val shapeTwo = Polyline.encode(listOf(LatLon(53.3000, -6.2000), LatLon(53.3100, -6.2100)))
        return """
            {
              "version": 2,
              "stops": [
                {"id":"s1","code":"1001","name":"Trinity College","lat":53.3441,"lon":-6.2578},
                {"id":"s2","code":"1002","name":"Heuston Station","lat":53.3468,"lon":-6.2951},
                {"id":"s3","code":"7788","name":"Clare Hall","lat":53.4000,"lon":-6.1700}
              ],
              "routes": [
                {"id":"r1","shortName":"15","longName":"Ballycullen - Merrion Square","agencyId":"a1"},
                {"id":"r2","shortName":"15","longName":"Ballycullen - Ringsend","agencyId":"a1"},
                {"id":"r3","shortName":"27","longName":"Jobstown - Clare Hall","agencyId":"a1"}
              ],
              "trips": {
                "t1": {"routeId":"r1","headsign":"Merrion Square","directionId":"0","shapeId":"sh1"},
                "t2": {"routeId":"r3","headsign":"Clare Hall","directionId":"1","shapeId":"sh2"},
                "t3": {"routeId":"r1","headsign":"Ballycullen","directionId":"1","shapeId":""}
              },
              "shapes": {"sh1":"$shapeOne","sh2":"$shapeTwo"},
              "routePatterns": {"r1": {"0": ["s1","s2"], "1": ["s2","s1"]}},
              "stopRoutes": {"s1":["15","27"],"s2":["15"],"s3":["27"]}
            }
        """.trimIndent()
    }
}
