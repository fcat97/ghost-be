package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/**
 * The test-control surface an e2e flow drives ghost-be through.
 *
 * Designed to be hard to get wrong from a one-line HTTP call: every mutation returns the
 * full new state, so a flow never needs a follow-up GET, and teardown verbs are
 * idempotent so a re-run or a retry cannot fail on them.
 *
 * The one deliberate exception is `PUT /api/test/scenarios`, which rejects an unknown
 * scenario name instead of ignoring it. A silently-ignored typo in a flow file is the
 * likeliest way to end up with a green test that asserts nothing.
 *
 * Registered under `/api/`, alongside `/api/rules`, so it stays clear of the
 * `get("/{path...}")` static catch-all.
 */
fun Route.testApiRoute(
    session: TestSession,
    currentRules: () -> List<Rule>,
    journalCapacity: Int = DEFAULT_JOURNAL_CAPACITY
) {
    // Recomputed per call rather than captured, so it tracks rule hot-reloads.
    fun available() = scenarioNames(currentRules())

    suspend fun ApplicationCall.respondState(state: TestState) =
        respondText(
            json.encodeToString(testStateDto(state, available(), journalCapacity)),
            ContentType.Application.Json
        )

    get("/api/test/state") {
        call.respondState(session.snapshot())
    }

    put("/api/test/scenarios") {
        val body = call.receiveText()
        val request = try {
            json.decodeFromString<SetScenariosRequest>(body)
        } catch (e: Exception) {
            call.respondText(
                json.encodeToString(ErrorDto("malformed request body: ${e.message}")),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@put
        }

        // Validate before mutating, so a rejected request leaves state untouched.
        val known = available()
        val unknown = request.active.filterNot { it in known }
        if (unknown.isNotEmpty()) {
            call.respondText(
                json.encodeToString(ErrorDto("unknown scenario: ${unknown.joinToString(", ")}", known)),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@put
        }

        call.respondState(session.setScenarios(request.active.toSet()))
    }

    post("/api/test/scenarios/{name}") {
        val name = call.parameters["name"]!!
        val known = available()
        if (name !in known) {
            call.respondText(
                json.encodeToString(ErrorDto("unknown scenario: $name", known)),
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
            return@post
        }
        call.respondState(session.activate(name))
    }

    delete("/api/test/scenarios/{name}") {
        // Idempotent, and deliberately 200 even for an unknown name: teardown runs after
        // a rules edit may have removed the scenario, and it must never fail.
        call.respondState(session.deactivate(call.parameters["name"]!!))
    }

    get("/api/test/journal") {
        val filter = journalFilterFrom(call.request.queryParameters)
        val result = queryJournal(session.snapshot().journal, filter)
        call.respondText(
            json.encodeToString(JournalResponseDto(result.count, result.entries)),
            ContentType.Application.Json
        )
    }

    get("/api/test/journal/count") {
        // A bare integer rather than JSON: `parseInt(res.body, 10)` in a Maestro helper is
        // one line that cannot go subtly wrong.
        val filter = journalFilterFrom(call.request.queryParameters)
        call.respondText(queryJournal(session.snapshot().journal, filter).count.toString(), ContentType.Text.Plain)
    }

    post("/api/test/reset") {
        call.respondState(session.reset())
    }
}

/**
 * Builds a filter from query params. An unparseable or out-of-range `limit` is clamped
 * rather than rejected -- failing a whole assertion because a limit was mistyped would be
 * a worse outcome than showing a sane number of entries.
 */
internal fun journalFilterFrom(params: Parameters): JournalFilter {
    val requested = params["limit"]?.toIntOrNull() ?: DEFAULT_JOURNAL_QUERY_LIMIT
    return JournalFilter(
        method = params["method"],
        path = params["path"],
        bodyContains = params["bodyContains"],
        limit = requested.coerceIn(1, MAX_JOURNAL_QUERY_LIMIT)
    )
}
