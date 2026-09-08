package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Path
import platform.linux.IN_CLOSE_WRITE
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.inotify_add_watch
import platform.linux.inotify_init1
import platform.posix.read

@OptIn(ExperimentalForeignApi::class)
internal fun createRulesWatch(path: String): Int {
    val fd = inotify_init1(0)
    val mask = (IN_MODIFY or IN_CREATE or IN_DELETE or IN_MOVED_TO or IN_MOVED_FROM or IN_CLOSE_WRITE).toUInt()
    inotify_add_watch(fd, path, mask)
    return fd
}

@OptIn(ExperimentalForeignApi::class)
internal fun readRulesWatch(fd: Int): Int {
    val buffer = ByteArray(4096)
    return buffer.usePinned { pinned -> read(fd, pinned.addressOf(0), buffer.size.toULong()) }.toInt()
}

@OptIn(ExperimentalForeignApi::class)
actual fun watchRulesDirectory(rulesDir: Path, onChange: () -> Unit) {
    val fd = createRulesWatch(rulesDir.toString())
    while (true) {
        val n = readRulesWatch(fd)
        if (n <= 0) break
        onChange()
    }
}
