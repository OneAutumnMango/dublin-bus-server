package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import dev.dublinbustracker.core.Arrival
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.StaticDataRef

/** One live stop call on a trip, used to render a trip's progress along its route. */
data class TripStopCall(
    val stopSequence: Int,
    val stopId: String,
    val stopName: String,
    val expectedEpochSeconds: Long,
    val delaySeconds: Int?,
)

/**
 * Answers stop- and trip-level questions from the cached TripUpdates feed. Every query reads
 * the one retained snapshot, so serving a thousand stops costs no extra upstream calls.
 */
class ArrivalsService(
    private val store: FeedStore,
    private val staticData: StaticDataRef,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private fun feeds(): List<FeedMessage> =
        listOfNotNull(store.latest(FeedKind.TRIP_UPDATES), store.latest(FeedKind.COMBINED)).map { it.feed }

    fun arrivalsForStop(stopId: String, routeFilter: String? = null, limit: Int = 50): List<Arrival> {
        val now = nowEpochSeconds()
        val wantedRouteIds = routeFilter?.let { filter -> staticData.current.resolveRoutes(filter).map { it.id }.toSet() }

        return feeds()
            .asSequence()
            .flatMap { it.entityList.asSequence() }
            .filter { it.hasTripUpdate() }
            .map { it.tripUpdate }
            .flatMap { update -> arrivalsFrom(update, stopId, now, wantedRouteIds) }
            .distinctBy { it.tripId to it.expectedEpochSeconds }
            .sortedBy { it.expectedEpochSeconds }
            .take(limit)
            .toList()
    }

    private fun arrivalsFrom(
        update: TripUpdate,
        stopId: String,
        now: Long,
        wantedRouteIds: Set<String>?,
    ): Sequence<Arrival> {
        val trip = staticData.current.trip(update.trip.tripId)
        val routeId = trip?.routeId ?: update.trip.routeId
        if (wantedRouteIds != null && routeId !in wantedRouteIds) return emptySequence()

        return update.stopTimeUpdateList.asSequence()
            .filter { it.stopId == stopId }
            .mapNotNull { stopTimeUpdate ->
                val event = stopTimeUpdate.event() ?: return@mapNotNull null
                Arrival(
                    routeShortName = staticData.current.routeShortName(routeId),
                    destination = trip?.headsign.orEmpty(),
                    dueMinutes = ((event.time - now) / 60).toInt().coerceAtLeast(0),
                    isRealtime = true,
                    tripId = update.trip.tripId,
                    routeId = routeId,
                    stopId = stopId,
                    expectedEpochSeconds = event.time,
                    delaySeconds = if (event.hasDelay()) event.delay else null,
                    vehicleId = update.vehicle.id.orNullIfBlank(),
                )
            }
    }

    /** Every remaining live stop call for a trip, in order. */
    fun tripStopCalls(tripId: String): List<TripStopCall> {
        val update = feeds()
            .asSequence()
            .flatMap { it.entityList.asSequence() }
            .filter { it.hasTripUpdate() && it.tripUpdate.trip.tripId == tripId }
            .map { it.tripUpdate }
            .firstOrNull()
            ?: return emptyList()

        return update.stopTimeUpdateList.mapNotNull { stopTimeUpdate ->
            val event = stopTimeUpdate.event() ?: return@mapNotNull null
            TripStopCall(
                stopSequence = stopTimeUpdate.stopSequence,
                stopId = stopTimeUpdate.stopId,
                stopName = staticData.current.stopById(stopTimeUpdate.stopId)?.name.orEmpty(),
                expectedEpochSeconds = event.time,
                delaySeconds = if (event.hasDelay()) event.delay else null,
            )
        }.sortedBy { it.stopSequence }
    }

    /** Trip ids currently reporting updates, optionally limited to one route. */
    fun activeTripIds(routeFilter: String? = null): List<String> {
        val wantedRouteIds = routeFilter?.let { filter -> staticData.current.resolveRoutes(filter).map { it.id }.toSet() }
        return feeds()
            .asSequence()
            .flatMap { it.entityList.asSequence() }
            .filter { it.hasTripUpdate() }
            .map { it.tripUpdate.trip.tripId }
            .filter { tripId ->
                wantedRouteIds == null || staticData.current.trip(tripId)?.routeId in wantedRouteIds
            }
            .distinct()
            .toList()
    }

    /** Prefers arrival time, falling back to departure, matching how the NTA populates the feed. */
    private fun TripUpdate.StopTimeUpdate.event(): TripUpdate.StopTimeEvent? = when {
        hasArrival() && arrival.hasTime() -> arrival
        hasDeparture() && departure.hasTime() -> departure
        else -> null
    }
}
