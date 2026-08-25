package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import dev.dublinbustracker.data.FeedKind

data class FeedSnapshot(
    val kind: FeedKind,
    val fetchedAtEpochMs: Long,
    val feed: FeedMessage,
)

/**
 * Keeps the most recent snapshot of each feed plus a bounded backlog of earlier ones.
 * The backlog is what makes historical queries (such as a vehicle's recent track) possible
 * without any database; [historyDepth] trades memory for how far back those can look.
 */
class FeedStore(val historyDepth: Int) {
    init {
        require(historyDepth >= 1) { "historyDepth must be at least 1" }
    }

    private val lock = Any()
    private val snapshots = LinkedHashMap<FeedKind, ArrayDeque<FeedSnapshot>>()

    fun record(kind: FeedKind, feed: FeedMessage, fetchedAtEpochMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val deque = snapshots.getOrPut(kind) { ArrayDeque() }
            deque.addLast(FeedSnapshot(kind, fetchedAtEpochMs, feed))
            while (deque.size > historyDepth) {
                deque.removeFirst()
            }
        }
    }

    fun latest(kind: FeedKind): FeedSnapshot? = synchronized(lock) { snapshots[kind]?.lastOrNull() }

    /** Retained snapshots for a feed, oldest first. */
    fun history(kind: FeedKind): List<FeedSnapshot> = synchronized(lock) { snapshots[kind]?.toList().orEmpty() }

    fun depth(kind: FeedKind): Int = synchronized(lock) { snapshots[kind]?.size ?: 0 }

    fun trackedKinds(): Set<FeedKind> = synchronized(lock) { snapshots.keys.toSet() }
}
