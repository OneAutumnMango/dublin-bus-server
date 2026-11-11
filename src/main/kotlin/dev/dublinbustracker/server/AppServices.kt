package dev.dublinbustracker.server

import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.data.StaticDataRef
import java.io.File
import java.util.concurrent.TimeUnit

/** Everything the routes need, assembled once at startup. */
class AppServices(
    val config: ServerConfig,
    val staticData: StaticDataRef,
    val store: FeedStore,
    val client: RealtimeGtfsClient,
    val poller: FeedPoller,
    val arrivals: ArrivalsService,
    val vehicles: VehicleService,
    val alerts: AlertsService,
    val staticDataRefresher: StaticDataRefresher,
    val routeArrivalsCache: ResponseCache<Map<String, List<ArrivalResponse>>> = ResponseCache(),
) {
    companion object {
        fun build(
            config: ServerConfig,
            staticData: StaticDataRef,
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
                staticDataRefresher = StaticDataRefresher(
                    target = staticData,
                    // No path configured means the server booted from the bundled resource; a refresh
                    // still needs somewhere on disk to write the fresh bundle to.
                    outputPath = config.gtfsStaticPath
                        ?: File(System.getProperty("java.io.tmpdir"), "dublin-bus-server-gtfs_static.json").path,
                    notifier = notifier,
                    intervalMs = TimeUnit.HOURS.toMillis(config.gtfsRefreshIntervalHours),
                ),
            )
        }
    }
}
