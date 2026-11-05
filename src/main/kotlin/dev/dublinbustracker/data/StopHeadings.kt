package dev.dublinbustracker.data

import dev.dublinbustracker.core.LatLon
import dev.dublinbustracker.core.Stop
import dev.dublinbustracker.core.bearingDegrees
import dev.dublinbustracker.core.compassPoint
import dev.dublinbustracker.core.haversineMeters

/** Which way a route's buses are heading as they pass a stop, e.g. route "4" heading "NW". */
data class RouteHeading(val route: String, val compass: String)

/** Closer than this the neighbouring stops say nothing reliable about direction. */
private const val MIN_SEGMENT_METERS = 15.0

/**
 * The compass direction each route travels at [stop]. It is stop-based: a stop on the other side
 * of the road sits in the opposite direction's stop sequence, so it gets the opposite heading.
 *
 * The heading is read from the stops either side of this one in each route's sequence. A bus
 * departing is preferred to one only arriving (the end of a route is also the start of the way
 * back, and "where it goes from here" is what's useful). A route that visits the stop heading in
 * two different directions (a loop, say) lists both, as "NW/SE".
 */
fun GtfsStaticData.headingsAtStop(stop: Stop): List<RouteHeading> =
    routesServingStop(stop.id).mapNotNull { shortName ->
        val departing = mutableSetOf<String>()
        val arriving = mutableSetOf<String>()
        resolveRoutes(shortName).forEach { route ->
            patternsForRoute(route.id).values.forEach { pattern ->
                val index = pattern.indexOf(stop.id)
                if (index < 0) return@forEach
                val before = pattern.getOrNull(index - 1)?.let(::stopById)?.let { LatLon(it.lat, it.lon) }
                val here = LatLon(stop.lat, stop.lon)
                val after = pattern.getOrNull(index + 1)?.let(::stopById)?.let { LatLon(it.lat, it.lon) }
                when {
                    after != null -> headingOf(before, here, after)?.let { departing += it }
                    before != null -> headingOf(before, here, null)?.let { arriving += it }
                }
            }
        }
        val compass = departing.ifEmpty { arriving }
        if (compass.isEmpty()) null else RouteHeading(shortName, compass.sorted().joinToString("/"))
    }

private fun distanceMeters(a: LatLon, b: LatLon): Double = haversineMeters(a.lat, a.lon, b.lat, b.lon)

/** Before-to-after is the truest picture of the road here; failing that, one side will do. */
private fun headingOf(before: LatLon?, here: LatLon, after: LatLon?): String? {
    val (from, to) = when {
        before != null && after != null && distanceMeters(before, after) >= MIN_SEGMENT_METERS -> before to after
        after != null -> here to after
        before != null -> before to here
        else -> return null
    }
    if (distanceMeters(from, to) < MIN_SEGMENT_METERS) return null
    return compassPoint(bearingDegrees(from, to))
}
