package dev.dublinbustracker.data

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TRIP_UPDATES_URL = "https://api.nationaltransport.ie/gtfsr/v2/TripUpdates"

/**
 * Each NTA key has its own 60s rate limit; supplying two unlinked keys lets requests
 * alternate between them, halving the effective wait between requests.
 */
class RealtimeGtfsClient(
    rawApiKeys: List<String>,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val feedUrl: String = TRIP_UPDATES_URL,
) {
    constructor(apiKey: String, httpClient: OkHttpClient = OkHttpClient(), feedUrl: String = TRIP_UPDATES_URL) :
        this(listOf(apiKey), httpClient, feedUrl)

    private val apiKeys = rawApiKeys.filter { it.isNotBlank() }
    private val nextKeyIndex = AtomicInteger(0)

    init {
        require(this.apiKeys.isNotEmpty()) { "at least one non-blank API key is required" }
    }

    val keyCount: Int get() = apiKeys.size

    fun fetchTripUpdates(): FeedMessage {
        val key = apiKeys[nextKeyIndex.getAndIncrement().mod(apiKeys.size)]
        val request = Request.Builder().url(feedUrl).header("x-api-key", key).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GTFS-R request failed: ${response.code}" }
            return FeedMessage.parseFrom(response.body!!.byteStream())
        }
    }
}
