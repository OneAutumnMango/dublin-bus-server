package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.support.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FeedStoreTest {
    private fun feed(entityId: String) = Fixtures.feedOf(Fixtures.tripUpdateEntity(entityId, tripId = "t1"))

    @Test
    fun `latest returns the most recently recorded snapshot`() {
        val store = FeedStore(historyDepth = 5)
        store.record(FeedKind.TRIP_UPDATES, feed("first"), fetchedAtEpochMs = 1_000)
        store.record(FeedKind.TRIP_UPDATES, feed("second"), fetchedAtEpochMs = 2_000)

        val latest = store.latest(FeedKind.TRIP_UPDATES)
        assertEquals(2_000, latest?.fetchedAtEpochMs)
        assertEquals("second", latest?.feed?.getEntity(0)?.id)
    }

    @Test
    fun `history is returned oldest first`() {
        val store = FeedStore(historyDepth = 5)
        repeat(3) { store.record(FeedKind.TRIP_UPDATES, feed("e$it"), fetchedAtEpochMs = it.toLong()) }

        val ids = store.history(FeedKind.TRIP_UPDATES).map { it.feed.getEntity(0).id }
        assertEquals(listOf("e0", "e1", "e2"), ids)
    }

    @Test
    fun `the backlog is capped at the configured depth, evicting oldest first`() {
        val store = FeedStore(historyDepth = 3)
        repeat(6) { store.record(FeedKind.TRIP_UPDATES, feed("e$it"), fetchedAtEpochMs = it.toLong()) }

        assertEquals(3, store.depth(FeedKind.TRIP_UPDATES))
        assertEquals(
            listOf("e3", "e4", "e5"),
            store.history(FeedKind.TRIP_UPDATES).map { it.feed.getEntity(0).id },
        )
    }

    @Test
    fun `feeds are tracked independently`() {
        val store = FeedStore(historyDepth = 2)
        store.record(FeedKind.TRIP_UPDATES, feed("t"))
        store.record(FeedKind.VEHICLES, feed("v"))

        assertEquals(setOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES), store.trackedKinds())
        assertEquals(1, store.depth(FeedKind.VEHICLES))
        assertEquals(0, store.depth(FeedKind.COMBINED))
    }

    @Test
    fun `an unseen feed has no latest snapshot or history`() {
        val store = FeedStore(historyDepth = 2)
        assertNull(store.latest(FeedKind.VEHICLES))
        assertEquals(emptyList(), store.history(FeedKind.VEHICLES))
    }

    @Test
    fun `a depth of one keeps only the newest snapshot`() {
        val store = FeedStore(historyDepth = 1)
        store.record(FeedKind.TRIP_UPDATES, feed("old"))
        store.record(FeedKind.TRIP_UPDATES, feed("new"))

        assertEquals(1, store.depth(FeedKind.TRIP_UPDATES))
        assertEquals("new", store.latest(FeedKind.TRIP_UPDATES)?.feed?.getEntity(0)?.id)
    }

    @Test
    fun `a non-positive depth is rejected`() {
        assertFailsWith<IllegalArgumentException> { FeedStore(historyDepth = 0) }
    }
}
