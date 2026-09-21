package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSProcessInfo
import platform.posix.free
import platform.posix.realpath

/**
 * Darwin has no /proc/self/exe, so this resolves argv[0] (via NSProcessInfo, which
 * captures it verbatim) against the process's cwd and symlinks with realpath() --
 * reliable as long as the binary was launched by path (e.g. `./ghost-be`), which is
 * how every documented workflow for this binary invokes it. A bare `ghost-be` found
 * via $PATH would leave argv[0] without a path component for realpath to resolve,
 * falling through to null (the dev-tree fallback) rather than a wrong directory.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun executableDir(): Path? {
    val argv0 = NSProcessInfo.processInfo.arguments.firstOrNull() ?: return null
    val resolved = realpath(argv0, null) ?: return null
    val path = resolved.toKString()
    free(resolved)
    return path.toPath().parent
}
