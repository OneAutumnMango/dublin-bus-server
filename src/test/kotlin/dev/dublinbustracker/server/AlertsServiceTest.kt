package dev.dublinbustracker.server

import dev.dublinbustracker.data.FeedKind
import dev.dublinbustracker.support.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class AlertsServiceTest {
    private val store = FeedStore(historyDepth = 3)
    private val service = AlertsService(store)

    private fun recordAlerts() {
        store.record(
            FeedKind.COMBINED,
            Fixtures.feedOf(
                Fixtures.alertEntity(
                    entityId = "a1",
                    header = "Diversion on route 15",
                    description = "Roadworks in the city centre",
                    routeIds = listOf("r1"),
                    stopIds = listOf("s1"),
                    startEpochSeconds = 1_000,
                    endEpochSeconds = 2_000,
                ),
                Fixtures.alertEntity(
                    entityId = "a2",
                    header = "Stop closed",
                    stopIds = listOf("s2"),
                ),
            ),
        )
    }

    @Test
    fun `alerts are mapped with text, cause, effect and active period`() {
        recordAlerts()

        val alert = service.alerts().single { it.id == "a1" }

        assertEquals("Diversion on route 15", alert.header)
        assertEquals("Roadworks in the city centre", alert.description)
        assertEquals("CONSTRUCTION", alert.cause)
        assertEquals("DETOUR", alert.effect)
        assertEquals(listOf("r1"), alert.routeIds)
        assertEquals(listOf("s1"), alert.stopIds)
        assertEquals(1_000, alert.startEpochSeconds)
        assertEquals(2_000, alert.endEpochSeconds)
    }

    @Test
    fun `an alert with no active period reports null bounds`() {
        recordAlerts()

        val alert = service.alerts().single { it.id == "a2" }

        assertEquals(null, alert.startEpochSeconds)
        assertEquals(null, alert.endEpochSeconds)
    }

    @Test
    fun `alerts can be filtered by route`() {
        recordAlerts()

        assertEquals(listOf("a1"), service.alertsForRoute(setOf("r1")).map { it.id })
        assertEquals(emptyList(), service.alertsForRoute(setOf("r3")).map { it.id })
    }

    @Test
    fun `alerts can be filtered by stop`() {
        recordAlerts()

        assertEquals(listOf("a2"), service.alertsForStop("s2").map { it.id })
    }

    @Test
    fun `alerts appearing in more than one feed are de-duplicated`() {
        recordAlerts()
        store.record(FeedKind.TRIP_UPDATES, Fixtures.feedOf(Fixtures.alertEntity("a1", "Diversion on route 15")))

        assertEquals(setOf("a1", "a2"), service.alerts().map { it.id }.toSet())
    }

    @Test
    fun `with no feed recorded there are no alerts`() {
        assertEquals(emptyList(), service.alerts())
    }
}
