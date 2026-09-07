# GhostBe: iOS/Alamofire Interceptor — Design

Status: approved for planning
Date: 2026-09-07

## 1. Purpose

Extend GhostBe's request-mocking capability to iOS apps that use Alamofire,
mirroring what `client/`'s `GhostBeInterceptor` already does for Android/
OkHttp: ask `ghost-be` what to do with each request, and either return the
response it configures or let the request proceed to the real backend.

## 2. Why not `RequestInterceptor`

Alamofire's `RequestInterceptor` protocol (`adapt` + `retry`) cannot do what
this project needs. `adapt` runs before the request is sent and can only
return a (possibly modified) `URLRequest` or an error — there is no path
from there to "hand back a successful fake response instead of sending this
over the network." `retry` only runs after a real network round-trip has
already completed and failed; it controls whether the same request is
attempted again, never substitutes a response. Neither hook can reproduce
`GhostBeInterceptor`'s core behavior (fabricate a `Response`, skip the real
network entirely).

The actual mechanism for that on Apple platforms is `URLProtocol`
(`NSURLProtocol`), the same low-level technique tools like OHHTTPStubs/
Mocker use for request mocking. It is not Alamofire-specific — it operates
at the `URLSessionConfiguration` level — but it can be registered into an
Alamofire `Session`'s configuration, which is how this design connects the
two.

## 3. Why not a pure-Kotlin `NSURLProtocol` subclass

The natural next idea — implement the entire `URLProtocol` subclass in
Kotlin/Native, reusing `client/`'s existing logic directly, since
`NSURLProtocol` is a Foundation/Objective-C class and Kotlin/Native can
subclass Objective-C classes — does not work. `NSURLProtocol` requires
overriding two **class-side** methods (`canInitWithRequest:`,
`canonicalRequestForRequest:`), and Kotlin/Native cannot override
Objective-C class-side methods when subclassing: `companion object :
NSURLProtocol.Companion()` fails to compile with "cannot extend an object"
(`NSURLProtocol.Companion` is a plain singleton, not an open class).
Confirmed empirically against a throwaway spike module on `macos-latest`
CI (Apple targets cannot be compiled at all on a non-Apple host — this
could not be checked locally). Matches known, acknowledged Kotlin/Native
limitations around overriding Objective-C class-side methods from a Kotlin
subclass ([KT-23529] and related issues).

[KT-23529]: https://youtrack.jetbrains.com/issue/KT-23529

## 4. Architecture

`client/` becomes genuinely multiplatform: `android` (existing) plus
`iosArm64` + `iosSimulatorArm64` (new). The wire-protocol logic — building
the request envelope, JSON encode/decode, deciding mock-vs-passthrough —
moves from the Android-only `client/src/{Envelope,GhostBeInterceptor}.kt`
into common code. The one platform-specific piece, actually sending the
envelope to `ghost-be`'s `/intercept` endpoint and reading the response,
sits behind an `expect`/`actual`: Android keeps its existing OkHttp-based
call, iOS gets a new `NSURLSession`-based `actual` (ordinary instance-level
Foundation interop, not affected by the class-method limitation above).

A small Kotlin object exposes the resulting decision to Swift:

```kotlin
// client/src@ios/GhostBeCore.kt
object GhostBeCore {
    fun resolve(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Decision
}
sealed interface Decision {
    data class Mock(val status: Int, val headers: Map<String, String>, val body: ByteArray) : Decision
    object Passthrough : Decision
}
```

This is exported as an `.xcframework` (classic Objective-C interop
framework export — Kotlin's newer Swift export stays out of scope; it is
still experimental and this design already carries enough unverified
surface area).

The actual `NSURLProtocol` subclass is native Swift, in a new Swift
package at `ios/GhostBe/` (a `Package.swift` at the repo root, as SPM
requires for remote consumption, with its target path pointing into
`ios/GhostBe/Sources/`):

```swift
// ios/GhostBe/Sources/GhostBe/GhostBeURLProtocol.swift
final class GhostBeURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let decision = GhostBeCore.shared.resolve(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "",
            headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody
        )
        switch decision {
        case let mock as Decision.Mock:
            let response = HTTPURLResponse(
                url: request.url!, statusCode: Int(mock.status),
                httpVersion: "HTTP/1.1", headerFields: mock.headers
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: mock.body)
            client?.urlProtocolDidFinishLoading(self)
        default:
            // Passthrough: let the real network handle it. URLProtocol has no
            // built-in "proceed normally" -- this needs its own real network
            // request whose result is relayed back through `client?`, mirroring
            // what OkHttp's chain.proceed() does for free. See section 6.
        }
    }

    override func stopLoading() {}
}
```

