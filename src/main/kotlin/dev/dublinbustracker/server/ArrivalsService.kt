package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate
import dev.dublinbustracker.core.Arrival
import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.data.StaticDataRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val SCHEDULE_ZONE: ZoneId = ZoneId.of("Europe/Dublin")

/** How far ahead a trip with no live update yet is still shown as "scheduled". */
private const val SCHEDULED_LOOKAHEAD_SECONDS = 90 * 60L

/** Small grace window so an arrival doesn't vanish the instant it's technically passed. */
private const val SCHEDULED_PAST_GRACE_SECONDS = 2 * 60L

/**
 * A live prediction that has gone by stays listed (flagged) for this long, since the bus may
 * just be running late. Past it, the bus is long gone - the feed keeps passed stops around.
 */
private const val PAST_VISIBLE_SECONDS = 15 * 60L

/**
 * GTFS times count from "noon minus 12h" of the service day, not from local midnight - the two
 * differ by an hour on the days the clocks change, which would shift every call that day.
 */
private fun serviceDayStart(date: LocalDate): Long =
    date.atTime(12, 0).atZone(SCHEDULE_ZONE).minusHours(12).toEpochSecond()

private fun TripUpdate.isCancelled(): Boolean =
    trip.scheduleRelationship == TripDescriptor.ScheduleRelationship.CANCELED

