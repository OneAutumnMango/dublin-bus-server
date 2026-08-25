package dev.dublinbustracker.server

class ServerConfig(
    val port: Int,
    val serverApiKey: String,
    val ntaApiKeys: List<String>,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig {
            val serverApiKey = env("SERVER_API_KEY")
                ?: error("SERVER_API_KEY is required - clients must send it as the X-Api-Key header")
            val ntaApiKeys = listOfNotNull(env("GTFSR_API_KEY"), env("GTFSR_API_KEY_SECONDARY"))
                .filter { it.isNotBlank() }
            require(ntaApiKeys.isNotEmpty()) { "GTFSR_API_KEY is required (GTFSR_API_KEY_SECONDARY is optional)" }
            return ServerConfig(
                port = env("PORT")?.toIntOrNull() ?: 8080,
                serverApiKey = serverApiKey,
                ntaApiKeys = ntaApiKeys,
            )
        }
    }
}
