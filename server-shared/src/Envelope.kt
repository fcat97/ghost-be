package ghostbe.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class RequestEnvelope(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null
) {
    companion object {
        fun fromJson(text: String): RequestEnvelope = json.decodeFromString(text)
    }
}

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
}

fun ResponseEnvelope.toJson(): String = when (this) {
    is ResponseEnvelope.Mock -> json.encodeToString(this)
    is ResponseEnvelope.Passthrough -> json.encodeToString(this)
}
