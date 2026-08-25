package dev.dublinbustracker.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object StopSearch {
    /** Matches [query] against a stop's name (contains) or code (prefix), case-insensitive. */
    fun search(stops: List<Stop>, query: String): List<Stop> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return stops.filter {
            it.name.contains(trimmed, ignoreCase = true) ||
                it.code.startsWith(trimmed, ignoreCase = true)
        }
    }

    fun nearestStops(lat: Double, lon: Double, stops: List<Stop>, limit: Int): List<Stop> =
        stops.sortedBy { haversineMeters(lat, lon, it.lat, it.lon) }.take(limit)
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}
