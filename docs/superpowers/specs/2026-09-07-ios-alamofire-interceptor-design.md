# GhostBe: iOS/Alamofire Interceptor — Design

Status: approved for planning
Date: 2026-09-07 (revised same day: pure-Swift approach, see §4)

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

## 4. Architecture: pure native Swift, no shared Kotlin core

An earlier revision of this design proposed sharing the wire-protocol logic
(envelope building, JSON encode/decode, mock-vs-passthrough decision) with
Android via a KMP-extended `client/` module, exported to Swift as an
`.xcframework`, with only the unavoidable `NSURLProtocol` boilerplate
written natively. That approach is not achievable with this project's
tooling: implementing it (plan Task 1) confirmed that Amper (the Kotlin
Toolchain, which this whole repo is built with) produces only a `.klib`
for `kmp/lib` modules targeting iOS — never a `.framework`/`.xcframework`.
Cross-checked against Kotlin's own documentation: every distribution
method for consuming a KMP library from Swift (direct Xcode integration,
SwiftPM export, CocoaPods) is explicitly a feature of the *Gradle* Kotlin
Multiplatform plugin, not documented as supported by Amper, which has its
own independent native-compilation pipeline entirely separate from Gradle
(confirmed separately: Android modules in this repo visibly go through a
real generated Gradle project; no native target — Linux, Windows, macOS,
iOS — ever does).

Rather than bolt on a second build tool (a hand-written Gradle project
solely to produce the `.xcframework`, alongside Amper for everything else)
to rescue the shared-Kotlin-core idea, this design instead drops it: the
iOS client is a **plain native Swift Package**, hand-written, with no
Kotlin/KMP involvement at all. This also matches where this project is
headed next — a planned Flutter client package has nothing to do with KMP
either, so there is no cross-platform-sharing payoff being given up by
keeping each platform's client independent.

The package (`ios/GhostBe/`, `Package.swift` at the repo root as SPM
requires) hand-implements the same wire protocol `client/`'s Android
`GhostBeInterceptor` already implements: build a request envelope, POST it
as JSON to `ghost-be`'s `/intercept` endpoint, decode the JSON response,
and either synthesize a mock response or let the request proceed for real.

```swift
// ios/GhostBe/Sources/GhostBe/Envelope.swift
struct RequestEnvelope: Encodable {
    let method: String
    let url: String
    let headers: [String: String]
    let body: String?
}

enum ResponseEnvelope: Decodable {
    case mock(status: Int, headers: [String: String], body: String)
    case passthrough

    private enum CodingKeys: String, CodingKey { case intercept, status, headers, body }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let intercept = try container.decodeIfPresent(Bool.self, forKey: .intercept) ?? false
        if intercept {
            self = .mock(
                status: try container.decode(Int.self, forKey: .status),
                headers: try container.decode([String: String].self, forKey: .headers),
                body: try container.decode(String.self, forKey: .body)
            )
        } else {
            self = .passthrough
        }
    }
}
```

```swift
// ios/GhostBe/Sources/GhostBe/GhostBeURLProtocol.swift
final class GhostBeURLProtocol: URLProtocol {
    private var task: URLSessionDataTask?
    static var baseURL = "http://127.0.0.1:8787"

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let client = client, let url = request.url else { return }

        var headers: [String: String] = [:]
        request.allHTTPHeaderFields?.forEach { headers[$0.key] = $0.value }
        let envelope = RequestEnvelope(
            method: request.httpMethod ?? "GET",
            url: url.absoluteString,
            headers: headers,
            body: request.httpBody?.base64EncodedString()
        )

        let baseURL = Self.baseURL.hasSuffix("/") ? String(Self.baseURL.dropLast()) : Self.baseURL
        var relayRequest = URLRequest(url: URL(string: baseURL + "/intercept")!)
        relayRequest.httpMethod = "POST"
        relayRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        relayRequest.httpBody = try? JSONEncoder().encode(envelope)

        let relaySession = URLSession(configuration: .ephemeral)
        relaySession.dataTask(with: relayRequest) { data, _, _ in
            let decision = data.flatMap { try? JSONDecoder().decode(ResponseEnvelope.self, from: $0) } ?? .passthrough
            switch decision {
            case let .mock(status, mockHeaders, body):
                let response = HTTPURLResponse(
                    url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: mockHeaders
                )!
                let bodyData = Data(base64Encoded: body) ?? Data()
                client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client.urlProtocol(self, didLoad: bodyData)
                client.urlProtocolDidFinishLoading(self)
            case .passthrough:
                self.performRealRequest(client: client)
            }
        }.resume()
    }

    private func performRealRequest(client: URLProtocolClient) {
        let realSession = URLSession(configuration: .default)
        task = realSession.dataTask(with: request) { data, response, error in
            if let error = error {
                client.urlProtocol(self, didFailWithError: error)
                return
            }
            if let response = response {
                client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            }
            if let data = data {
                client.urlProtocol(self, didLoad: data)
            }
            client.urlProtocolDidFinishLoading(self)
        }
        task?.resume()
    }

    override func stopLoading() {
        task?.cancel()
    }
}
```

