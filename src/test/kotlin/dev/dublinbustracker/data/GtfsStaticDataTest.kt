package dev.dublinbustracker.data

import dev.dublinbustracker.support.Fixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GtfsStaticDataTest {
    private val data = GtfsStaticData.parse(Fixtures.staticBundleJson())

    @Test
    fun `parses stops, routes and trips`() {
        assertEquals(3, data.allStops().size)
        assertEquals(3, data.allRoutes().size)
        assertEquals("Trinity College", data.stopById("s1")?.name)
    }

    @Test
    fun `looks up a stop by its roadside code as well as its id`() {
        assertEquals("s2", data.stopByIdOrCode("1002")?.id)
        assertEquals("s2", data.stopByIdOrCode("s2")?.id)
        assertNull(data.stopByIdOrCode("nope"))
    }

    @Test
    fun `a bus number resolves to every route variant carrying it`() {
        val routes = data.resolveRoutes("15")
        assertEquals(setOf("r1", "r2"), routes.map { it.id }.toSet())
    }

    @Test
    fun `a route id resolves to exactly that route`() {
        assertEquals(listOf("r3"), data.resolveRoutes("r3").map { it.id })
    }

    @Test
    fun `unknown route identifier resolves to nothing`() {
        assertEquals(emptyList(), data.resolveRoutes("999X"))
    }

    @Test
    fun `resolves trip metadata and route short names`() {
        assertEquals(TripInfo("15", "Merrion Square"), data.tripInfo("t1"))
        assertEquals("27", data.routeShortName("r3"))
        assertNull(data.tripInfo("missing"))
    }

    @Test
    fun `unknown route id falls back to echoing the id as its short name`() {
        assertEquals("rX", data.routeShortName("rX"))
    }

    @Test
    fun `exposes which routes serve a stop`() {
        assertEquals(listOf("15", "27"), data.routesServingStop("s1"))
        assertEquals(emptyList(), data.routesServingStop("unknown"))
    }

    @Test
    fun `exposes ordered stop patterns per direction`() {
        val patterns = data.patternsForRoute("r1")
        assertEquals(listOf("s1", "s2"), patterns["0"])
        assertEquals(listOf("s2", "s1"), patterns["1"])
    }

    @Test
    fun `decodes shape geometry`() {
        val points = data.shapePoints("sh1")
        assertEquals(3, points.size)
        assertTrue(kotlin.math.abs(points.first().lat - 53.3441) < 1e-5)
    }

    @Test
    fun `unknown shape yields no points`() {
        assertEquals(emptyList(), data.shapePoints("missing"))
        assertNull(data.encodedShape("missing"))
    }

    @Test
    fun `picks a shape for a route, honouring direction`() {
        assertEquals("sh1", data.shapeIdForRoute("r1"))
        assertEquals("sh1", data.shapeIdForRoute("r1", directionId = "0"))
        // t3 is the only direction-1 trip on r1 and it has no shape.
        assertNull(data.shapeIdForRoute("r1", directionId = "1"))
    }

    @Test
    fun `reports bundle statistics`() {
        val stats = data.stats()
        assertEquals(3, stats["stops"])
        assertEquals(3, stats["routes"])
        assertEquals(3, stats["trips"])
        assertEquals(2, stats["shapes"])
    }

    @Test
    fun `tolerates a bundle with only stops`() {
        val minimal = GtfsStaticData.parse("""{"version":2,"stops":[{"id":"a","code":"a","name":"A","lat":1.0,"lon":2.0}]}""")
        assertEquals(1, minimal.allStops().size)
        assertEquals(emptyList(), minimal.allRoutes())
        assertNotNull(minimal.stopById("a"))
    }

    @Test
    fun `scheduled calls for a stop come from stopSchedule`() {
        val bundle = GtfsStaticData.parse(
            """{"version":3,"stops":[],"stopSchedule":{"s1":[{"t":"t1","s":28800},{"t":"t2","s":30000}]}}""",
        )
        assertEquals(
            listOf("t1" to 28800, "t2" to 30000),
            bundle.scheduledCallsForStop("s1").map { it.tripId to it.arrivalSecondsSinceMidnight },
        )
        assertEquals(emptyList(), bundle.scheduledCallsForStop("unknown"))
    }

    @Test
    fun `a service is active on its weekly pattern within its date range`() {
        val bundle = GtfsStaticData.parse(
            """{"version":3,"stops":[],
               |"services":{"weekday":{"weekdays":"1111100","start":"20240101","end":"20241231"}}}
            """.trimMargin(),
        )
        assertTrue(bundle.isServiceActive("weekday", LocalDate.of(2024, 1, 10))) // Wednesday
        assertFalse(bundle.isServiceActive("weekday", LocalDate.of(2024, 1, 13))) // Saturday
        assertFalse(bundle.isServiceActive("weekday", LocalDate.of(2023, 12, 31))) // before start
        assertFalse(bundle.isServiceActive("weekday", LocalDate.of(2025, 1, 1))) // after end
    }

    @Test
    fun `an added date in calendar_dates runs even outside the weekly pattern`() {
        val bundle = GtfsStaticData.parse(
            """{"version":3,"stops":[],
               |"services":{"svc":{"weekdays":"0000000","start":"20240101","end":"20241231","added":["20240115"]}}}
            """.trimMargin(),
        )
        assertTrue(bundle.isServiceActive("svc", LocalDate.of(2024, 1, 15)))
        assertFalse(bundle.isServiceActive("svc", LocalDate.of(2024, 1, 16)))
    }

    @Test
    fun `a removed date is excluded even if the weekly pattern would include it`() {
        val bundle = GtfsStaticData.parse(
            """{"version":3,"stops":[],
               |"services":{"svc":{"weekdays":"1111111","start":"20240101","end":"20241231","removed":["20240110"]}}}
            """.trimMargin(),
        )
        assertFalse(bundle.isServiceActive("svc", LocalDate.of(2024, 1, 10)))
        assertTrue(bundle.isServiceActive("svc", LocalDate.of(2024, 1, 11)))
    }

    @Test
    fun `an unknown service is never active`() {
        assertFalse(data.isServiceActive("ghost", LocalDate.of(2024, 1, 1)))
    }
}
