package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Route.interceptRoute(rules: List<Rule>, resolver: ResponseResolver) {
    post("/intercept") {
        val body = call.receiveText()
        val envelope = RequestEnvelope.fromJson(body)
        val rule = matchRule(envelope, rules)

        if (rule == null) {
            call.respondText(ResponseEnvelope.Passthrough().toJson(), ContentType.Application.Json)
            return@post
        }

        val resolved = resolver.resolve(rule, envelope)
        val responseEnvelope: ResponseEnvelope = when (resolved) {
            is ResolvedResponse.Success -> ResponseEnvelope.Mock(
                status = resolved.status,
                headers = resolved.headers,
                body = Base64.encode(resolved.bodyBytes)
            )
            is ResolvedResponse.Failure -> ResponseEnvelope.Mock(
                status = 500,
                headers = mapOf("Content-Type" to "application/json"),
                body = Base64.encode(
                    """{"error":"ghost-be rule '${resolved.ruleName}' failed: ${resolved.message}"}""".encodeToByteArray()
                )
            )
        }
        call.respondText(responseEnvelope.toJson(), ContentType.Application.Json)
    }
}
