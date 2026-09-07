package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.windows.FALSE
import platform.windows.FILE_NOTIFY_CHANGE_DIR_NAME
import platform.windows.FILE_NOTIFY_CHANGE_FILE_NAME
import platform.windows.FILE_NOTIFY_CHANGE_LAST_WRITE
import platform.windows.FindCloseChangeNotification
import platform.windows.FindFirstChangeNotificationW
import platform.windows.FindNextChangeNotification
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject

/**
 * Blocks on FindFirstChangeNotificationW/WaitForSingleObject the same way the Linux
 * actual blocks on an inotify fd -- one native wait per detected change, re-armed via
 * FindNextChangeNotification after each signal (Windows auto-resets the handle once
 * consumed, unlike inotify which just keeps queuing events on the same fd).
 */
@OptIn(ExperimentalForeignApi::class)
actual fun watchRulesDirectory(rulesDir: Path, onChange: () -> Unit) {
    val mask = (FILE_NOTIFY_CHANGE_FILE_NAME or FILE_NOTIFY_CHANGE_DIR_NAME or FILE_NOTIFY_CHANGE_LAST_WRITE).toUInt()
    val handle = FindFirstChangeNotificationW(rulesDir.toString(), FALSE, mask)
    check(handle != INVALID_HANDLE_VALUE) { "FindFirstChangeNotificationW failed for $rulesDir" }
    try {
        while (true) {
            val result = WaitForSingleObject(handle, INFINITE)
            if (result != WAIT_OBJECT_0) break
            onChange()
            if (FindNextChangeNotification(handle) == 0) break
        }
    } finally {
        FindCloseChangeNotification(handle)
    }
}
