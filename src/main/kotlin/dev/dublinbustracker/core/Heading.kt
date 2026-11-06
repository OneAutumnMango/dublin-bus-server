package dev.dublinbustracker.core

import kotlin.math.cos

/** A bus further than this from its route's path isn't on it, so the path can't say which way it is facing. */
private const val MAX_OFF_PATH_METERS = 150.0

/**
 * Which way a vehicle at [here] faces if it is driving along [path]: the bearing along the segment of it closest to
 * the vehicle. Null when there's no path or the vehicle isn't near it.
 */
fun headingAlongPath(path: List<LatLon>, here: LatLon): Float? {
    if (path.size < 2) return null
    // Work in flat metres around [here]: accurate enough over a few hundred metres, and cheap enough to try every segment.
    val metersPerLat = 110_540.0
    val metersPerLon = 111_320.0 * cos(Math.toRadians(here.lat))
    fun x(point: LatLon) = (point.lon - here.lon) * metersPerLon
    fun y(point: LatLon) = (point.lat - here.lat) * metersPerLat

    var nearest = -1
    var nearestSquared = Double.MAX_VALUE
    for (index in 0 until path.lastIndex) {
        val ax = x(path[index])
        val ay = y(path[index])
        val dx = x(path[index + 1]) - ax
        val dy = y(path[index + 1]) - ay
        val lengthSquared = dx * dx + dy * dy
        // How far along this segment the closest point to the vehicle (the origin) is, kept within the segment.
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        val squared = px * px + py * py
        if (squared < nearestSquared) {
            nearestSquared = squared
            nearest = index
        }
    }
    if (nearest < 0 || nearestSquared > MAX_OFF_PATH_METERS * MAX_OFF_PATH_METERS) return null
    return bearingDegrees(path[nearest], path[nearest + 1]).toFloat()
}
