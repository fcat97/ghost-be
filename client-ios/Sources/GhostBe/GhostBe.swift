import Alamofire
import Foundation

public enum GhostBe {
    public static func session(baseURL: String = "http://127.0.0.1:44678") -> Session {
        GhostBeURLProtocol.baseURL = baseURL
        let configuration = URLSessionConfiguration.af.default
        configuration.protocolClasses = [GhostBeURLProtocol.self] + (configuration.protocolClasses ?? [])
        return Session(configuration: configuration)
    }

    // Lets a QA tester point this session at a specific ghost-be instance by tapping a
    // deep link/universal link -- no adb, no LAN discovery, no code change. Hook this
    // into `application(_:open:options:)` (UIKit) or `scene(_:openURLContexts:)`
    // (SwiftUI/scene-based apps):
    //
    //   func application(_ app: UIApplication, open url: URL, options: ...) -> Bool {
    //       GhostBe.captureFromURL(url)
    //       return true
    //   }
    //
    // A link like `myapp://open?ghostBe=192.168.1.5:44678` then overrides
    // `session(baseURL:)`'s baseURL for the rest of this process's life -- there's
    // nothing to persist, since the app being killed is exactly when a QA session
    // naturally ends too.
    public static func captureFromURL(_ url: URL) {
        guard let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "ghostBe" })?.value,
            !value.isEmpty
        else { return }
        GhostBeURLProtocol.overrideBaseURL = "http://\(value)"
    }

    /// Falls back to whatever `baseURL` `session(baseURL:)` was called with.
    public static func clearCapturedServer() {
        GhostBeURLProtocol.overrideBaseURL = nil
    }
}
