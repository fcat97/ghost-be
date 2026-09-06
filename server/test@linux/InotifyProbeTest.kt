package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toPath

class InotifyProbeTest {
    @Test
    fun `read returns with bytes after a file change once the watch exists`() {
        val dir = "test/fixtures/responses".toPath()
        val fd = createWatch(dir.toString())
        val fs = FileSystem.SYSTEM
        val marker = dir / "inotify-probe-marker.txt"
        fs.write(marker) { writeUtf8("x") }
        val n = readOnce(fd)
        fs.delete(marker)
        assertTrue(n > 0, "expected inotify read() to return event bytes, got $n")
    }
}
