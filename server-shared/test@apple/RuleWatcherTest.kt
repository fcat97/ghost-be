package ghostbe.server

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import okio.FileSystem
import okio.Path.Companion.toPath

class RuleWatcherTest {
    @Test
    fun `onChange fires after a file is written to the watched directory`() {
        val dir = "test/fixtures/responses".toPath()
        val changeCount = AtomicInt(0)

        val worker = Worker.start(name = "rule-watcher-test")
        worker.execute(TransferMode.SAFE, { dir to changeCount }) { (watchedDir, counter) ->
            watchRulesDirectory(watchedDir) { counter.value = counter.value + 1 }
        }

        // Give the worker time to actually call kqueue()/open() before we write --
        // there is no synchronous "watch established" signal to wait on instead.
        val setupDeadline = TimeSource.Monotonic.markNow() + 500.milliseconds
        while (setupDeadline.hasNotPassedNow()) {}

        val marker = dir / "rule-watcher-marker.txt"
        FileSystem.SYSTEM.write(marker) { writeUtf8("x") }

        val waitDeadline = TimeSource.Monotonic.markNow() + 5000.milliseconds
        while (changeCount.value == 0 && waitDeadline.hasNotPassedNow()) {}

        FileSystem.SYSTEM.delete(marker)
        assertTrue(changeCount.value > 0, "expected watchRulesDirectory to report a change, got count=${changeCount.value}")
    }
}
