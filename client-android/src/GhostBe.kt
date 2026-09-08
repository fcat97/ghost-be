package dev.yellowbytes.ghostbe.client

import android.content.Intent

/**
 * Lets a QA tester point [GhostBeInterceptor] at a specific ghost-be instance
 * by tapping a deep link/app link -- no adb, no LAN discovery, no code change.
 *
 * Hook [captureFromIntent] into whichever Activity receives the launch intent
 * (typically a splash/launcher Activity's `onCreate` and `onNewIntent`):
 *
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     GhostBe.captureFromIntent(intent)
 * }
 *
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     GhostBe.captureFromIntent(intent)
 * }
 * ```
 *
 * A link like `myapp://open?ghostBe=192.168.1.5:44678` then overrides every
 * [GhostBeInterceptor]'s configured `baseUrl` for the rest of this process's
 * life -- there's nothing to persist, since the app being killed is exactly
 * when a QA session naturally ends too.
 */
object GhostBe {
    private const val QUERY_PARAM = "ghostBe"

    @Volatile
    internal var overrideBaseUrl: String? = null
        private set

    fun captureFromIntent(intent: Intent?) {
        captureFromUriString(intent?.dataString)
    }

    // Split out from captureFromIntent so this parsing logic can be unit-tested on
    // plain JVM: android.content.Intent's methods throw "not mocked" outside
    // Robolectric, but a plain String has no such restriction.
    internal fun captureFromUriString(uriString: String?) {
        val value = uriString?.let { extractQueryParam(it, QUERY_PARAM) }?.trim()
        if (!value.isNullOrEmpty()) {
            overrideBaseUrl = "http://$value"
        }
    }

    private fun extractQueryParam(uriString: String, name: String): String? {
        val query = uriString.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.getOrNull(0) == name }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    /** Falls back to whatever `baseUrl` each [GhostBeInterceptor] was constructed with. */
    fun clearCapturedServer() {
        overrideBaseUrl = null
    }
}
