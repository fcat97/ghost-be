package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toPath

class RuleWatcherTest {
    @Test
    fun `read returns with bytes after a file change once the watch exists`() {
        val dir = "test/fixtures/responses".toPath()
        val fd = createRulesWatch(dir.toString())
        val fs = FileSystem.SYSTEM
        val marker = dir / "rule-watcher-marker.txt"
        fs.write(marker) { writeUtf8("x") }
        val n = readRulesWatch(fd)
        fs.delete(marker)
        assertTrue(n > 0, "expected inotify read() to return event bytes, got $n")
    }
}
