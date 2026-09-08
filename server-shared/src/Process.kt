package ghostbe.server

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Spawns `command arg`, writes [stdin] to the child's stdin (closing it afterwards so the
 * child sees EOF), then reads the child's stdout/stderr to completion.
 *
 * Writes all of stdin before reading any output, so this can deadlock if the child
 * writes more than one pipe buffer (64KB on Linux) before this function starts
 * reading -- acceptable for the small JSON payloads ghost-be scripts produce.
 */
expect fun runProcess(command: String, arg: String, stdin: String): ProcessResult
