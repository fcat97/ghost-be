package spike

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachedURLResponse
import platform.Foundation.NSURLProtocol
import platform.Foundation.NSURLProtocolClientProtocol
import platform.Foundation.NSURLRequest

@OptIn(ExperimentalForeignApi::class)
class GhostBeURLProtocol : NSURLProtocol {

    @Suppress("CONFLICTING_OVERLOADS")
    constructor(request: NSURLRequest, cachedResponse: NSCachedURLResponse?, client: NSURLProtocolClientProtocol?)
        : super(request, cachedResponse, client)

    companion object : NSURLProtocol.Companion() {
        override fun canInitWithRequest(request: NSURLRequest): Boolean = true
        override fun canonicalRequestForRequest(request: NSURLRequest): NSURLRequest = request
    }

    override fun startLoading() {
        // real implementation would ask ghost-be here
    }

    override fun stopLoading() {
    }
}
