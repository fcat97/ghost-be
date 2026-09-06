package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessTest {
    @Test
    fun `runs a command feeds it stdin and captures stdout and exit code`() {
        // `cat` echoes stdin back to stdout unmodified -- a minimal, always-available
        // way to verify the stdin-write / stdout-read plumbing without a real script.
        val result = runProcess(command = "cat", arg = "", stdin = "hello ghost-be")
        assertEquals(0, result.exitCode)
        assertEquals("hello ghost-be", result.stdout)
    }

    @Test
    fun `captures a nonzero exit code`() {
        // `false` ignores its argument and always exits 1.
        val result = runProcess(command = "false", arg = "", stdin = "")
        assertEquals(1, result.exitCode)
    }
}
