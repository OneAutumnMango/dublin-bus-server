package dev.dublinbustracker.core

data class Stop(
    val id: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

data class Arrival(
    val routeShortName: String,
    val destination: String,
    val dueMinutes: Int,
    val isRealtime: Boolean,
)
