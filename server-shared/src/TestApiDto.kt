package ghostbe.server

import kotlinx.serialization.Serializable

/**
 * Wire types for the `/api/test` endpoints.
 *
 * Kept separate from [TestState] and friends so the shape a Maestro flow depends on is a
 * deliberate artifact, rather than something that changes because a domain field was
 * renamed. [JournalEntry] is the one type shared between the two, which is fine -- it
 * *is* the contract.
 */

@Serializable
data class ScenariosDto(val active: List<String>, val available: List<String>)

@Serializable
data class JournalSummaryDto(val size: Int, val capacity: Int)

/** Returned by every mutation, so a flow can set state and assert on the result in one call. */
@Serializable
data class TestStateDto(
    val scenarios: ScenariosDto,
    val hits: Map<String, Int>,
    val journal: JournalSummaryDto
)

@Serializable
data class JournalResponseDto(val count: Int, val entries: List<JournalEntry>)

@Serializable
data class SetScenariosRequest(val active: List<String> = emptyList())

@Serializable
data class ErrorDto(val error: String, val available: List<String> = emptyList())

internal fun testStateDto(state: TestState, available: List<String>, capacity: Int) = TestStateDto(
    // Active names are sorted so a flow asserting on this gets a stable order; the
    // underlying Set has none.
    scenarios = ScenariosDto(active = state.activeScenarios.sorted(), available = available),
    hits = state.hits,
    journal = JournalSummaryDto(size = state.journal.size, capacity = capacity)
)
