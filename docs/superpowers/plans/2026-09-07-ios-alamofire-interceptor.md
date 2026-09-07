# iOS/Alamofire Interceptor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let iOS apps using Alamofire get the same request-mocking behavior Android apps already get from `client/`'s `GhostBeInterceptor`, distributed as a plain Swift Package.

**Architecture:** A hand-written native Swift Package (`ios/GhostBe/`, `Package.swift` at repo root) implementing the same wire protocol as the Android client — build a request envelope, POST it to `ghost-be`'s `/intercept` endpoint, decode the response, and either synthesize a mock response via a custom `URLProtocol` or let the request proceed for real. No Kotlin/KMP involvement (see spec §4 for why: Amper cannot produce an `.xcframework`, confirmed against both CI and Kotlin's own docs).

**Tech Stack:** Swift Package Manager, XCTest, GitHub Actions `macos-latest`.

**Spec:** [`docs/superpowers/specs/2026-09-07-ios-alamofire-interceptor-design.md`](../specs/2026-09-07-ios-alamofire-interceptor-design.md)

## Global Constraints

- No `RequestInterceptor` conformance — it cannot mock a response (spec §2).
- No shared Kotlin/KMP code, no `.xcframework`, no Kotlin Toolchain involvement at all (spec §4).
- No CocoaPods — SPM only (spec §10).
- Minimum deployment target iOS 13 (spec §10).
- This repo has no local Swift toolchain, and Apple-platform code has consistently needed CI-round-trip fixes throughout this project (confirmed for both macOS and iOS Kotlin work). `swift build`/`swift test` can only be validated via the `macos-latest` CI job introduced in Task 1 — budget for multiple iterations per task, not one clean pass.
- Existing git tags in this repo are `vX.Y.Z` (e.g. `v0.1.14`) — SPM supports both `X.Y.Z` and `vX.Y.Z` tag formats for version resolution, so no tagging-scheme change is needed for consumers to `.package(url:, from:)` this repo.

---

### Task 1: Scaffold the Swift package and get it building in CI

**Files:**
- Create: `Package.swift` (repo root)
- Create: `ios/GhostBe/Sources/GhostBe/Envelope.swift`
- Create: `ios/GhostBe/Sources/GhostBe/GhostBeURLProtocol.swift`
- Create: `ios/GhostBe/Sources/GhostBe/GhostBe.swift`
- Create: `.github/workflows/ios-check.yml`

**Interfaces:**
- Produces: `GhostBe.session(baseURL:) -> Session` (Task 2's tests call this), `.github/workflows/ios-check.yml` (Task 2 extends it with `swift test`).

- [x] **Step 1: Write `Package.swift`**

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "GhostBe",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "GhostBe", targets: ["GhostBe"])
    ],
    dependencies: [
        .package(url: "https://github.com/Alamofire/Alamofire.git", from: "5.9.0")
    ],
    targets: [
        .target(
            name: "GhostBe",
            dependencies: [
                .product(name: "Alamofire", package: "Alamofire")
            ],
            path: "ios/GhostBe/Sources/GhostBe"
        )
    ]
)
```

- [x] **Step 2: Write `Envelope.swift`** (verbatim from spec §4)

```swift
// ios/GhostBe/Sources/GhostBe/Envelope.swift
import Foundation

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

- [x] **Step 3: Write `GhostBeURLProtocol.swift`** (verbatim from spec §4)

```swift
// ios/GhostBe/Sources/GhostBe/GhostBeURLProtocol.swift
import Foundation

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

- [x] **Step 4: Write `GhostBe.swift`** (verbatim from spec §4)

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

- [x] **Step 5: Write `ios-check.yml`**

```yaml
# .github/workflows/ios-check.yml
name: iOS Check

on:
  workflow_dispatch:

jobs:
  build-and-test:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build the Swift package
        run: swift build --package-path .
```

- [x] **Step 6: Commit, push, trigger (`gh workflow run "iOS Check" --ref master`), watch, fix compile errors, repeat until green**

Expect real first-attempt errors here — none of `GhostBeURLProtocol`'s `URLProtocolClient` callback usage, `HTTPURLResponse` construction, or Alamofire's `URLSessionConfiguration.af.default` extension have been compiled anywhere before. Fix whatever the actual `swift build` error says; do not guess more than twice at the same error before reading Apple's actual `URLProtocol`/`URLSessionConfiguration` API documentation directly rather than continuing to guess blind.

Expected end state: `iOS Check` run is green (build only — no tests yet, that's Task 2).

---

### Task 2: Swift tests for `GhostBeURLProtocol` against a stub `ghost-be`

**Files:**
- Create: `ios/GhostBe/Tests/GhostBeTests/GhostBeURLProtocolTests.swift`
- Modify: `Package.swift` (add the test target)
- Modify: `.github/workflows/ios-check.yml` (add `swift test`)

**Interfaces:**
- Consumes: `GhostBe.session(baseURL:)` from Task 1.

- [x] **Step 1: Add the test target to `Package.swift`**

```swift
    targets: [
        // ...(existing GhostBe target from Task 1)...
        .testTarget(
            name: "GhostBeTests",
            dependencies: ["GhostBe"],
            path: "ios/GhostBe/Tests/GhostBeTests"
        )
    ]
