package ghostbe.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Route.interceptRoute(currentRules: () -> List<Rule>, resolver: ResponseResolver) {
    post("/intercept") {
        val body = call.receiveText()
        val envelope = RequestEnvelope.fromJson(body)
        val requestLabel = "${envelope.method} ${envelope.url}"
        val rule = matchRule(envelope, currentRules())

        if (rule == null) {
            println("ghost-be: $requestLabel -> passthrough")
            call.respondText(ResponseEnvelope.Passthrough().toJson(), ContentType.Application.Json)
            return@post
        }

        val resolved = resolver.resolve(rule, envelope)
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
        call.respondText(responseEnvelope.toJson(), ContentType.Application.Json)
    }
}
