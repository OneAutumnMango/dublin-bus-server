package dev.dublinbustracker.server

import dev.dublinbustracker.core.ServiceAlert
import dev.dublinbustracker.data.FeedKind

/** Service disruptions. Only the combined feed carries alert entities. */
class AlertsService(private val store: FeedStore) {
    fun alerts(): List<ServiceAlert> =
        FeedKind.entries
            .mapNotNull { store.latest(it) }
            .flatMap { it.feed.entityList }
            .filter { it.hasAlert() }
            .distinctBy { it.id }
            .map { entity ->
                val alert = entity.alert
                val period = alert.activePeriodList.firstOrNull()
                ServiceAlert(
                    id = entity.id,
                    cause = alert.cause.name,
                    effect = alert.effect.name,
                    header = alert.headerText.firstText(),
                    description = alert.descriptionText.firstText(),
                    url = alert.url.firstText(),
                    routeIds = alert.informedEntityList.mapNotNull { it.routeId.orNullIfBlank() }.distinct(),
                    stopIds = alert.informedEntityList.mapNotNull { it.stopId.orNullIfBlank() }.distinct(),
                    startEpochSeconds = period?.takeIf { it.hasStart() }?.start,
                    endEpochSeconds = period?.takeIf { it.hasEnd() }?.end,
                )
            }

    fun alertsForRoute(routeIds: Set<String>): List<ServiceAlert> =
        alerts().filter { alert -> alert.routeIds.any { it in routeIds } }

    fun alertsForStop(stopId: String): List<ServiceAlert> =
        alerts().filter { stopId in it.stopIds }
}
