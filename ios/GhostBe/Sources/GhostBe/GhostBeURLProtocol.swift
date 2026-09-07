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
