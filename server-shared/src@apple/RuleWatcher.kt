package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.Path
import platform.darwin.EVFILT_VNODE
import platform.darwin.EV_ADD
import platform.darwin.EV_CLEAR
import platform.darwin.NOTE_WRITE
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.O_EVTONLY
import platform.posix.close
import platform.posix.open

/**
 * macOS has no inotify, so this uses kqueue's EVFILT_VNODE/NOTE_WRITE on the rules
 * directory's own fd -- the BSD/Darwin equivalent of watching a directory for content
 * changes (files added/removed/renamed/written), rather than watching each file
 * individually.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun watchRulesDirectory(rulesDir: Path, onChange: () -> Unit) {
    val fd = open(rulesDir.toString(), O_EVTONLY)
    check(fd >= 0) { "open() failed for $rulesDir" }
    val kq = kqueue()
    check(kq >= 0) { "kqueue() failed" }

    try {
        memScoped {
            val changeEvent = alloc<kevent>()
            changeEvent.ident = fd.toULong()
            changeEvent.filter = EVFILT_VNODE.toShort()
            changeEvent.flags = (EV_ADD or EV_CLEAR).toUShort()
            changeEvent.fflags = NOTE_WRITE.toUInt()
            changeEvent.data = 0L
            changeEvent.udata = null

            while (true) {
                val triggered = alloc<kevent>()
                val n = kevent(kq, changeEvent.ptr, 1, triggered.ptr, 1, null)
                if (n <= 0) break
                onChange()
            }
        }
    } finally {
        close(fd)
        close(kq)
    }
}
