package dev.dublinbustracker.server

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NtfyNotifierTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a blank or missing url disables alerting regardless of level`() {
        NtfyNotifier(null, NtfyLevel.INFO).notify(NtfyLevel.ERROR, "title", "message")
        NtfyNotifier("", NtfyLevel.INFO).notify(NtfyLevel.ERROR, "title", "message")

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `off sends nothing even for the most severe category`() {
        val notifier = NtfyNotifier(server.url("/t").toString(), NtfyLevel.OFF)

        notifier.notify(NtfyLevel.ERROR, "title", "message")

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a level sends only categories at or above its own severity`() {
        repeat(3) { server.enqueue(MockResponse()) }
        val notifier = NtfyNotifier(server.url("/t").toString(), NtfyLevel.WARN)

        notifier.notify(NtfyLevel.INFO, "title", "too verbose for warn")
        notifier.notify(NtfyLevel.WARN, "title", "warn")
        notifier.notify(NtfyLevel.ERROR, "title", "error")

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the push carries the title header and the message as the body`() {
        server.enqueue(MockResponse())
        val notifier = NtfyNotifier(server.url("/topic").toString(), NtfyLevel.INFO)

        notifier.notify(NtfyLevel.INFO, "Dublin Bus Server", "TRIP_UPDATES poll failed")

        val request = server.takeRequest()
        assertEquals("/topic", request.path)
        assertEquals("Dublin Bus Server", request.getHeader("Title"))
        assertEquals("TRIP_UPDATES poll failed", request.body.readUtf8())
    }

    @Test
    fun `a push failure is swallowed rather than thrown`() {
        val url = server.url("/topic").toString()
        server.shutdown()

        NtfyNotifier(url, NtfyLevel.INFO).notify(NtfyLevel.ERROR, "title", "unreachable topic")
    }

    @Test
    fun `fromEnv reads NTFY_URL and NTFY_LEVEL, treating blank values as unset`() {
        server.enqueue(MockResponse())
        val configured = NtfyNotifier.fromEnv {
            mapOf("NTFY_URL" to server.url("/t").toString(), "NTFY_LEVEL" to "warn")[it]
        }
        configured.notify(NtfyLevel.INFO, "title", "filtered out by warn")
        configured.notify(NtfyLevel.WARN, "title", "sent")
        assertEquals(1, server.requestCount)

        val disabled = NtfyNotifier.fromEnv { mapOf("NTFY_URL" to "", "NTFY_LEVEL" to "")[it] }
        disabled.notify(NtfyLevel.ERROR, "title", "still disabled")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `NtfyLevel parse defaults to info, is case-insensitive, and rejects garbage`() {
        assertEquals(NtfyLevel.INFO, NtfyLevel.parse(null))
        assertEquals(NtfyLevel.INFO, NtfyLevel.parse(""))
        assertEquals(NtfyLevel.WARN, NtfyLevel.parse("warn"))
        assertEquals(NtfyLevel.OFF, NtfyLevel.parse("OFF"))
        assertFailsWith<IllegalStateException> { NtfyLevel.parse("loud") }
    }
}
