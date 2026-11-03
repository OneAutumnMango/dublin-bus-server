package dev.dublinbustracker.server

import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.RealtimeGtfsClient

/** Everything the routes need, assembled once at startup. */
class AppServices(
    val config: ServerConfig,
    val staticData: GtfsStaticData,
    val store: FeedStore,
    val client: RealtimeGtfsClient,
    val poller: FeedPoller,
    val arrivals: ArrivalsService,
    val vehicles: VehicleService,
    val alerts: AlertsService,
) {
    companion object {
        fun build(
            config: ServerConfig,
            staticData: GtfsStaticData,
            client: RealtimeGtfsClient,
            notifier: NtfyNotifier = NtfyNotifier(null, NtfyLevel.OFF),
        ): AppServices {
            val store = FeedStore(config.historyDepth)
            return AppServices(
                config = config,
                staticData = staticData,
                store = store,
                client = client,
                poller = FeedPoller(client, store, config.feeds, config.pollIntervalMs, notifier),
                arrivals = ArrivalsService(store, staticData),
                vehicles = VehicleService(store, staticData),
                alerts = AlertsService(store),
            )
        }
    }
}
