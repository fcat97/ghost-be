package spike

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLProtocol
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse

@OptIn(ExperimentalForeignApi::class)
class GhostBeURLProtocol : NSURLProtocol {

    @Suppress("CONFLICTING_OVERLOADS")
    constructor(request: NSURLRequest, cachedResponse: NSURLResponse?, client: platform.Foundation.NSURLProtocolClientProtocol?)
        : super(request, cachedResponse, client)

    companion object : NSURLProtocolMeta() {
        override fun canInitWithRequest(request: NSURLRequest): Boolean = true
        override fun canonicalRequestForRequest(request: NSURLRequest): NSURLRequest = request
    }

    override fun startLoading() {
        // real implementation would ask ghost-be here
    }

    override fun stopLoading() {
    }
}
