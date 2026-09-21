package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import okio.Path
import okio.Path.Companion.toPath
import platform.windows.GetModuleFileNameW
import platform.windows.WCHARVar

@OptIn(ExperimentalForeignApi::class)
actual fun executableDir(): Path? = memScoped {
    val bufferSize = 32768 // supports long Windows paths
    val buffer = allocArray<WCHARVar>(bufferSize)
    val length = GetModuleFileNameW(null, buffer, bufferSize.toUInt())
    if (length == 0u) return@memScoped null

    val chars = CharArray(length.toInt()) { i -> buffer[i].toInt().toChar() }
    String(chars).toPath().parent
}
