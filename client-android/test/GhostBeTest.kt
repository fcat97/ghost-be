package dev.yellowbytes.ghostbe.client

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GhostBeTest {

    @AfterTest
    fun clearCapturedServer() {
        GhostBe.clearCapturedServer()
    }

    @Test
    fun `captures the ghostBe query param as an http baseUrl`() {
        GhostBe.captureFromUriString("myapp://open?ghostBe=192.168.1.5:44678")
        assertEquals("http://192.168.1.5:44678", GhostBe.overrideBaseUrl)
    }

    @Test
    fun `ignores unrelated query params`() {
        GhostBe.captureFromUriString("myapp://open?utm_source=qa&ghostBe=192.168.1.5:44678&foo=bar")
        assertEquals("http://192.168.1.5:44678", GhostBe.overrideBaseUrl)
    }

    @Test
    fun `does nothing when the link has no ghostBe param`() {
        GhostBe.captureFromUriString("myapp://open?foo=bar")
        assertNull(GhostBe.overrideBaseUrl)
    }

    @Test
    fun `does nothing when there is no link at all`() {
        GhostBe.captureFromUriString(null)
        assertNull(GhostBe.overrideBaseUrl)
    }
}
