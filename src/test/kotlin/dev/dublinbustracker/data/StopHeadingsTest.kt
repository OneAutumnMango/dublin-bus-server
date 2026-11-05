package dev.dublinbustracker.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StopHeadingsTest {
    /**
     * Route "4" runs east along the top road (a, b, c) and back west along the bottom road
     * (c2, b2, a2). Stops b and b2 face each other across the street. Route "9" is a terminus:
     * it leaves "t" going north and, on the way back, arrives at "t" heading south.
     */
    private val data = GtfsStaticData.parse(
        """{"version":3,
        |"stops":[
        |  {"id":"a","code":"1","name":"A","lat":53.3000,"lon":-6.3000},
        |  {"id":"b","code":"2","name":"B","lat":53.3000,"lon":-6.2900},
        |  {"id":"c","code":"3","name":"C","lat":53.3000,"lon":-6.2800},
        |  {"id":"a2","code":"4","name":"A2","lat":53.2995,"lon":-6.3000},
        |  {"id":"b2","code":"5","name":"B2","lat":53.2995,"lon":-6.2900},
        |  {"id":"c2","code":"6","name":"C2","lat":53.2995,"lon":-6.2800},
        |  {"id":"t","code":"7","name":"Terminus","lat":53.3200,"lon":-6.2500},
        |  {"id":"m","code":"8","name":"Middle","lat":53.3300,"lon":-6.2500},
        |  {"id":"far","code":"9","name":"Far","lat":53.3400,"lon":-6.2500},
        |  {"id":"dup","code":"10","name":"Dup","lat":53.3200,"lon":-6.2500}
        |],
        |"routes":[
        |  {"id":"r4","shortName":"4","longName":"","agencyId":"a"},
        |  {"id":"r9","shortName":"9","longName":"","agencyId":"a"}
        |],
        |"trips":{},
        |"routePatterns":{
        |  "r4":{"0":["a","b","c"],"1":["c2","b2","a2"]},
        |  "r9":{"0":["t","m","far"],"1":["far","m","t"]}
        |},
        |"stopRoutes":{"a":["4"],"b":["4"],"c":["4"],"a2":["4"],"b2":["4"],"c2":["4"],"t":["9"],"m":["9"],"far":["9"],"dup":["4"]}
        |}
        """.trimMargin(),
    )

    private fun headings(stopId: String) = data.headingsAtStop(data.stopById(stopId)!!).associate { it.route to it.compass }

    @Test
    fun `a stop heading east is east, and the stop across the road is west`() {
        assertEquals(mapOf("4" to "E"), headings("b"))
        assertEquals(mapOf("4" to "W"), headings("b2"))
    }

    @Test
    fun `the first stop uses the way to the next one`() {
        assertEquals(mapOf("4" to "E"), headings("a"))
    }

    @Test
    fun `the last stop on a one-way pattern uses the way the bus arrived`() {
        assertEquals(mapOf("4" to "E"), headings("c"))
    }

    @Test
    fun `a terminus is described by the way buses leave it, not arrive`() {
        // "t" is the end of the way back (arriving heading south) and the start of the way out (leaving north).
        assertEquals(mapOf("9" to "N"), headings("t"))
    }

    @Test
    fun `a middle stop used in both directions lists both`() {
        // "m" is on both patterns, heading north one way and south the other.
        assertEquals(mapOf("9" to "N/S"), headings("m"))
    }

    @Test
    fun `a stop whose route has no stop sequence has no heading`() {
        assertEquals(emptyMap(), headings("dup"))
    }

    @Test
    fun `routes that serve the stop but have a different bus number are kept apart`() {
        assertNull(headings("b")["9"])
    }
}
