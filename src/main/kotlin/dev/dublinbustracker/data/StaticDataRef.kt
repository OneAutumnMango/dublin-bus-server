package dev.dublinbustracker.data

/**
 * A hot-swappable pointer to the current static GTFS bundle. The NTA revises the static
 * schedule periodically, which drifts out of sync with the live GTFS-Realtime feed until the
 * bundle is refreshed - see [dev.dublinbustracker.server.StaticDataRefresher]. Every reader
 * goes through [current] rather than holding a snapshot, so a refresh takes effect immediately
 * for every in-flight and future request without a restart.
 */
class StaticDataRef(@Volatile var current: GtfsStaticData)