`GhostBe.session(baseURL:)` is the public API — a factory returning an
Alamofire `Session` with `GhostBeURLProtocol` pre-registered in its
configuration:

```swift
public enum GhostBe {
    public static func session(baseURL: String = "http://127.0.0.1:8787") -> Session {
        GhostBeCore.shared.configure(baseURL: baseURL)
        let configuration = URLSessionConfiguration.af.default
        configuration.protocolClasses = [GhostBeURLProtocol.self] + (configuration.protocolClasses ?? [])
        return Session(configuration: configuration)
    }
}
```

No `RequestInterceptor` conformance is offered — per section 2, it cannot
do anything useful here, and offering one that's a no-op would be
misleading.

## 5. Data flow

Identical in shape to Android: app issues a request through the
`GhostBe.session(...)`-provided `Session` → `GhostBeURLProtocol.startLoading()`
calls `GhostBeCore.resolve(...)` → that POSTs the envelope to `ghost-be`'s
`/intercept` endpoint via `NSURLSession` and decodes the JSON response →
`.Mock` synthesizes an `HTTPURLResponse` + data and finishes loading
without the request ever reaching the real network; `.Passthrough` (or
`ghost-be` unreachable) lets the real request proceed.

## 6. Open design gap: passthrough needs a real network request

Unlike OkHttp's `chain.proceed(request)` (which the interceptor gets "for
free" from the chain), `URLProtocol` has no equivalent single call — on
passthrough, this protocol must issue its own real `NSURLSession` request
for the original URL and relay every callback (data, response, completion,
error) back through `client?`. This is standard practice for
`URLProtocol`-based mocking libraries but adds real implementation surface
(redirect handling, streaming bodies, cancellation) not present in the
OkHttp version. The implementation plan must size this properly rather
than treating it as a one-line delegate call.

## 7. Error handling

Same posture as Android: `ghost-be` unreachable is treated identically to
"no rule matched" — passthrough via the mechanism in section 6, never a
hard failure surfaced to the app.

## 8. Distribution

CI (`macos-latest`) builds the `.xcframework` from the iOS-extended
`client/` module and attaches it to GitHub Releases, the same pattern
already used for the Linux/Windows/macOS `ghost-be` binaries and the
Android AAR. `Package.swift` at the repo root declares a `.binaryTarget`
pointing at that release asset (URL + checksum) plus a regular target for
`ios/GhostBe/Sources/` (the Swift shell). Consumers add it via Xcode's
"Add Package Dependency" pointing at this repo; they never need the Kotlin
Toolchain installed.

## 9. Testing

Mirrors the `server-macos` validation pattern established earlier in this
project: a `workflow_dispatch`-only `ios-check.yml` on `macos-latest`
builds the `.xcframework` and runs both the shared Kotlin tests
(`iosSimulatorArm64`, executable in CI via the simulator) and a Swift test
target exercising `GhostBeURLProtocol` end-to-end against a stub `ghost-be`
(mock/status endpoint), before this is ever wired into the real
`release.yml`. Nothing here can be verified on a non-Apple host, matching
the constraint already documented for `server-macos`.

## 10. Non-goals

- No `RequestInterceptor` conformance (section 4).
- No Kotlin Swift export (section 4) — classic Objective-C framework
  export only, for now.
- No CocoaPods distribution — SPM only.
- Minimum deployment target iOS 13 (a common modern Alamofire/SPM
  baseline; `URLProtocol`'s callback API has been stable since long
  before this, so the floor is a compatibility/support-surface choice,
  not a technical constraint).
- No macOS/watchOS/tvOS targets for this package — iOS only.

## 11. Open risks carried into implementation

- Kotlin/Native's `NSURLSession`-based `actual` networking call for the
  shared core is unverified — everything in this design that touches
  Apple APIs can only be validated on `macos-latest` CI, the same
  constraint (and the same slower, CI-round-trip-driven debugging loop)
  encountered building `server-macos`.
- Section 6 (passthrough via a real relayed `NSURLSession` request) is the
  single largest unknown-complexity item in this design and should be its
  own early task in the implementation plan, not an afterthought.
