package ghostbe.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.GetExitCodeProcess
import platform.windows.HANDLEVar
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INFINITE
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.SetHandleInformation
import platform.windows.TRUE
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile

@OptIn(ExperimentalForeignApi::class)
private fun readAll(handle: platform.windows.HANDLE?): String = memScoped {
    val buffer = ByteArray(4096)
    val out = StringBuilder()
    val bytesRead = alloc<DWORDVar>()
    while (true) {
        val ok = buffer.usePinned { pinned ->
            ReadFile(handle, pinned.addressOf(0), buffer.size.toUInt(), bytesRead.ptr, null)
        }
        if (ok == 0 || bytesRead.value == 0u) break
        out.append(buffer.decodeToString(0, bytesRead.value.toInt()))
    }
    out.toString()
}

/**
 * Spawns [command] [arg] via CreateProcessW with its std handles redirected to pipes.
 * lpApplicationName is left null so Windows resolves [command] against PATH the same
 * way cmd.exe would, mirroring the execlp() behavior of the Linux implementation.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun runProcess(command: String, arg: String, stdin: String): ProcessResult = memScoped {
    val security = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
        bInheritHandle = TRUE
        lpSecurityDescriptor = null
    }

    val stdinRead = alloc<HANDLEVar>()
    val stdinWrite = alloc<HANDLEVar>()
    val stdoutRead = alloc<HANDLEVar>()
    val stdoutWrite = alloc<HANDLEVar>()
    val stderrRead = alloc<HANDLEVar>()
    val stderrWrite = alloc<HANDLEVar>()

    check(CreatePipe(stdinRead.ptr, stdinWrite.ptr, security.ptr, 0u) == TRUE) { "CreatePipe failed for stdin" }
    check(CreatePipe(stdoutRead.ptr, stdoutWrite.ptr, security.ptr, 0u) == TRUE) { "CreatePipe failed for stdout" }
    check(CreatePipe(stderrRead.ptr, stderrWrite.ptr, security.ptr, 0u) == TRUE) { "CreatePipe failed for stderr" }

    // The parent's own ends must not be inherited by the child, or the child's copy
    // of e.g. stdoutRead keeps that pipe open after the child exits, and our read
    // loop below would then block forever waiting for EOF that never comes.
    SetHandleInformation(stdinWrite.value, HANDLE_FLAG_INHERIT.toUInt(), 0u)
    SetHandleInformation(stdoutRead.value, HANDLE_FLAG_INHERIT.toUInt(), 0u)
    SetHandleInformation(stderrRead.value, HANDLE_FLAG_INHERIT.toUInt(), 0u)

    val startupInfo = alloc<STARTUPINFOW>()
    startupInfo.cb = sizeOf<STARTUPINFOW>().toUInt()
    startupInfo.dwFlags = STARTF_USESTDHANDLES.toUInt()
    startupInfo.hStdInput = stdinRead.value
    startupInfo.hStdOutput = stdoutWrite.value
    startupInfo.hStdError = stderrWrite.value

    val processInfo = alloc<PROCESS_INFORMATION>()

    val commandLine = if (arg.isEmpty()) "\"$command\"" else "\"$command\" \"$arg\""
    val commandLinePtr = commandLine.wcstr.getPointer(this)

    val created = CreateProcessW(
        lpApplicationName = null,
        lpCommandLine = commandLinePtr,
        lpProcessAttributes = null,
        lpThreadAttributes = null,
        bInheritHandles = TRUE,
        dwCreationFlags = CREATE_NO_WINDOW.toUInt(),
        lpEnvironment = null,
        lpCurrentDirectory = null,
        lpStartupInfo = startupInfo.ptr,
        lpProcessInformation = processInfo.ptr
    )
    check(created == TRUE) { "CreateProcessW failed for $commandLine" }

    // Close the child's ends in the parent -- otherwise the parent's own copy of
    // e.g. stdoutWrite keeps that pipe open, and readAll() below never sees EOF.
    CloseHandle(stdinRead.value)
    CloseHandle(stdoutWrite.value)
    CloseHandle(stderrWrite.value)

    val stdinBytes = stdin.encodeToByteArray()
    val bytesWritten = alloc<DWORDVar>()
    stdinBytes.usePinned { pinned ->
        var written = 0
        while (written < stdinBytes.size) {
            val ok = WriteFile(
                stdinWrite.value,
                pinned.addressOf(written),
                (stdinBytes.size - written).toUInt(),
                bytesWritten.ptr,
                null
            )
            if (ok == 0 || bytesWritten.value == 0u) break
            written += bytesWritten.value.toInt()
        }
    }
    CloseHandle(stdinWrite.value)

    val stdout = readAll(stdoutRead.value)
    val stderr = readAll(stderrRead.value)
    CloseHandle(stdoutRead.value)
    CloseHandle(stderrRead.value)

    WaitForSingleObject(processInfo.hProcess, INFINITE)
    val exitCode = alloc<DWORDVar>()
    GetExitCodeProcess(processInfo.hProcess, exitCode.ptr)
    CloseHandle(processInfo.hProcess)
    CloseHandle(processInfo.hThread)

    ProcessResult(exitCode.value.toInt(), stdout, stderr)
}
