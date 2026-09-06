package dev.yellobytes.ghostbe.client

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

@OptIn(ExperimentalEncodingApi::class)
class GhostBeInterceptor(
    baseUrl: String = "http://127.0.0.1:8787"
) : Interceptor {

    private val interceptUrl = baseUrl.trimEnd('/') + "/intercept"
    private val relayClient = OkHttpClient()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val envelope = buildEnvelope(originalRequest)

        val relayRequest = Request.Builder()
            .url(interceptUrl)
            .post(envelope.toJson().toRequestBody("application/json".toMediaType()))
            .build()

        val responseEnvelope: ResponseEnvelope = try {
            relayClient.newCall(relayRequest).execute().use { relayResponse ->
                ResponseEnvelope.fromJson(relayResponse.body!!.string())
            }
        } catch (e: IOException) {
            // ghost-be is not reachable (not running, wrong port, etc). Treat this
            // exactly like "no rule matched" and fall through to the real endpoint.
            ResponseEnvelope.Passthrough()
        }

        return when (responseEnvelope) {
            is ResponseEnvelope.Passthrough -> chain.proceed(originalRequest)
            is ResponseEnvelope.Mock -> buildResponse(originalRequest, responseEnvelope)
        }
    }

    private fun buildEnvelope(request: Request): RequestEnvelope {
        val headers = request.headers.toMultimap().mapValues { it.value.joinToString(",") }
        val bodyBase64 = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            Base64.encode(buffer.readByteArray())
        }
        return RequestEnvelope(
            method = request.method,
            url = request.url.toString(),
            headers = headers,
            body = bodyBase64
        )
    }

    private fun buildResponse(request: Request, mock: ResponseEnvelope.Mock): Response {
        val contentType = mock.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaType()

        val responseBuilder = Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(mock.status)
            .message(if (mock.status in 200..299) "OK" else "Error")
            .body(Base64.decode(mock.body).toResponseBody(contentType))

        mock.headers.forEach { (key, value) -> responseBuilder.addHeader(key, value) }

        return responseBuilder.build()
    }
}
