package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EXIT_FAILURE
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.execlp
import platform.posix.fork
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
actual fun runProcess(command: String, arg: String, stdin: String): ProcessResult = memScoped {
    val stdinPipe = allocArray<IntVar>(2)
    val stdoutPipe = allocArray<IntVar>(2)
    val stderrPipe = allocArray<IntVar>(2)

    check(pipe(stdinPipe) == 0) { "pipe() failed for stdin" }
    check(pipe(stdoutPipe) == 0) { "pipe() failed for stdout" }
    check(pipe(stderrPipe) == 0) { "pipe() failed for stderr" }

    val stdinRead = stdinPipe[0]; val stdinWrite = stdinPipe[1]
    val stdoutRead = stdoutPipe[0]; val stdoutWrite = stdoutPipe[1]
    val stderrRead = stderrPipe[0]; val stderrWrite = stderrPipe[1]

    val pid = fork()
    check(pid >= 0) { "fork() failed" }

    if (pid == 0) {
        // Child: wire up the pipes and exec. No Kotlin heap allocation past this
        // point beyond what execlp/dup2/close themselves need -- fork() only
        // duplicates the calling thread, so anything relying on other threads
        // or the GC running is unsafe here.
        dup2(stdinRead, 0)
        dup2(stdoutWrite, 1)
        dup2(stderrWrite, 2)
        close(stdinRead); close(stdinWrite)
        close(stdoutRead); close(stdoutWrite)
        close(stderrRead); close(stderrWrite)
        if (arg.isEmpty()) {
            execlp(command, command, null)
        } else {
            execlp(command, command, arg, null)
        }
        _exit(EXIT_FAILURE) // only reached if execlp failed
    }

    // Parent
    close(stdinRead)
    close(stdoutWrite)
    close(stderrWrite)

    val stdinBytes = stdin.encodeToByteArray()
    stdinBytes.usePinned { pinned ->
        var written = 0
        while (written < stdinBytes.size) {
            val n = write(stdinWrite, pinned.addressOf(written), (stdinBytes.size - written).toULong())
            if (n <= 0) break
            written += n.toInt()
        }
    }
    close(stdinWrite)

    fun readAll(fd: Int): String {
        val buffer = ByteArray(4096)
        val out = StringBuilder()
        while (true) {
            val n = buffer.usePinned { pinned -> read(fd, pinned.addressOf(0), buffer.size.toULong()) }
            if (n <= 0) break
            out.append(buffer.decodeToString(0, n.toInt()))
        }
        return out.toString()
    }

    val stdout = readAll(stdoutRead)
    val stderr = readAll(stderrRead)
    close(stdoutRead)
    close(stderrRead)

    val status = alloc<IntVar>()
    waitpid(pid, status.ptr, 0)
    val exitCode = (status.value shr 8) and 0xFF

    ProcessResult(exitCode, stdout, stderr)
}
