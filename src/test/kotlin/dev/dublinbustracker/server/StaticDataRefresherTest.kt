package dev.dublinbustracker.server

import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.StaticDataRef
import dev.dublinbustracker.support.Fixtures
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
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
}
