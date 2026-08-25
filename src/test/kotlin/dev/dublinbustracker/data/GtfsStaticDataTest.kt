package dev.dublinbustracker.data

import dev.dublinbustracker.support.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
