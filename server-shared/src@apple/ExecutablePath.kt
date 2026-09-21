package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.Path
import okio.Path.Companion.toPath
import platform.darwin._NSGetExecutablePath

@OptIn(ExperimentalForeignApi::class)
actual fun executableDir(): Path? = memScoped {
    val size = alloc<UIntVar>()
    size.value = 0u
    _NSGetExecutablePath(null, size.ptr)
    if (size.value == 0u) return@memScoped null

    val buffer = ByteArray(size.value.toInt())
    val ok = buffer.usePinned { pinned -> _NSGetExecutablePath(pinned.addressOf(0), size.ptr) }
    if (ok != 0) return@memScoped null

    val terminator = buffer.indexOf(0.toByte()).let { if (it < 0) buffer.size else it }
    buffer.decodeToString(0, terminator).toPath().parent
}