```

- [x] **Step 2: Write the failing test**

Launches a real `ghost-be`-shaped stub via a `python3` subprocess (preinstalled on `macos-latest`) serving one canned `/intercept` response, then asserts `GhostBe.session(...)` returns that mocked response instead of hitting anything real:

```swift
// ios/GhostBe/Tests/GhostBeTests/GhostBeURLProtocolTests.swift
import XCTest
import Alamofire
@testable import GhostBe

final class GhostBeURLProtocolTests: XCTestCase {
    private var stubProcesses: [Process] = []

    /// Launches a `python3 -m http.server`-style stub on `port` that answers every
    /// request (regardless of path) with the given raw JSON body, and blocks until
    /// it's actually accepting connections (rather than a fixed sleep) by polling
    /// with a real HTTP request.
    private func launchStub(port: Int, responseJSON: String) throws {
        let script = """
        import http.server

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = '''\(responseJSON)'''.encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                self.do_POST()

        http.server.HTTPServer(("127.0.0.1", \(port)), Handler).serve_forever()
        """
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = ["-c", script]
        try process.run()
        stubProcesses.append(process)

        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if let data = try? Data(contentsOf: URL(string: "http://127.0.0.1:\(port)/ping")!), !data.isEmpty {
                return
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTFail("stub on port \(port) never started responding")
    }

    override func tearDownWithError() throws {
        stubProcesses.forEach { $0.terminate() }
        stubProcesses = []
    }

    func testReturnsTheMockedResponseInsteadOfCallingTheRealBackend() throws {
        try launchStub(
            port: 18787,
            responseJSON: #"{"intercept":true,"status":201,"headers":{"Content-Type":"application/json"},"body":"eyJvayI6dHJ1ZX0="}"#
        )

        let session = GhostBe.session(baseURL: "http://127.0.0.1:18787")
        let expectation = expectation(description: "response received")

        session.request("http://example.invalid/v1/users/42")
            .validate()
            .responseData { response in
                XCTAssertEqual(response.response?.statusCode, 201)
                XCTAssertEqual(response.data, Data(#"{"ok":true}"#.utf8))
                expectation.fulfill()
            }

        wait(for: [expectation], timeout: 5)
    }

    func testFallsThroughToTheRealRequestWhenGhostBeSaysPassthrough() throws {
        // Port 18788: stands in for ghost-be, always says "don't intercept".
        try launchStub(port: 18788, responseJSON: #"{"intercept":false}"#)
        // Port 18789: stands in for the real backend the app actually talks to.
        try launchStub(port: 18789, responseJSON: #"{"real":"backend"}"#)

        let session = GhostBe.session(baseURL: "http://127.0.0.1:18788")
        let expectation = expectation(description: "response received")

        session.request("http://127.0.0.1:18789/v1/users/42")
            .validate()
            .responseData { response in
                XCTAssertEqual(response.response?.statusCode, 200)
                XCTAssertEqual(response.data, Data(#"{"real":"backend"}"#.utf8))
                expectation.fulfill()
            }

        wait(for: [expectation], timeout: 5)
    }
}
```

- [x] **Step 3: Extend `ios-check.yml`**

```yaml
      - name: Run Swift tests
        run: swift test --package-path .
```

- [x] **Step 4: Trigger, watch, fix, repeat until green**

Run: `gh workflow run "iOS Check" -R fcat97/ghost-be --ref master` then `gh run watch <id> --exit-status`
Expected end state: both `swift build` and `swift test` steps green, covering both `testReturnsTheMockedResponseInsteadOfCallingTheRealBackend` and `testFallsThroughToTheRealRequestWhenGhostBeSaysPassthrough` from Step 2's code.

- [x] **Step 5: Commit**

```bash
git add Package.swift ios/GhostBe/Tests .github/workflows/ios-check.yml
git commit -m "test: add GhostBeURLProtocol XCTest suite against a stub ghost-be"
git push origin master
```

---

### Task 3: Documentation

No `release.yml` changes are needed — this is a source-based SPM package, and SPM resolves versions directly from this repo's existing `vX.Y.Z` git tags (Global Constraints), so any future tag already works as an installable version with zero extra release-asset machinery.

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`

- [x] **Step 1: Add an "iOS" subsection to README's "Using it in your app"**

Mirror the existing Android AAR instructions' structure, documenting:
```swift
.package(url: "https://github.com/fcat97/ghost-be", from: "0.1.14")
```
and
```swift
import GhostBe

let session = GhostBe.session() // defaults to http://127.0.0.1:8787
session.request("https://api.example.com/v1/users/42").responseDecodable(of: User.self) { ... }
```

- [x] **Step 2: Add an iOS variant to AGENTS.md's step 2**

("Integrate the client interceptor into the target app") mirroring the existing Android instructions, but for Xcode's "Add Package Dependency" + `GhostBe.session(...)` instead of the `.aar`/`libs/` flow.

- [x] **Step 3: Commit**

```bash
git add README.md AGENTS.md
git commit -m "docs: document the iOS/Alamofire SPM package"
git push origin master
```
