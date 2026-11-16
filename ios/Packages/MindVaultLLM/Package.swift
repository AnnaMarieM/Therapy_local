// swift-tools-version: 6.0
import PackageDescription

// The llama.cpp-backed engine lives in its own target so that the heavy Apple-only
// dependency is separable from the pure logic (see INTEGRATION.md): the pure target
// `MindVaultLLM` must build and test on Linux (ios/KANBAN.md), where the pinned
// Apple XCFramework of llama.cpp cannot be linked. Package manifests are compiled
// for the host platform, so `os(Linux)` is the right discriminator: on a Linux host
// the manifest declares only the pure targets; on a macOS host (which is also what
// builds for iOS) the engine target and the pinned binary are declared too.
#if os(Linux)
let appleTargets: [Target] = []
let appleProducts: [Product] = []
#else
// llama.cpp, pinned to the exact revision bench/bench.py runs on CI
// (--llama-build b11960, commit 8642541b255bfb5dc39cd351a178be09013a55d6).
// The Apple XCFramework is the official ggml-org release archive; SwiftPM
// verifies the archive checksum at resolve time.
let appleTargets: [Target] = [
    .binaryTarget(
        name: "llama",
        url: "https://github.com/ggml-org/llama.cpp/releases/download/b11960/llama-b11960-xcframework.zip",
        checksum: "feba05e0b7cb5e682ae3354abb455bf9723ae2cc95c69e24d073264e6a34d759"
    ),
    .target(
        name: "MindVaultLLMLlama",
        dependencies: [
            "MindVaultLLM",
            .target(name: "llama"),
        ],
        path: "Sources/MindVaultLLMLlama"
    ),
]
let appleProducts: [Product] = [
    .library(name: "MindVaultLLMLlama", targets: ["MindVaultLLMLlama"]),
]
#endif

let package = Package(
    name: "MindVaultLLM",
    platforms: [
        .iOS(.v18),
        .macOS(.v15)
    ],
    products: [
        .library(name: "MindVaultLLM", targets: ["MindVaultLLM"])
    ] + appleProducts,
    targets: [
        .target(
            name: "MindVaultLLM",
            resources: [
                // .copy, not .process: .process flattens the cs/ subdirectory and the
                // Czech copies then collide with the English ones ("multiple resources
                // named 'extract.system.txt'"). Copying the folder as-is keeps
                // Resources/cs/, which is exactly the layout `PromptStore` reads by path.
                .copy("Resources")
            ]
        ),
    ] + appleTargets + [
        .testTarget(
            name: "MindVaultLLMTests",
            dependencies: ["MindVaultLLM"]
        )
    ],
    swiftLanguageModes: [.v6]
)
