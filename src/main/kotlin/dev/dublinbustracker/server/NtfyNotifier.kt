package dev.dublinbustracker.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

/** Alert severities, most to least urgent, like a log level: a lower level is a subset of a higher one. */
enum class NtfyLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    ;

    fun allows(required: NtfyLevel): Boolean = this != OFF && ordinal >= required.ordinal

    companion object {
        fun parse(raw: String?): NtfyLevel {
            if (raw.isNullOrBlank()) return INFO
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: error("unknown NTFY_LEVEL '$raw'; valid: ${entries.joinToString()}")
        }
    }
}

/**
 * Pushes alerts to a self-hosted ntfy topic ([ntfy.sh](https://ntfy.sh)). A blank [url] disables
 * alerting entirely. [level] filters by severity: ERROR is startup failures only, WARN adds a
 * feed going stale or recovering, INFO adds every single poll failure.
 */
class NtfyNotifier(
    private val url: String?,
    private val level: NtfyLevel,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val logger = LoggerFactory.getLogger(NtfyNotifier::class.java)

    fun notify(required: NtfyLevel, title: String, message: String) {
        if (url.isNullOrBlank() || !level.allows(required)) return
        val request = Request.Builder()
            .url(url)
            .post(message.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("Title", title)
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
            .onFailure { logger.warn("ntfy push to {} failed: {}", url, it.message) }
    }

    companion object {
        fun fromEnv(rawEnv: (String) -> String? = System::getenv): NtfyNotifier {
            val env: (String) -> String? = { name -> rawEnv(name)?.takeIf { it.isNotBlank() } }
            return NtfyNotifier(env("NTFY_URL"), NtfyLevel.parse(env("NTFY_LEVEL")))
        }
    }
}