private fun TripUpdate.StopTimeUpdate.isSkipped(): Boolean =
    scheduleRelationship == TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

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
        val allUpdates = feeds().asSequence().flatMap { it.entityList.asSequence() }
            .filter { it.hasTripUpdate() }.map { it.tripUpdate }.toList()

        // A cancelled trip, or one that skips this very stop, must show nowhere - not even as a
        // cascaded estimate or a "(scheduled)" fallback from the static timetable.
        val suppressedTripIds = allUpdates.asSequence()
            .filter { it.isCancelled() || it.stopTimeUpdateList.any { stu -> stu.stopId == stopId && stu.isSkipped() } }
            .mapTo(HashSet()) { it.trip.tripId }
        val tripUpdates = allUpdates.filter { it.trip.tripId !in suppressedTripIds }

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
            .flatMap { update -> cascadedArrivalsFor(update, stopId, now, wantedRouteIds) }
            .toList()

        // Only a trip with no live presence anywhere in the feed (e.g. it hasn't left its
        // terminus yet) falls all the way back to the untouched static timetable.
        // A trip that reported anywhere also counts as live when its cascade lands nowhere (the
        // bus has already passed this stop), so the timetable can't resurrect it as "(scheduled)".
        val liveTripIds = directTripIds + tripUpdates.filter { it.firstReport(now) != null }.map { it.trip.tripId }
        val scheduled = scheduledArrivalsForStop(stopId, now, wantedRouteIds, liveTripIds + suppressedTripIds)

        return (direct.asSequence() + cascaded.asSequence() + scheduled)
            // Ones that may already have left go last, so they never push a real bus down the list.
            .sortedWith(compareBy({ it.possiblyPassed }, { it.expectedEpochSeconds }))
            .take(limit)
            .toList()
    }

    /**
     * Applies the live trip's current delay to each of its scheduled calls at [stopId] that
     * come after the stop it last reported (a trip that loops back through a stop has several).
     */
    private fun cascadedArrivalsFor(
        update: TripUpdate,
        stopId: String,
        now: Long,
        wantedRouteIds: Set<String>?,
    ): List<Arrival> {
        val tripId = update.trip.tripId
        val trip = staticData.current.trip(tripId) ?: return emptyList()
        if (wantedRouteIds != null && trip.routeId !in wantedRouteIds) return emptyList()

        val (reportedStopId, reportedEvent) = update.firstReport(now) ?: return emptyList()

        val explicitDelay = reportedEvent.takeIf { it.hasDelay() }?.delay
        val reportedScheduled = closestScheduledEpoch(
            tripId, reportedStopId, trip.serviceId, reportedEvent.time - (explicitDelay ?: 0),
        )
        val delaySeconds = explicitDelay
            ?: (reportedEvent.time - (reportedScheduled ?: return emptyList())).toInt()

        // Delay only flows forward along the trip. Times never decrease along a trip, so a
        // call scheduled before the reported stop has already been passed.
        // shortcut: two stops with an identical scheduled time can't be told apart without
        // stop_sequence, so one just passed may linger inside the 2 min grace; store the
        // sequence in the bundle if that ever shows up.
        val isAhead: (Long) -> Boolean = when {
            reportedScheduled == null -> { _ -> true }
            stopId == reportedStopId -> { epoch -> epoch > reportedScheduled }
            else -> { epoch -> epoch >= reportedScheduled }
        }

        val window = (now - PAST_VISIBLE_SECONDS)..(now + SCHEDULED_LOOKAHEAD_SECONDS)
        return scheduledEpochs(tripId, stopId, trip.serviceId, reportedScheduled ?: now)
            .filter(isAhead)
            .map { it + delaySeconds }
            .filter { it in window }
            .map { epoch ->
                Arrival(
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
                    possiblyPassed = epoch < now - SCHEDULED_PAST_GRACE_SECONDS,
                )
            }
    }

    /**
     * Every epoch [tripId] is scheduled at [stopId] on the service days around [near] (today,
     * yesterday, tomorrow - a trip can belong to either side of midnight). A trip that calls at
     * a stop more than once yields one epoch per visit.
     */
    private fun scheduledEpochs(tripId: String, stopId: String, serviceId: String, near: Long): List<Long> {
        val calls = staticData.current.scheduledCallsForTripAtStop(stopId, tripId)
        if (calls.isEmpty()) return emptyList()
        val today = Instant.ofEpochSecond(near).atZone(SCHEDULE_ZONE).toLocalDate()
        return sequenceOf(today, today.minusDays(1), today.plusDays(1))
            .filter { date -> staticData.current.isServiceActive(serviceId, date) }
            .flatMap { date -> calls.asSequence().map { serviceDayStart(date) + it.arrivalSecondsSinceMidnight } }
            .toList()
    }

    /** The scheduled epoch of [tripId] at [stopId] nearest [near], used to line a live event up with its schedule slot. */
    private fun closestScheduledEpoch(tripId: String, stopId: String, serviceId: String, near: Long): Long? =
        scheduledEpochs(tripId, stopId, serviceId, near).minByOrNull { kotlin.math.abs(it - near) }

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
                    .map { date -> serviceDayStart(date) + call.arrivalSecondsSinceMidnight }
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
                val event = stopTimeUpdate.event(update.trip.tripId, now) ?: return@mapNotNull null
                // The feed keeps listing stops a bus has already passed; without this a bus that
                // left half an hour ago shows as "Due now".
                if (event.time < now - PAST_VISIBLE_SECONDS) return@mapNotNull null
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
                    possiblyPassed = event.time < now - SCHEDULED_PAST_GRACE_SECONDS,
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
            ?.takeUnless { it.isCancelled() }
            ?: return emptyList()

        return update.stopTimeUpdateList.mapNotNull { stopTimeUpdate ->
            val event = stopTimeUpdate.event(tripId, nowEpochSeconds()) ?: return@mapNotNull null
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
            .filter { it.hasTripUpdate() && !it.tripUpdate.isCancelled() }
            .map { it.tripUpdate.trip.tripId }
            .filter { tripId ->
                wantedRouteIds == null || staticData.current.trip(tripId)?.routeId in wantedRouteIds
            }
            .distinct()
            .toList()
    }

    /**
     * Where the bus currently is: the first stop it hasn't passed yet. The feed also keeps the
     * stops already passed, whose delays are stale, so those are skipped - unless every stop is
     * behind it, when the last one is the best there is.
     */
    private fun TripUpdate.firstReport(now: Long): Pair<String, TripUpdate.StopTimeEvent>? {
        val reports = stopTimeUpdateList.asSequence()
            .mapNotNull { stu -> stu.event(trip.tripId, now)?.let { stu.stopId to it } }
        return reports.firstOrNull { it.second.time >= now - SCHEDULED_PAST_GRACE_SECONDS } ?: reports.lastOrNull()
    }

    /**
     * Prefers an event with an absolute time (arrival, then departure). The spec also allows an
     * event with only a delay, so that one gets its time filled in from the trip's own schedule
     * at this stop rather than the lateness being thrown away.
     */
    private fun TripUpdate.StopTimeUpdate.event(tripId: String, near: Long): TripUpdate.StopTimeEvent? {
        // A skipped stop has no arrival at all, and NO_DATA explicitly carries no prediction.
        if (isSkipped() || scheduleRelationship == TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA) return null
        val candidates = listOfNotNull(arrival.takeIf { hasArrival() }, departure.takeIf { hasDeparture() })
        candidates.firstOrNull { it.hasTime() }?.let { return it }
        val delayOnly = candidates.firstOrNull { it.hasDelay() } ?: return null
        val serviceId = staticData.current.trip(tripId)?.serviceId ?: return null
        val scheduled = closestScheduledEpoch(tripId, stopId, serviceId, near) ?: return null
        return delayOnly.toBuilder().setTime(scheduled + delayOnly.delay).build()
    }
}
