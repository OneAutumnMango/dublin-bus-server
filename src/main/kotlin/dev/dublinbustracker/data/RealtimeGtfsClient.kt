package dev.dublinbustracker.data

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request

const val DEFAULT_GTFSR_BASE_URL = "https://api.nationaltransport.ie/gtfsr/v2"

/**
 * Each NTA key is rate limited to one call per 60s, and the limit applies per key rather
 * than per feed, so every request shares one rotating pool of keys.
 */
class RealtimeGtfsClient(
    rawApiKeys: List<String>,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_GTFSR_BASE_URL,
) {
    constructor(apiKey: String, httpClient: OkHttpClient = OkHttpClient(), baseUrl: String = DEFAULT_GTFSR_BASE_URL) :
        this(listOf(apiKey), httpClient, baseUrl)

    private val apiKeys = rawApiKeys.filter { it.isNotBlank() }
    private val nextKeyIndex = AtomicInteger(0)
    private val requestCount = AtomicLong(0)

    init {
        require(apiKeys.isNotEmpty()) { "at least one non-blank API key is required" }
    }

    val keyCount: Int get() = apiKeys.size

    /** Total upstream calls made, surfaced on /v1/status so the rate-limit budget is observable. */
    val totalRequests: Long get() = requestCount.get()

    fun fetch(kind: FeedKind): FeedMessage {
        val key = apiKeys[nextKeyIndex.getAndIncrement().mod(apiKeys.size)]
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/${kind.path}")
            .header("x-api-key", key)
            .build()
        requestCount.incrementAndGet()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GTFS-R ${kind.path} request failed: ${response.code}" }
            return FeedMessage.parseFrom(response.body!!.byteStream())
        }
    }
}
