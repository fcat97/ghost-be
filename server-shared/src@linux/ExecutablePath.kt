package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.readlink

@OptIn(ExperimentalForeignApi::class)
actual fun executableDir(): Path? {
    val buffer = ByteArray(4096)
    val n = buffer.usePinned { pinned -> readlink("/proc/self/exe", pinned.addressOf(0), buffer.size.toULong()) }
    if (n <= 0) return null
    return buffer.decodeToString(0, n.toInt()).toPath().parent
}
