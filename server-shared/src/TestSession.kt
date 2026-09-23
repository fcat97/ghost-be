package ghostbe.server

import kotlin.concurrent.AtomicReference
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable

const val DEFAULT_JOURNAL_CAPACITY = 500
const val DEFAULT_BODY_LIMIT = 8 * 1024

/**
 * One recorded request, as seen by `/intercept`.
 *
 * [body] is the base64-*decoded* request body where that was possible, because every
 * client base64-encodes bodies on the wire (see `RequestEnvelope.body`) and a user
 * filtering on `bodyContains=4242` means the payload they wrote, not its encoding.
 * [bodyRaw] keeps exactly what the client sent, since a short plain-text body can
 * coincidentally be valid base64 and "decode" to nonsense -- filters check both, so a
 * caller never has to know which happened.
 */
@Serializable
data class JournalEntry(
    val seq: Long,
    val timestamp: Long,
    val method: String,
    val url: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val bodyRaw: String? = null,
    val bodyTruncated: Boolean = false,
    val matchedRule: String? = null,
    val status: Int? = null
)

/**
 * The whole of a test run's ephemeral state, as one immutable value.
 *
 * Held in a single atomic cell rather than three, so that `reset` is one store instead
 * of a three-way consistency problem and `snapshot` hands out a view that cannot tear.
 *
 * Everything reachable from here must stay immutable -- the CAS loop in [TestSession]
 * depends on each update allocating fresh collections.
 */
data class TestState(
    val activeScenarios: Set<String> = emptySet(),
    val hits: Map<String, Int> = emptyMap(),
    /** Oldest first; bounded to the session's capacity. */
    val journal: List<JournalEntry> = emptyList(),
    val nextSeq: Long = 0
)

/**
 * Scenario activation, per-rule call counters, and the request journal for one test run.
 *
 * Deliberately an injected instance rather than an `object`: tests need a fresh one each
 * time (the native test binary is a single process, so a global would make results
 * order-dependent), and per-client isolation later means having more than one of these.
 *
 * Lives entirely in memory. Nothing here touches the rules YAML on disk, and every
 * mutation is visible to the very next `/intercept` call with no polling: a write
 * completes with a volatile store, and any thread's subsequent read observes it.
 *
 * Thread-safe by CAS over immutable snapshots. Kotlin/Native has no public stdlib mutex
 * (`kotlin.native.concurrent.Lock` is internal) and `kotlinx.coroutines.sync.Mutex` would
 * only work from coroutines, which the rule-watcher Worker is not.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class TestSession(
    private val journalCapacity: Int = DEFAULT_JOURNAL_CAPACITY,
    private val bodyLimit: Int = DEFAULT_BODY_LIMIT
) {
    private val ref = AtomicReference(TestState())

    /**
     * The single CAS loop. [block] may run more than once under contention, so it must be
     * pure and cheap -- build anything involving a clock read or base64 decoding outside.
     *
     * `compareAndSet` on Native compares by reference identity, which is exactly right
     * here because every update allocates a fresh [TestState]. That also means the loop
     * must re-read `ref.value` on each iteration and pass *that* instance as `expected`;
     * a structurally-equal rebuild would spin forever.
     */
    private inline fun mutate(block: (TestState) -> TestState): TestState {
        while (true) {
            val current = ref.value
            val next = block(current)
            if (ref.compareAndSet(current, next)) return next
        }
    }

    fun snapshot(): TestState = ref.value

    val activeScenarios: Set<String> get() = ref.value.activeScenarios

    /** Replaces the active set wholesale. */
    fun setScenarios(names: Set<String>): TestState = mutate { it.copy(activeScenarios = names) }

    fun activate(name: String): TestState = mutate { it.copy(activeScenarios = it.activeScenarios + name) }

    fun deactivate(name: String): TestState = mutate { it.copy(activeScenarios = it.activeScenarios - name) }

    /**
     * The 0-based index of *this* matching call for [ruleName], then increments.
     *
     * Note rule names are not unique across rule files -- `loadRulesFromDirectory` flat-maps
     * every `.yaml` in the directory -- so two files defining the same name share a counter.
     * Uniqueness is not enforced because that would break rule sets that work today.
     */
    fun nextHit(ruleName: String): Int {
        while (true) {
            val current = ref.value
            val hit = current.hits[ruleName] ?: 0
            val next = current.copy(hits = current.hits + (ruleName to hit + 1))
            if (ref.compareAndSet(current, next)) return hit
        }
    }

    /** Appends one request to the journal, evicting the oldest entry when at capacity. */
    fun record(envelope: RequestEnvelope, path: String, matchedRule: String?, status: Int?) {
        // Done before the loop: the loop body can re-run, and neither of these is cheap.
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val decoded = decodeBodyForJournal(envelope.body)
        val truncated = (decoded?.length ?: 0) > bodyLimit

        mutate { state ->
            val entry = JournalEntry(
                seq = state.nextSeq,
                timestamp = timestamp,
                method = envelope.method,
                url = envelope.url,
                path = path,
                headers = envelope.headers,
                body = decoded?.take(bodyLimit),
                bodyRaw = envelope.body?.take(bodyLimit),
                bodyTruncated = truncated,
                matchedRule = matchedRule,
                status = status
            )
            val appended = state.journal + entry
            state.copy(
                journal = if (appended.size > journalCapacity) appended.takeLast(journalCapacity) else appended,
                nextSeq = state.nextSeq + 1
            )
        }
    }

    /** Clears scenarios, counters and journal together, in one atomic store. */
    fun reset(): TestState = mutate { TestState() }
}

/**
 * Best-effort base64 decode of a request body, falling back to the raw string.
 *
 * Bodies arrive base64-encoded from all three clients, but a malformed or non-UTF-8
 * payload should degrade to something greppable rather than fail a request.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBodyForJournal(raw: String?): String? {
    if (raw == null) return null
    return try {
        Base64.decode(raw).decodeToString()
    } catch (_: Throwable) {
        raw
    }
}
