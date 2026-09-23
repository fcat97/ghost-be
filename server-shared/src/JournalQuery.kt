package ghostbe.server

const val DEFAULT_JOURNAL_QUERY_LIMIT = 100
const val MAX_JOURNAL_QUERY_LIMIT = 1000

/** A null field means "don't filter on this"; the rest are AND-combined. */
data class JournalFilter(
    val method: String? = null,
    val path: String? = null,
    val bodyContains: String? = null,
    val limit: Int = DEFAULT_JOURNAL_QUERY_LIMIT
)

/**
 * [count] is the total number of matches, *before* [limit] is applied, so it stays a
 * trustworthy thing to assert on however many entries the caller asked to see.
 */
data class JournalResult(val count: Int, val entries: List<JournalEntry>)

private fun JournalEntry.matches(filter: JournalFilter): Boolean {
    if (filter.method != null && !filter.method.equals(method, ignoreCase = true)) return false
    if (filter.path != null && filter.path != path) return false
    if (filter.bodyContains != null) {
        // Checked against the decoded body and the raw one. A short plain-text body can
        // be coincidentally valid base64 and "decode" to nonsense, so testing both means
        // the caller never has to know which way their payload was stored.
        val hit = body?.contains(filter.bodyContains) == true || bodyRaw?.contains(filter.bodyContains) == true
        if (!hit) return false
    }
    return true
}

/** The matching entries, newest [JournalFilter.limit] first-to-last, plus the total count. */
fun queryJournal(entries: List<JournalEntry>, filter: JournalFilter): JournalResult {
    val matching = entries.filter { it.matches(filter) }
    return JournalResult(count = matching.size, entries = matching.takeLast(filter.limit))
}
