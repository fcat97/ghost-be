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
