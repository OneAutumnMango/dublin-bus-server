package dev.dublinbustracker.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StopHeadingsTest {
    /**
     * Route "4" runs east along the top road (a, b, c) and back west along the bottom road
     * (c2, b2, a2). Stops b and b2 face each other across the street. Route "9" is a terminus:
     * it leaves "t" going north and, on the way back, arrives at "t" heading south. Route "5"
     * runs east along the road then turns north to a terminus far to the north; route "6" is a
     * loop that ends where it began.
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
        |  {"id":"dup","code":"10","name":"Dup","lat":53.3200,"lon":-6.2500},
        |  {"id":"w1","code":"11","name":"W1","lat":53.2000,"lon":-6.2000},
        |  {"id":"w2","code":"12","name":"W2","lat":53.2000,"lon":-6.1900},
        |  {"id":"w3","code":"13","name":"W3","lat":53.2000,"lon":-6.1800},
        |  {"id":"w4","code":"14","name":"W4","lat":53.2600,"lon":-6.1800},
        |  {"id":"l1","code":"15","name":"L1","lat":53.1000,"lon":-6.1000},
        |  {"id":"l2","code":"16","name":"L2","lat":53.1000,"lon":-6.0900},
        |  {"id":"l3","code":"17","name":"L3","lat":53.1000,"lon":-6.1000}
        |],
        |"routes":[
        |  {"id":"r4","shortName":"4","longName":"","agencyId":"a"},
        |  {"id":"r9","shortName":"9","longName":"","agencyId":"a"},
        |  {"id":"r5","shortName":"5","longName":"","agencyId":"a"},
        |  {"id":"r6","shortName":"6","longName":"","agencyId":"a"}
        |],
        |"trips":{},
        |"routePatterns":{
        |  "r4":{"0":["a","b","c"],"1":["c2","b2","a2"]},
        |  "r9":{"0":["t","m","far"],"1":["far","m","t"]},
        |  "r5":{"0":["w1","w2","w3","w4"]},
        |  "r6":{"0":["l1","l2","l3"]}
        |},
        |"stopRoutes":{"a":["4"],"b":["4"],"c":["4"],"a2":["4"],"b2":["4"],"c2":["4"],"t":["9"],"m":["9"],"far":["9"],"dup":["4"],"w1":["5"],"w2":["5"],"w3":["5"],"w4":["5"],"l1":["6"],"l2":["6"],"l3":["6"]}
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

    @Test
    fun `the heading is towards the end of the route, not the way the road happens to point here`() {
        // At w2 the road runs east (w1 -> w3), but the route is heading for w4, almost due north.
        assertEquals(mapOf("5" to "N"), headings("w2"))
    }

    @Test
    fun `a loop that ends where it began falls back to the road's own direction`() {
        // The terminus l3 is where l1 is, so it says nothing about direction from l1; l2 is east of it.
        assertEquals(mapOf("6" to "E"), headings("l1"))
    }
}
