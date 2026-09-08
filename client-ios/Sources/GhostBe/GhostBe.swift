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
