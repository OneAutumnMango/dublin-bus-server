package dev.dublinbustracker.data

/** The GTFS-Realtime feeds the NTA publishes. Each poll of a feed costs one API call. */
enum class FeedKind(val path: String) {
    /** Predicted arrival/departure times for every running trip. */
    TRIP_UPDATES("TripUpdates"),

    /** Live vehicle positions. */
    VEHICLES("Vehicles"),

    /** Combined feed; also the only one that carries service alerts. */
    COMBINED("gtfsr"),
    ;

    companion object {
        fun parse(raw: String): FeedKind? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) || it.path.equals(raw.trim(), ignoreCase = true) }
    }
}
