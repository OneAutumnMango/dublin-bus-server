package dev.dublinbustracker.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineTest {
    // The reference example from Google's encoded polyline specification.
    private val referencePoints = listOf(
        LatLon(38.5, -120.2),
        LatLon(40.7, -120.95),
        LatLon(43.252, -126.453),
    )
    private val referenceEncoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun `encodes the reference polyline`() {
        assertEquals(referenceEncoded, Polyline.encode(referencePoints))
    }

    @Test
    fun `decodes the reference polyline`() {
        val decoded = Polyline.decode(referenceEncoded)
        assertEquals(referencePoints.size, decoded.size)
        referencePoints.zip(decoded).forEach { (expected, actual) ->
            assertTrue(abs(expected.lat - actual.lat) < 1e-5, "lat ${actual.lat} != ${expected.lat}")
            assertTrue(abs(expected.lon - actual.lon) < 1e-5, "lon ${actual.lon} != ${expected.lon}")
        }
    }

    @Test
    fun `round trips Dublin coordinates within encoding precision`() {
        val points = listOf(
            LatLon(53.349805, -6.260310),
            LatLon(53.344100, -6.257800),
            LatLon(53.346800, -6.295100),
        )
        val decoded = Polyline.decode(Polyline.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertTrue(abs(expected.lat - actual.lat) < 1e-5)
            assertTrue(abs(expected.lon - actual.lon) < 1e-5)
        }
    }

    @Test
    fun `empty input round trips to empty output`() {
        assertEquals("", Polyline.encode(emptyList()))
        assertEquals(emptyList(), Polyline.decode(""))
    }

    @Test
    fun `handles a single point`() {
        val decoded = Polyline.decode(Polyline.encode(listOf(LatLon(53.35, -6.26))))
        assertEquals(1, decoded.size)
        assertTrue(abs(decoded[0].lat - 53.35) < 1e-5)
    }
}
