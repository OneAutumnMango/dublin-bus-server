package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import dev.dublinbustracker.core.Arrival
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.StaticDataRef
import java.time.Instant
import java.time.ZoneId

private val SCHEDULE_ZONE: ZoneId = ZoneId.of("Europe/Dublin")

/** How far ahead a trip with no live update yet is still shown as "scheduled". */
private const val SCHEDULED_LOOKAHEAD_SECONDS = 90 * 60L

/** Small grace window so a scheduled call doesn't vanish the instant it's technically passed. */
private const val SCHEDULED_PAST_GRACE_SECONDS = 2 * 60L

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
        val tripUpdates = feeds().asSequence().flatMap { it.entityList.asSequence() }
            .filter { it.hasTripUpdate() }.map { it.tripUpdate }.toList()

        val direct = tripUpdates.asSequence()
            .flatMap { update -> arrivalsFrom(update, stopId, now, wantedRouteIds) }
            .distinctBy { it.tripId to it.expectedEpochSeconds }
            .toList()
        val directTripIds = direct.mapTo(HashSet()) { it.tripId }

        // The feed only ever reports a trip's immediate next stop, not its whole remaining
        // route - but a trip that's live *somewhere* still tells us its current delay, which we
        // can apply to this stop's scheduled time. More useful than silence, and more accurate
        // than the bare, un-adjusted timetable, since it reflects the vehicle actually running.
        val cascaded = tripUpdates.asSequence()
            .filter { it.trip.tripId !in directTripIds }
            .mapNotNull { update -> cascadedArrivalFor(update, stopId, now, wantedRouteIds) }
            .toList()

        // Only a trip with no live presence anywhere in the feed (e.g. it hasn't left its
        // terminus yet) falls all the way back to the untouched static timetable.
        val liveTripIds = directTripIds + cascaded.map { it.tripId }
        val scheduled = scheduledArrivalsForStop(stopId, now, wantedRouteIds, liveTripIds)

        return (direct.asSequence() + cascaded.asSequence() + scheduled)
            .sortedBy { it.expectedEpochSeconds }
            .take(limit)
            .toList()
    }

    private fun cascadedArrivalFor(
        update: TripUpdate,
        stopId: String,
        now: Long,
        wantedRouteIds: Set<String>?,
    ): Arrival? {
        val tripId = update.trip.tripId
        val trip = staticData.current.trip(tripId) ?: return null
        if (wantedRouteIds != null && trip.routeId !in wantedRouteIds) return null

        val (reportedStopId, reportedEvent) = update.stopTimeUpdateList.asSequence()
            .mapNotNull { stu -> stu.event()?.let { stu.stopId to it } }
            .firstOrNull() ?: return null

        val delaySeconds = if (reportedEvent.hasDelay()) {
            reportedEvent.delay
        } else {
            val reportedScheduled = closestScheduledEpoch(tripId, reportedStopId, trip.serviceId, reportedEvent.time)
                ?: return null
            (reportedEvent.time - reportedScheduled).toInt()
        }

        val queriedScheduled = closestScheduledEpoch(tripId, stopId, trip.serviceId, now) ?: return null
        val epoch = queriedScheduled + delaySeconds
        val horizon = now + SCHEDULED_LOOKAHEAD_SECONDS
        if (epoch !in (now - SCHEDULED_PAST_GRACE_SECONDS)..horizon) return null

        return Arrival(
            routeShortName = staticData.current.routeShortName(trip.routeId),
            destination = trip.headsign,
            dueMinutes = ((epoch - now) / 60).toInt().coerceAtLeast(0),
            isRealtime = true,
            tripId = tripId,
            routeId = trip.routeId,
            stopId = stopId,
            expectedEpochSeconds = epoch,
            delaySeconds = delaySeconds,
            vehicleId = update.vehicle.id.orNullIfBlank(),
        )
    }

    /**
     * [tripId]'s static scheduled epoch at [stopId], picking whichever service day (today,
     * yesterday or tomorrow) lands closest to [near] - used to line a reported live event up
     * with its schedule slot even near a midnight service-day boundary.
     */
    private fun closestScheduledEpoch(tripId: String, stopId: String, serviceId: String, near: Long): Long? {
        val call = staticData.current.scheduledCallsForStop(stopId).firstOrNull { it.tripId == tripId } ?: return null
        val today = Instant.ofEpochSecond(near).atZone(SCHEDULE_ZONE).toLocalDate()
        return sequenceOf(today, today.minusDays(1), today.plusDays(1))
            .filter { date -> staticData.current.isServiceActive(serviceId, date) }
            .map { date -> date.atStartOfDay(SCHEDULE_ZONE).toEpochSecond() + call.arrivalSecondsSinceMidnight }
            .minByOrNull { kotlin.math.abs(it - near) }
    }

    private fun scheduledArrivalsForStop(
        stopId: String,
        now: Long,
        wantedRouteIds: Set<String>?,
        excludeTripIds: Set<String>,
    ): Sequence<Arrival> {
        val today = Instant.ofEpochSecond(now).atZone(SCHEDULE_ZONE).toLocalDate()
        val horizon = now + SCHEDULED_LOOKAHEAD_SECONDS

        return staticData.current.scheduledCallsForStop(stopId).asSequence()
            .filter { it.tripId !in excludeTripIds }
            .mapNotNull { call ->
                val trip = staticData.current.trip(call.tripId) ?: return@mapNotNull null
                if (wantedRouteIds != null && trip.routeId !in wantedRouteIds) return@mapNotNull null

                // Seconds-since-midnight can exceed 24h for a trip that runs past midnight but is
                // still counted under the previous day's service, so a call might belong to
                // either day - try both and take whichever lands in the window we care about.
                val epoch = sequenceOf(today, today.minusDays(1))
                    .filter { date -> staticData.current.isServiceActive(trip.serviceId, date) }
                    .map { date -> date.atStartOfDay(SCHEDULE_ZONE).toEpochSecond() + call.arrivalSecondsSinceMidnight }
                    .filter { it in (now - SCHEDULED_PAST_GRACE_SECONDS)..horizon }
                    .minOrNull() ?: return@mapNotNull null

                Arrival(
                    routeShortName = staticData.current.routeShortName(trip.routeId),
                    destination = trip.headsign,
                    dueMinutes = ((epoch - now) / 60).toInt().coerceAtLeast(0),
                    isRealtime = false,
                    tripId = call.tripId,
                    routeId = trip.routeId,
                    stopId = stopId,
                    expectedEpochSeconds = epoch,
                )
            }
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