`GhostBe.session(baseURL:)` is the public API — a factory returning an
Alamofire `Session` with `GhostBeURLProtocol` pre-registered in its
configuration:

```swift
// ios/GhostBe/Sources/GhostBe/GhostBe.swift
import Alamofire
import Foundation

public enum GhostBe {
    public static func session(baseURL: String = "http://127.0.0.1:8787") -> Session {
        GhostBeURLProtocol.baseURL = baseURL
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

App issues a request through the `GhostBe.session(...)`-provided `Session`
→ `GhostBeURLProtocol.startLoading()` builds the envelope and POSTs it to
`ghost-be`'s `/intercept` endpoint via a plain `URLSession` call → decodes
the JSON response → `.mock` synthesizes an `HTTPURLResponse` + data and
finishes loading without the request ever reaching the real network;
`.passthrough` (or `ghost-be` unreachable, which decodes to the same
`.passthrough` fallback per the `data.flatMap { ... } ?? .passthrough` in
§4) issues the real request itself and relays every callback back through
`client`.

## 6. Open design gap: passthrough needs a real network request

Unlike OkHttp's `chain.proceed(request)` (which the interceptor gets "for
free" from the chain), `URLProtocol` has no equivalent single call — on
passthrough, this protocol must issue its own real `URLSession` request
for the original URL and relay every callback (data, response, completion,
error) back through `client`. This is standard practice for
`URLProtocol`-based mocking libraries but adds real implementation surface
(redirect handling, streaming bodies, cancellation) not present in the
OkHttp version. The implementation plan must size this properly rather
than treating it as a one-line delegate call. (§4's code sketch already
implements a first pass at this — `performRealRequest` — but redirect and
streaming-body handling are not yet addressed there and should be treated
as real open items in the implementation plan, not assumed solved.)

## 7. Error handling

Same posture as Android: `ghost-be` unreachable is treated identically to
"no rule matched" — passthrough via the mechanism in section 6, never a
hard failure surfaced to the app.

## 8. Distribution

Plain source-based SPM package — no Kotlin Toolchain, no `.xcframework`,
no binary target, no release-asset/checksum machinery. `Package.swift` at
the repo root declares a single library target pointing at
`ios/GhostBe/Sources/GhostBe/` and an Alamofire dependency. Consumers add
it via Xcode's "Add Package Dependency" pointing at this repo (or a tagged
version of it); Xcode compiles it from source like any other Swift
package. This is simpler than the original xcframework plan in every way
except that there is now a second hand-maintained implementation of the
wire protocol (Android's Kotlin, iOS's Swift) to keep in sync by hand —
an accepted tradeoff given §4's finding.

## 9. Testing

A `workflow_dispatch`-only `ios-check.yml` on `macos-latest` runs
`swift build` and `swift test` — no Kotlin, no xcframework step. The test
target exercises `GhostBeURLProtocol` end-to-end against a stub `ghost-be`
(a `python3 -m http.server`-style stub launched as a subprocess, serving a
canned `/intercept` response) before this is ever wired into the real
`release.yml`. `swift build`/`swift test` need a real Apple host (Swift
itself has a Linux toolchain, but `URLProtocol`'s integration surface here
is Foundation/Darwin-specific enough, and this project has no local Swift
toolchain installed, that CI remains the only verification venue, matching
every other Apple-platform constraint already documented in this project).

## 10. Non-goals

- No `RequestInterceptor` conformance (section 4).
- No shared Kotlin/KMP code, no `.xcframework`, no Kotlin Toolchain
  involvement in the iOS client at all (section 4).
- No CocoaPods distribution — SPM only.
- Minimum deployment target iOS 13 (a common modern Alamofire/SPM
  baseline; `URLProtocol`'s callback API has been stable since long
  before this, so the floor is a compatibility/support-surface choice,
  not a technical constraint).
- No macOS/watchOS/tvOS targets for this package — iOS only.

## 11. Open risks carried into implementation

- Section 6 (passthrough via a real relayed `URLSession` request) is the
  single largest unknown-complexity item in this design and should be its
  own early task in the implementation plan, not an afterthought — the
  §4 code sketch is a first pass, not a verified-correct implementation.
- Nothing in this design has been compiled or run anywhere; the entire
  Swift package is unverified until `ios-check.yml` actually runs it on
  `macos-latest`.
