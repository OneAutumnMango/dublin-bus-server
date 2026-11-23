package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.FeedEntity
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StaticDataRefresherTest {
    private val emptyBundle = GtfsStaticData.parse("""{"version":2,"stops":[]}""")

    private fun fakeScript(dir: File, body: String): String {
        val script = File(dir, "fake-fetch.sh")
        script.writeText("#!/bin/bash\n$body\n")
        script.setExecutable(true)
        return script.path
    }

    @Test
    fun `a successful refresh swaps in the new static data`() = runTest {
        val dir = Files.createTempDirectory("refresher-test").toFile()
        val fixture = File(dir, "fixture.json").apply { writeText(Fixtures.staticBundleJson()) }
        val script = fakeScript(dir, "cp '${fixture.path}' \"\$2\"\nexit 0")
        val target = StaticDataRef(emptyBundle)
        val refresher = StaticDataRefresher(
            target = target,
            outputPath = File(dir, "out.json").path,
            intervalMs = 1,
            scriptPath = script,
        )

        refresher.refresh()

        assertTrue(target.current.allStops().isNotEmpty())
    }

    @Test
    fun `a bundle already older than the interval is refreshed at start`() = runTest {
        val dir = Files.createTempDirectory("refresher-test").toFile()
        val fixture = File(dir, "fixture.json").apply { writeText(Fixtures.staticBundleJson()) }
        val script = fakeScript(dir, "cp '${fixture.path}' \"\$2\"\nexit 0")
        val out = File(dir, "out.json").apply { writeText("{}"); setLastModified(System.currentTimeMillis() - 48 * 3_600_000L) }
        val target = StaticDataRef(emptyBundle)
        StaticDataRefresher(target, out.path, intervalMs = 24 * 3_600_000L, scriptPath = script).start(backgroundScope)

        withContext(Dispatchers.Default) {
            repeat(100) { if (target.current.allStops().isEmpty()) delay(50) }
        }

        assertTrue(target.current.allStops().isNotEmpty())
    }

    @Test
    fun `a failed refresh keeps the previous bundle and does not throw`() = runTest {
        val dir = Files.createTempDirectory("refresher-test").toFile()
        val script = fakeScript(dir, "exit 1")
        val target = StaticDataRef(emptyBundle)
        val refresher = StaticDataRefresher(
            target = target,
            outputPath = File(dir, "out.json").path,
            intervalMs = 1,
            scriptPath = script,
        )

        refresher.refresh()

        assertSame(emptyBundle, target.current)
    }

    @Test
    fun `a script that writes half a bundle then fails leaves the previous bundle in place`() = runTest {
        val dir = Files.createTempDirectory("refresher-test").toFile()
        val script = fakeScript(dir, "printf '{\"stops\":[{\"id\"' > \"\$2\"\nexit 1")
        val target = StaticDataRef(emptyBundle)
        val refresher = StaticDataRefresher(
            target = target,
            outputPath = File(dir, "out.json").path,
            intervalMs = 1,
            scriptPath = script,
        )

        refresher.refresh()

        assertSame(emptyBundle, target.current)
    }

    @Test
    fun `a script that exits 0 but leaves unparseable output keeps the previous bundle`() = runTest {
        val dir = Files.createTempDirectory("refresher-test").toFile()
        val script = fakeScript(dir, "printf '{\"stops\":[' > \"\$2\"\nexit 0")
        val target = StaticDataRef(emptyBundle)
        val refresher = StaticDataRefresher(
            target = target,
            outputPath = File(dir, "out.json").path,
            intervalMs = 1,
            scriptPath = script,
        )

        refresher.refresh()

        assertSame(emptyBundle, target.current)
    }

    @Test
    fun `a missing script is a no-op, not a crash`() = runTest {
        val dir = Files.createTempDirectory("refresher-test").toFile()
        val target = StaticDataRef(emptyBundle)
        val refresher = StaticDataRefresher(
            target = target,
            outputPath = File(dir, "out.json").path,
            intervalMs = 1,
            scriptPath = File(dir, "missing.sh").path,
        )

        refresher.refresh()

        assertSame(emptyBundle, target.current)
    }

    // --- bad-bundle detector -------------------------------------------------------------

    private val rigs = mutableListOf<Rig>()

    @AfterTest
    fun cancelRigs() { rigs.forEach { it.scope.cancel() }; rigs.clear() }

    private inner class Rig(val cooldownMs: Long = 6 * 3_600_000L, scriptBody: String? = null) {
        val dir: File = Files.createTempDirectory("refresher-detect").toFile()
        val counter = File(dir, "runs")
        val fixture = File(dir, "fixture.json").apply { writeText(Fixtures.staticBundleJson()) }
        val script = File(dir, "fake-fetch.sh").apply {
            writeText("#!/bin/bash\necho x >> '${counter.path}'\n${scriptBody ?: "cp '${fixture.path}' \"\$2\""}\nexit 0\n")
            setExecutable(true)
        }
        var now = 1_000_000_000_000L
        val target = StaticDataRef(GtfsStaticData.parse(Fixtures.staticBundleJson()))
        val refresher = StaticDataRefresher(
            target, File(dir, "out.json").path, intervalMs = Long.MAX_VALUE / 4, scriptPath = script.path,
            nowMs = { now }, cooldownMs = cooldownMs,
        )
        // Real time, not runTest's virtual clock, which would skip the timer loop's delay.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        init { rigs += this }
        val runs get() = if (counter.exists()) counter.readLines().size else 0
    }

    private fun feed(vararg tripIds: String, added: Boolean = false): FeedMessage = FeedMessage.newBuilder()
        .setHeader(com.google.transit.realtime.GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
        .also { b ->
            tripIds.forEach { id ->
                val trip = TripDescriptor.newBuilder().setTripId(id)
                if (added) trip.scheduleRelationship = TripDescriptor.ScheduleRelationship.ADDED
                b.addEntity(FeedEntity.newBuilder().setId(id)
                    .setTripUpdate(com.google.transit.realtime.GtfsRealtime.TripUpdate.newBuilder().setTrip(trip)))
            }
        }.build()

    private suspend fun waitFor(cond: () -> Boolean) = withContext(Dispatchers.Default) {
        repeat(100) { if (!cond()) delay(50) }
    }

    @Test
    fun `observe launches a refresh after five consecutive polls with unmatched trips`() = runTest {
        val rig = Rig()
        rig.refresher.start(rig.scope)
        repeat(4) { rig.refresher.observe(feed("nope1", "nope2", "nope3")) }
        assertEquals(0, rig.runs)
        rig.refresher.observe(feed("nope1", "nope2", "nope3"))
        waitFor { rig.runs == 1 }
        assertEquals(1, rig.runs)
        assertEquals(3, rig.refresher.unmatchedTripUpdates)
    }

    @Test
    fun `a clean poll resets the streak`() = runTest {
        val rig = Rig()
        rig.refresher.start(rig.scope)
        repeat(4) { rig.refresher.observe(feed("nope")) }
        rig.refresher.observe(feed("t1"))
        repeat(4) { rig.refresher.observe(feed("nope")) }
        waitFor { rig.runs > 0 }
        assertEquals(0, rig.runs)
    }

    @Test
    fun `added trips are not counted as unmatched`() = runTest {
        val rig = Rig()
        rig.refresher.start(rig.scope)
        repeat(6) { rig.refresher.observe(feed("extra1", "extra2", added = true)) }
        waitFor { rig.runs > 0 }
        assertEquals(0, rig.runs)
        assertEquals(0, rig.refresher.unmatchedTripUpdates)
    }

    @Test
    fun `cooldown blocks a second refresh within the cooldown`() = runTest {
        val rig = Rig()
        rig.refresher.start(rig.scope)
        repeat(5) { rig.refresher.observe(feed("nope")) }
        waitFor { rig.runs == 1 }
        rig.now += 3_600_000L
        repeat(5) { rig.refresher.observe(feed("nope")) }
        waitFor { rig.runs > 1 }
        assertEquals(1, rig.runs)
        rig.now += 6 * 3_600_000L
        repeat(5) { rig.refresher.observe(feed("nope")) }
        waitFor { rig.runs == 2 }
        assertEquals(2, rig.runs)
    }

    @Test
    fun `a running refresh is not started twice`() = runTest {
        val rig = Rig(cooldownMs = 0, scriptBody = "sleep 1\ncp '\$(dirname \"\$0\")/fixture.json' \"\$2\"")
        rig.refresher.start(rig.scope)
        repeat(5) { rig.refresher.observe(feed("nope")) }
        waitFor { rig.runs == 1 }
        repeat(5) { rig.refresher.observe(feed("nope")) }
        waitFor { rig.runs > 1 }
        assertEquals(1, rig.runs)
    }
}
