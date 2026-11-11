package dev.dublinbustracker.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small TTL cache with single-flight computation. An entry is valid while its feed stamp is unchanged, its
 * static-data instance is the same (`===`), and it is no older than [ttlMs]. Entries are shared between callers, so
 * `V` must be treated as immutable. A throwing `compute` propagates and is never cached.
 */
class ResponseCache<V : Any>(
    private val ttlMs: Long = 15_000,
    private val maxEntries: Int = 256,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(val value: V, val stamp: List<Long?>, val staticData: Any, val computedAtMs: Long)

    private class KeyLock {
        val mutex = Mutex()
        var users = 0
    }

    private val guard = Mutex()
    // Insertion-ordered: the first entry is the oldest.
    private val entries = LinkedHashMap<String, Entry<V>>()
    private val locks = HashMap<String, KeyLock>()

    internal suspend fun size(): Int = guard.withLock { entries.size }

    suspend fun getOrCompute(key: String, feedStamp: List<Long?>, staticData: Any, compute: () -> V): V {
        val keyLock =
            guard.withLock {
                lookup(key, feedStamp, staticData)?.let {
                    return it
                }
                locks.getOrPut(key) { KeyLock() }.also { it.users++ }
            }
        try {
            return keyLock.mutex.withLock {
                guard.withLock {
                    lookup(key, feedStamp, staticData)?.let {
                        return it
                    }
                }
                val startedAt = nowMs()
                val value = compute()
                guard.withLock { store(key, Entry(value, feedStamp, staticData, startedAt)) }
                value
            }
        } finally {
            guard.withLock {
                if (--keyLock.users == 0) locks.remove(key)
            }
        }
    }

    // Caller holds [guard].
    private fun lookup(key: String, feedStamp: List<Long?>, staticData: Any): V? {
        entries.values.removeAll { it.stamp != feedStamp }
        val entry = entries[key] ?: return null
        if (entry.staticData !== staticData || nowMs() - entry.computedAtMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    // Caller holds [guard].
    private fun store(key: String, entry: Entry<V>) {
        entries.remove(key)
        entries.values.removeAll { it.stamp != entry.stamp }
        entries[key] = entry
        while (entries.size > maxEntries) {
            val oldest = entries.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}
