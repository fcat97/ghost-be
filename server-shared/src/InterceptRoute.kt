package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Route.interceptRoute(currentRules: () -> List<Rule>, resolver: ResponseResolver, session: TestSession) {
    post("/intercept") {
        val body = call.receiveText()
        val envelope = RequestEnvelope.fromJson(body)
        val requestLabel = "${envelope.method} ${envelope.url}"
        val (path, _) = parseUrl(envelope.url)

        // Read the session once, so a scenario switch arriving mid-request cannot change
        // the rule set out from under this call.
        val activeScenarios = session.snapshot().activeScenarios
        val rule = matchRule(envelope, activeRules(currentRules(), activeScenarios))

        if (rule == null) {
            println("ghost-be: $requestLabel -> passthrough")
            // Passthroughs are journalled too: "my app called something I have no rule
            // for" is a thing a test asserts on. No hit is counted -- no rule was consumed.
            session.record(envelope, path, matchedRule = null, status = null)
            TrafficBroadcaster.publish(TrafficEvent(envelope.method, envelope.url, ruleMatched = null, status = null))
            call.respondText(ResponseEnvelope.Passthrough().toJson(), ContentType.Application.Json)
            return@post
        }

        // The one and only counter mutation, on the one path where a call actually
        // consumed a response-sequence slot. Deliberately not inside matchRule (pure, and
        // called directly by tests) nor inside resolve (takes the index as input so it
        // stays side-effect-free). It advances even when resolution fails below: a hit is
        // "a matching call", and making it conditional would let sequence position depend
        // on whether a response file happened to exist.
        val hit = session.nextHit(rule.name)
        val resolved = resolver.resolve(rule.name, pickVariant(rule.responseVariants(), hit), envelope)

        val responseEnvelope: ResponseEnvelope = when (resolved) {
            is ResolvedResponse.Success -> {
                println("ghost-be: $requestLabel -> intercept (rule '${rule.name}') -> ${resolved.status}")
                ResponseEnvelope.Mock(
                    status = resolved.status,
                    headers = resolved.headers,
                    body = Base64.encode(resolved.bodyBytes)
                )
            }
            is ResolvedResponse.Failure -> {
                println("ghost-be: $requestLabel -> intercept (rule '${resolved.ruleName}' failed: ${resolved.message}) -> 500")
                ResponseEnvelope.Mock(
                    status = 500,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = Base64.encode(
                        """{"error":"ghost-be rule '${resolved.ruleName}' failed: ${resolved.message}"}""".encodeToByteArray()
                    )
                )
            }
        }
        val statusForEvent = (responseEnvelope as? ResponseEnvelope.Mock)?.status
        session.record(envelope, path, matchedRule = rule.name, status = statusForEvent)
        TrafficBroadcaster.publish(TrafficEvent(envelope.method, envelope.url, ruleMatched = rule.name, status = statusForEvent))
        call.respondText(responseEnvelope.toJson(), ContentType.Application.Json)
    }
}
