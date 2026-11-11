package dev.dublinbustracker.server

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class ResponseCacheTest {
    private var now = 1_000_000L
    private val static = Any()

    private fun cache(maxEntries: Int = 256) =
        ResponseCache<String>(ttlMs = 15_000, maxEntries = maxEntries, nowMs = { now })

    @Test
    fun `second call with same stamp within ttl does not recompute`() = runBlocking {
        val c = cache()
        val calls = AtomicInteger()
        val a = c.getOrCompute("k", listOf(1L, 2L), static) { "v${calls.incrementAndGet()}" }
        now += 14_000
        val b = c.getOrCompute("k", listOf(1L, 2L), static) { "v${calls.incrementAndGet()}" }
        assertEquals("v1", a)
        assertEquals("v1", b)
        assertEquals(1, calls.get())
    }

    @Test
    fun `changed feedStamp recomputes and drops older entries`() = runBlocking {
        val c = cache()
        val calls = AtomicInteger()
        c.getOrCompute("a", listOf(1L, 1L), static) { "a${calls.incrementAndGet()}" }
        c.getOrCompute("b", listOf(1L, 1L), static) { "b${calls.incrementAndGet()}" }
        assertEquals(2, c.size())
        val r = c.getOrCompute("a", listOf(2L, 1L), static) { "a${calls.incrementAndGet()}" }
        assertEquals("a3", r)
        assertEquals(1, c.size())
        // b was dropped: recomputes even under its old stamp
        c.getOrCompute("b", listOf(1L, 1L), static) { "b${calls.incrementAndGet()}" }
        assertEquals(4, calls.get())
    }

    @Test
    fun `ttl expiry recomputes`() = runBlocking {
        val c = cache()
        val calls = AtomicInteger()
        c.getOrCompute("k", listOf(1L), static) { "v${calls.incrementAndGet()}" }
        now += 15_001
        val r = c.getOrCompute("k", listOf(1L), static) { "v${calls.incrementAndGet()}" }
        assertEquals("v2", r)
        assertEquals(2, calls.get())
    }

    @Test
    fun `different staticData instance recomputes`() = runBlocking {
        val c = cache()
        val calls = AtomicInteger()
        val s1 = Any()
        val s2 = Any()
        c.getOrCompute("k", listOf(1L), s1) { "v${calls.incrementAndGet()}" }
        val r = c.getOrCompute("k", listOf(1L), s2) { "v${calls.incrementAndGet()}" }
        assertEquals("v2", r)
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent callers for one key compute once`() = runBlocking {
        val c = cache()
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val jobs =
            (1..10).map {
                async(Dispatchers.Default) {
                    c.getOrCompute("k", listOf(1L), static) {
                        calls.incrementAndGet()
                        release.await()
                        "value"
                    }
                }
            }
        // Wait until all callers have entered (queued on the key lock), or a second compute has started.
        withTimeout(5_000) {
            while (c.lockUsers() < 10 && calls.get() < 2) delay(5)
        }
        release.countDown()
        val results = jobs.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(List(10) { "value" }, results)
    }

    @Test
    fun `cancelled waiter does not leak the per-key lock`() = runBlocking {
        val c = cache()
        val release = CountDownLatch(1)
        val first = async(Dispatchers.Default) { c.getOrCompute("k", listOf(1L), static) { release.await(); "v1" } }
        val waiter = async(Dispatchers.Default) { c.getOrCompute("k", listOf(1L), static) { "never" } }
        withTimeout(5_000) { while (c.lockUsers() < 2) delay(5) }

        // Hold the guard so the waiter's cleanup contends on it while it is cancelled.
        c.guard.lock()
        waiter.cancel()
        delay(100)
        c.guard.unlock()
        release.countDown()

        assertEquals("v1", first.await())
        waiter.join()
        assertEquals(0, c.lockCount())
        assertEquals("v1", c.getOrCompute("k", listOf(1L), static) { "v2" })
    }

    @Test
    fun `compute exception propagates, is not cached, and next call recomputes`() = runBlocking {
        val c = cache()
        val calls = AtomicInteger()
        assertFailsWith<IllegalStateException> {
            c.getOrCompute("k", listOf(1L), static) {
                calls.incrementAndGet()
                error("boom")
            }
        }
        val r = c.getOrCompute("k", listOf(1L), static) { "v${calls.incrementAndGet()}" }
        assertEquals("v2", r)
        assertEquals(2, calls.get())
    }

    @Test
    fun `entry count never exceeds maxEntries`() = runBlocking {
        val c = cache(maxEntries = 3)
        for (i in 1..10) {
            c.getOrCompute("k$i", listOf(1L), static) { "v$i" }
            assertTrue(c.size() <= 3, "size ${c.size()} after key $i")
        }
        assertEquals(3, c.size())
    }
}
