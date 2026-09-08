package dev.yellowbytes.ghostbe.client

import android.util.Log
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
    private val baseUrl: String = "http://127.0.0.1:44678"
) : Interceptor {

    private val relayClient = OkHttpClient()

    private companion object {
        const val TAG = "GhostBe"
    }

    // android.util.Log is a stub under plain JVM unit tests (no Robolectric) and throws
    // "not mocked" -- logging is best-effort and never worth failing a request over.
    private fun logDebug(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val envelope = buildEnvelope(originalRequest)
        val requestLabel = "${originalRequest.method} ${originalRequest.url}"
        val requestPayload = envelope.toJson()
        logDebug("-> $requestLabel\nrequest payload: $requestPayload")

        // Re-read the override on every request rather than caching it at construction --
        // a deep link can arrive any time after the OkHttpClient (and this interceptor)
        // is already built.
        val effectiveBaseUrl = GhostBe.overrideBaseUrl ?: baseUrl
        val interceptUrl = effectiveBaseUrl.trimEnd('/') + "/intercept"
        val relayRequest = Request.Builder()
            .url(interceptUrl)
            .post(requestPayload.toRequestBody("application/json".toMediaType()))
            .build()

        val responsePayload = try {
            relayClient.newCall(relayRequest).execute().use { relayResponse -> relayResponse.body!!.string() }
        } catch (e: IOException) {
            logDebug("<- $requestLabel: ghost-be unreachable, passthrough")
            return chain.proceed(originalRequest)
        }
        val responseEnvelope = ResponseEnvelope.fromJson(responsePayload)

        logDebug("<- $requestLabel\nresponse payload: $responsePayload")
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
