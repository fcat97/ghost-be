package dev.yellobytes.ghostbe.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class RequestEnvelope(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null
)

fun RequestEnvelope.toJson(): String = json.encodeToString(this)

@Serializable
sealed interface ResponseEnvelope {
    @Serializable
    data class Mock(
        val intercept: Boolean = true,
        val status: Int,
        val headers: Map<String, String>,
        val body: String
    ) : ResponseEnvelope

    @Serializable
    data class Passthrough(val intercept: Boolean = false) : ResponseEnvelope

    companion object
}

fun ResponseEnvelope.Companion.fromJson(text: String): ResponseEnvelope {
    val element = json.parseToJsonElement(text).jsonObject
    val intercept = element["intercept"]?.jsonPrimitive?.boolean ?: false
    return if (intercept) {
        json.decodeFromJsonElement<ResponseEnvelope.Mock>(element)
    } else {
        ResponseEnvelope.Passthrough()
    }
}
