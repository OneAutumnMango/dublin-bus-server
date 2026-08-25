package dev.dublinbustracker.server

import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.NtaArrivalsRepository
import dev.dublinbustracker.data.RealtimeGtfsClient
import dev.dublinbustracker.data.StopRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    private val staticData = GtfsStaticData.parse("""{"stops":[],"routes":{},"trips":{}}""")
    private val config = ServerConfig(port = 0, serverApiKey = "secret", ntaApiKeys = listOf("nta-key"))
    private val feedCache = FeedCache(NtaArrivalsRepository(RealtimeGtfsClient(config.ntaApiKeys), staticData))
    private val stopRepository = StopRepository(staticData)

    @Test
    fun `health does not require an api key`() = testApplication {
        application { configureServer(config, feedCache, stopRepository) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `arrivals without a key is unauthorized`() = testApplication {
        application { configureServer(config, feedCache, stopRepository) }
        val response = client.get("/arrivals?stopId=s1")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `arrivals with the wrong key is unauthorized`() = testApplication {
        application { configureServer(config, feedCache, stopRepository) }
        val response = client.get("/arrivals?stopId=s1") { header("X-Api-Key", "wrong") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `arrivals with the right key and no cached feed returns an empty list`() = testApplication {
        application { configureServer(config, feedCache, stopRepository) }
        val response = client.get("/arrivals?stopId=s1") { header("X-Api-Key", "secret") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    @Test
    fun `arrivals without a stopId is a bad request`() = testApplication {
        application { configureServer(config, feedCache, stopRepository) }
        val response = client.get("/arrivals") { header("X-Api-Key", "secret") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
