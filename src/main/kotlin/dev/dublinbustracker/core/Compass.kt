package dev.dublinbustracker.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val COMPASS_POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** The direction from [from] to [to] in degrees clockwise from north, 0 until 360. */
fun bearingDegrees(from: LatLon, to: LatLon): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLon = Math.toRadians(to.lon - from.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** The nearest of the eight compass points for a bearing in degrees. */
fun compassPoint(bearing: Double): String =
    COMPASS_POINTS[((bearing % 360.0 + 360.0) % 360.0 / 45.0).roundToInt() % COMPASS_POINTS.size]
