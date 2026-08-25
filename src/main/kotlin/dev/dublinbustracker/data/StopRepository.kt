package dev.dublinbustracker.data

import dev.dublinbustracker.core.Stop
import dev.dublinbustracker.core.StopSearch

class StopRepository(private val gtfsStaticData: GtfsStaticData) {
    fun all(): List<Stop> = gtfsStaticData.allStops()

    fun byId(stopId: String): Stop? = gtfsStaticData.allStops().find { it.id == stopId }

    fun search(query: String): List<Stop> = StopSearch.search(gtfsStaticData.allStops(), query)

    fun nearest(lat: Double, lon: Double, limit: Int = 10): List<Stop> =
        StopSearch.nearestStops(lat, lon, gtfsStaticData.allStops(), limit)
}
