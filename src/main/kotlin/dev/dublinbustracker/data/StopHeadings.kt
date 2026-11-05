package dev.dublinbustracker.data

import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.core.Stop
import dev.dublinbustracker.core.bearingDegrees
import dev.dublinbustracker.core.compassPoint
import dev.dublinbustracker.core.haversineMeters

/** Which way a route's buses are heading as they pass a stop, e.g. route "4" heading "NW". */
data class RouteHeading(val route: String, val compass: String)

/** A terminus closer than this tells us nothing about direction, so the road itself is used instead. */
private const val MIN_TERMINUS_METERS = 50.0

/** Neighbouring stops closer than this tell us nothing either. */
private const val MIN_SEGMENT_METERS = 15.0

/**
 * The compass direction each route's buses are heading as they pass [stop]: from the stop towards
 * the terminus that direction of the route runs to (the way a passenger would say "the 4 goes
 * north-west"). It is stop-based: a stop on the other side of the road sits in the opposite
 * direction's stop sequence, which ends at the other terminus, so it gets the opposite heading.
 *
 * A bus departing is preferred to one only arriving: the end of one direction is the start of the
 * way back, and "where it goes from here" is what's useful - so a terminus describes the way out,
 * and a stop at the very end of a sequence falls back to the way the bus came in. A route that
 * passes in two different directions (a loop, say) lists both, as "NW/SE".
 */
fun GtfsStaticData.headingsAtStop(stop: Stop): List<RouteHeading> =
    routesServingStop(stop.id).mapNotNull { shortName ->
        val departing = mutableSetOf<String>()
        val arriving = mutableSetOf<String>()
        resolveRoutes(shortName).forEach { route ->
            patternsForRoute(route.id).values.forEach { pattern ->
                val index = pattern.indexOf(stop.id)
                if (index < 0) return@forEach
                val here = LatLon(stop.lat, stop.lon)
                fun positionOf(stopId: String?) = stopId?.let(::stopById)?.let { LatLon(it.lat, it.lon) }
                if (index < pattern.lastIndex) {
                    headingTowards(here, positionOf(pattern.last()), positionOf(pattern.getOrNull(index - 1)), positionOf(pattern[index + 1]))
                        ?.let { departing += it }
                } else {
                    // The end of the line: all that's left is the way the bus came in, from where it started.
                    positionOf(pattern.first())?.takeIf { index > 0 && distanceMeters(it, here) >= MIN_SEGMENT_METERS }
                        ?.let { arriving += compassPoint(bearingDegrees(it, here)) }
                }
            }
        }
        val compass = departing.ifEmpty { arriving }
        if (compass.isEmpty()) null else RouteHeading(shortName, compass.sorted().joinToString("/"))
    }

private fun distanceMeters(a: LatLon, b: LatLon): Double = haversineMeters(a.lat, a.lon, b.lat, b.lon)

/**
 * Towards the [terminus]. If that is practically where we're standing (a loop that ends where it
 * began, say), the stops either side give the road's direction here instead.
 */
private fun headingTowards(here: LatLon, terminus: LatLon?, before: LatLon?, after: LatLon?): String? {
    if (terminus != null && distanceMeters(here, terminus) >= MIN_TERMINUS_METERS) {
        return compassPoint(bearingDegrees(here, terminus))
    }
    val (from, to) = when {
        before != null && after != null && distanceMeters(before, after) >= MIN_SEGMENT_METERS -> before to after
        after != null -> here to after
        else -> return null
    }
    return if (distanceMeters(from, to) < MIN_SEGMENT_METERS) null else compassPoint(bearingDegrees(from, to))
}
