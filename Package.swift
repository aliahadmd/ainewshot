// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AINewsHot",
    platforms: [.macOS("26.0")],
    targets: [
        .executableTarget(
            name: "AINewsHot",
            path: "Sources/AINewsHot",
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [.linkedFramework("Carbon")]
        )
    ]
)
