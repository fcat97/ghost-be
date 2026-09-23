package ghostbe.server

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class TestSessionTest {
    private fun envelope(method: String = "GET", url: String = "https://api.example.com/v1/x", body: String? = null) =
        RequestEnvelope(method, url, emptyMap(), body)

    @Test
    fun `activating a scenario twice leaves one entry`() {
        val session = TestSession()
        session.activate("checkout-fails")
        session.activate("checkout-fails")
        assertEquals(setOf("checkout-fails"), session.activeScenarios)
    }

    @Test
    fun `deactivating a scenario that was never active is a no-op`() {
        val session = TestSession()
        session.deactivate("never-on")
        assertEquals(emptySet(), session.activeScenarios)
    }

    @Test
    fun `setScenarios replaces the active set rather than merging`() {
        val session = TestSession()
        session.setScenarios(setOf("a", "b"))
        session.setScenarios(setOf("c"))
        assertEquals(setOf("c"), session.activeScenarios)
    }

    @Test
    fun `nextHit counts from zero and counts each rule independently`() {
        val session = TestSession()
        assertEquals(listOf(0, 1, 2), (0..2).map { session.nextHit("rule-a") })
        assertEquals(0, session.nextHit("rule-b"))
        assertEquals(3, session.nextHit("rule-a"))
    }

    @Test
    fun `the journal evicts its oldest entry at capacity`() {
        val session = TestSession(journalCapacity = 3)
        repeat(5) { session.record(envelope(url = "https://api.example.com/v1/$it"), "/v1/$it", null, null) }
        val journal = session.snapshot().journal
        assertEquals(3, journal.size)
        // seq keeps rising across the wrap, and it is the LAST three that survive.
        assertEquals(listOf(2L, 3L, 4L), journal.map { it.seq })
        assertEquals(listOf("/v1/2", "/v1/3", "/v1/4"), journal.map { it.path })
    }

    @Test
    fun `a capacity of one keeps only the newest entry`() {
        val session = TestSession(journalCapacity = 1)
        repeat(3) { session.record(envelope(url = "https://api.example.com/v1/$it"), "/v1/$it", null, null) }
        assertEquals(listOf("/v1/2"), session.snapshot().journal.map { it.path })
    }

    @Test
    fun `the journal decodes a base64 body so a user can filter on what they wrote`() {
        val session = TestSession()
        val payload = """{"card":"4242"}"""
        session.record(envelope("POST", body = Base64.encode(payload.encodeToByteArray())), "/v1/pay", "pay", 200)
        val entry = session.snapshot().journal.single()
        assertEquals(payload, entry.body)
        assertTrue(entry.body!!.contains("4242"))
        assertFalse(entry.bodyTruncated)
    }

    @Test
    fun `a body that is not valid base64 is kept verbatim`() {
        val session = TestSession()
        session.record(envelope("POST", body = """{"a":1}"""), "/v1/x", null, null)
        assertEquals("""{"a":1}""", session.snapshot().journal.single().body)
    }

    @Test
    fun `a null body stays null`() {
        val session = TestSession()
        session.record(envelope(), "/v1/x", null, null)
        assertNull(session.snapshot().journal.single().body)
    }

    @Test
    fun `an oversized body is truncated and flagged`() {
        val session = TestSession(bodyLimit = 10)
        session.record(envelope("POST", body = "x".repeat(50)), "/v1/x", null, null)
        val entry = session.snapshot().journal.single()
        assertEquals(10, entry.body!!.length)
        assertTrue(entry.bodyTruncated)
    }

    @Test
    fun `reset clears scenarios counters and journal together`() {
        val session = TestSession()
        session.activate("checkout-fails")
        session.nextHit("rule-a")
        session.record(envelope(), "/v1/x", "rule-a", 200)

        session.reset()

        val state = session.snapshot()
        assertEquals(emptySet(), state.activeScenarios)
        assertEquals(emptyMap(), state.hits)
        assertEquals(emptyList(), state.journal)
        assertEquals(0, session.nextHit("rule-a"))
    }

    @Test
    fun `a held snapshot is unaffected by later mutations`() {
        // Guards against a mutable collection creeping into TestState, which would
        // silently break the CAS design.
        val session = TestSession()
        session.activate("a")
        session.record(envelope(), "/v1/x", null, null)
        val held = session.snapshot()

        session.activate("b")
        session.record(envelope(), "/v1/y", null, null)
        session.nextHit("r")

        assertEquals(setOf("a"), held.activeScenarios)
        assertEquals(1, held.journal.size)
        assertEquals(emptyMap(), held.hits)
    }
}
