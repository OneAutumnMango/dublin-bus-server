package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
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
            val vehicles = VehicleService(store, staticData)
            val refresher = StaticDataRefresher(
                target = staticData,
                // No path configured means the server booted from the bundled resource; a refresh
                // still needs somewhere on disk to write the fresh bundle to.
                outputPath = config.gtfsStaticPath
                    ?: File(System.getProperty("java.io.tmpdir"), "dublin-bus-server-gtfs_static.json").path,
                notifier = notifier,
                intervalMs = TimeUnit.HOURS.toMillis(config.gtfsRefreshIntervalHours),
                minUnmatchedFraction = config.gtfsMismatchPercent / 100.0,
            )
            return AppServices(
                config = config,
                staticData = staticData,
                store = store,
                client = client,
                poller = FeedPoller(client, store, config.feeds, config.pollIntervalMs, notifier) { kind, feed ->
                    if (kind == FeedKind.TRIP_UPDATES) refresher.observe(feed)
                },
                arrivals = ArrivalsService(store, staticData, vehicles = vehicles),
                vehicles = vehicles,
                alerts = AlertsService(store),
                staticDataRefresher = refresher,
            )
        }
    }
}
