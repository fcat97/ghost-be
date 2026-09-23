package ghostbe.server

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ktor CIO dispatches handlers onto a multi-threaded dispatcher, so two `/intercept`
 * calls can genuinely run in parallel and hit one [TestSession] at once.
 *
 * These tests fail on a non-atomic read-modify-write and, crucially, would *not* fail
 * in a single-threaded test -- which is why they exist.
 */
@OptIn(ObsoleteWorkersApi::class)
class TestSessionConcurrencyTest {
    private val workers = 4
    private val perWorker = 1000

    @Test
    fun `concurrent nextHit hands out every index exactly once`() {
        val session = TestSession()
        val futures = (0 until workers).map {
            Worker.start().execute(TransferMode.SAFE, { session to perWorker }) { (s, n) ->
                (0 until n).map { s.nextHit("shared-rule") }
            }
        }
        val handedOut = futures.flatMap { it.result }.sorted()

        // No duplicates and no gaps: a lost update would shorten the set, a torn
        // read-modify-write would repeat an index.
        assertEquals((0 until workers * perWorker).toList(), handedOut)
        assertEquals(workers * perWorker, session.snapshot().hits["shared-rule"])
    }

    @Test
    fun `concurrent record never exceeds capacity and assigns distinct sequences`() {
        val session = TestSession(journalCapacity = 100)
        val futures = (0 until workers).map { worker ->
            Worker.start().execute(TransferMode.SAFE, { Triple(session, worker, 500) }) { (s, w, n) ->
                repeat(n) { s.record(RequestEnvelope("GET", "https://api.example.com/v1/$w", emptyMap(), null), "/v1/$w", null, null) }
            }
        }
        futures.forEach { it.result }

        val journal = session.snapshot().journal
        assertEquals(100, journal.size)
        assertEquals(journal.size, journal.map { it.seq }.distinct().size)
        assertEquals((workers * 500).toLong(), session.snapshot().nextSeq)
    }

    @Test
    fun `a reset racing concurrent records leaves the journal within capacity`() {
        val session = TestSession(journalCapacity = 50)
        val writers = (0 until workers).map {
            Worker.start().execute(TransferMode.SAFE, { session to 500 }) { (s, n) ->
                repeat(n) { s.record(RequestEnvelope("GET", "https://api.example.com/v1/x", emptyMap(), null), "/v1/x", null, null) }
            }
        }
        val resetter = Worker.start().execute(TransferMode.SAFE, { session }) { s ->
            repeat(50) { s.reset() }
        }
        writers.forEach { it.result }
        resetter.result

        assertTrue(session.snapshot().journal.size <= 50, "journal outgrew its capacity under contention")
    }
}
