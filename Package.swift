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
        ),
        .testTarget(
            name: "GhostBeTests",
            dependencies: ["GhostBe"],
            path: "ios/GhostBe/Tests/GhostBeTests"
        )
    ]
)
