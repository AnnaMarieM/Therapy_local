// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MindVaultCore",
    platforms: [
        .iOS(.v18),
        .macOS(.v15)
    ],
    products: [
        .library(name: "MindVaultCore", targets: ["MindVaultCore"]),
        .library(name: "MindVaultModels", targets: ["MindVaultModels"]),
        .library(name: "MindVaultPersistence", targets: ["MindVaultPersistence"]),
        .library(name: "MindVaultDesign", targets: ["MindVaultDesign"]),
        .library(name: "MindVaultLogging", targets: ["MindVaultLogging"])
    ],
    targets: [
        .target(name: "MindVaultModels"),
        .target(name: "MindVaultDesign"),
        .target(name: "MindVaultLogging"),
        .target(name: "MindVaultPersistence", dependencies: ["MindVaultModels", "MindVaultLogging"]),
        .target(
            name: "MindVaultCore",
            dependencies: [
                "MindVaultModels",
                "MindVaultPersistence",
                "MindVaultDesign",
                "MindVaultLogging"
            ]
        ),
        .testTarget(name: "MindVaultModelsTests", dependencies: ["MindVaultModels"]),
        .testTarget(name: "MindVaultCoreTests", dependencies: ["MindVaultCore"]),
        .testTarget(name: "MindVaultPersistenceTests", dependencies: ["MindVaultPersistence", "MindVaultModels"]),
        .testTarget(name: "MindVaultDesignTests", dependencies: ["MindVaultDesign"]),
        .testTarget(name: "MindVaultLoggingTests", dependencies: ["MindVaultLogging"])
    ],
    swiftLanguageModes: [.v6]
)
