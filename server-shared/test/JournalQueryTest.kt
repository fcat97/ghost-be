package ghostbe.server

import kotlin.test.Test
import kotlin.test.assertEquals

class JournalQueryTest {
    private fun entry(
        seq: Long,
        method: String = "GET",
        path: String = "/v1/x",
        body: String? = null,
        bodyRaw: String? = null
    ) = JournalEntry(
        seq = seq,
        timestamp = 0,
        method = method,
        url = "https://api.example.com$path",
        path = path,
        headers = emptyMap(),
        body = body,
        bodyRaw = bodyRaw
    )

    private val entries = listOf(
        entry(0, "GET", "/v1/cart"),
        entry(1, "POST", "/v1/checkout", body = """{"coupon":"SAVE10"}"""),
        entry(2, "POST", "/v1/checkout", body = """{"coupon":"NONE"}"""),
        entry(3, "GET", "/v1/order/1")
    )

    @Test
    fun `no filter returns everything`() {
        val result = queryJournal(entries, JournalFilter())
        assertEquals(4, result.count)
        assertEquals(4, result.entries.size)
    }

    @Test
    fun `filters by method case-insensitively`() {
        assertEquals(2, queryJournal(entries, JournalFilter(method = "post")).count)
    }

    @Test
    fun `filters by exact path`() {
        val result = queryJournal(entries, JournalFilter(path = "/v1/checkout"))
        assertEquals(2, result.count)
        assertEquals(listOf(1L, 2L), result.entries.map { it.seq })
    }

    @Test
    fun `filters by body substring`() {
        val result = queryJournal(entries, JournalFilter(bodyContains = "SAVE10"))
        assertEquals(1, result.count)
        assertEquals(1L, result.entries.single().seq)
    }

    @Test
    fun `combines filters with AND`() {
        val result = queryJournal(entries, JournalFilter(method = "POST", path = "/v1/checkout", bodyContains = "SAVE10"))
        assertEquals(1, result.count)
    }

    @Test
    fun `no matches returns an empty result`() {
        val result = queryJournal(entries, JournalFilter(path = "/v1/nope"))
        assertEquals(0, result.count)
        assertEquals(emptyList(), result.entries)
    }

    @Test
    fun `bodyContains also matches a body that was stored raw`() {
        val raw = listOf(entry(0, body = null, bodyRaw = "not-base64-{token}"))
        assertEquals(1, queryJournal(raw, JournalFilter(bodyContains = "token")).count)
    }

    @Test
    fun `limit truncates the entries but not the count`() {
        val result = queryJournal(entries, JournalFilter(limit = 2))
        assertEquals(4, result.count)
        // Newest kept, so a flow querying with a small limit still sees the latest calls.
        assertEquals(listOf(2L, 3L), result.entries.map { it.seq })
    }
}
