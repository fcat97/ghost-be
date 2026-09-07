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
