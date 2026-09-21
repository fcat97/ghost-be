package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

class ResolveWebDistTest {
    private val executableDir = "/opt/ghost-be".toPath()
    private val devFallback = "web-backoffice/build/tasks/_web-backoffice_buildWasmJsAppWasmJsRelease".toPath()

    @Test
    fun `an explicit --web-dist always wins`() {
        val result = resolveWebDist("./custom", executableDir, exists = { true })
        assertEquals("./custom".toPath(), result)
    }

    @Test
    fun `prefers the folder bundled next to the binary when it exists`() {
        val result = resolveWebDist(null, executableDir, exists = { it == executableDir / "web-backoffice" })
        assertEquals(executableDir / "web-backoffice", result)
    }

    @Test
    fun `falls back to the dev build output when nothing is bundled`() {
        val result = resolveWebDist(null, executableDir, exists = { false })
        assertEquals(devFallback, result)
    }

    @Test
    fun `falls back to the dev build output when the executable dir is unknown`() {
        val result = resolveWebDist(null, executableDir = null, exists = { true })
        assertEquals(devFallback, result)
    }
}
