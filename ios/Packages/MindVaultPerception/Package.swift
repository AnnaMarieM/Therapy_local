// swift-tools-version: 6.0
import PackageDescription

// The diarization runtime for iOS/macOS: sherpa-onnx prebuilt xcframeworks, v2.0.0
// to match the Android AAR (app/build.gradle.kts pins sherpa-onnx-2.0.0.aar). Pinned
// by revision; that revision's own Package.swift pins the iOS xcframework URL +
// sha256 (95550cc9e2d9699eccbc41d6d7462fb83cdfc12dd070d937533f8d0383a38286,
// verified by download). See INTEGRATION.md "Diarization runtime".
//
// Apple-only, and conditioned out on Linux at the DEPENDENCY level: SwiftPM
// eagerly downloads every declared binary artifact at resolve time even when no
// target on that platform uses it, and the linux-core CI run crashed in
// FoundationNetworking downloading the Apple-only xcframeworks from GitHub's
// release CDN (PR #128 run 43787574700; reproduced locally on a cold cache).
// With the dependency absent on Linux there is no network on the Linux test
// path at all. macOS/iOS resolve exactly as before. (An `#if` inside the
// Package(...) argument list does not parse; conditional top-level arrays do.)
#if os(iOS) || os(macOS)
let sherpaDependency: [Package.Dependency] = [
    .package(
        url: "https://github.com/k2-fsa/sherpa-onnx",
        revision: "8abb7c8f87eae85b2e75f9a4115236c978980add"
    )
]
let sherpaProduct: [Product] = [
    // The diarization wrapper is its own product: the app links it by product name
    // (`import MindVaultPerceptionSherpa`), and a target without a product does not
    // appear in Xcode's product list at all (review blocker: the app build would
    // fail with "no such module" even with the target declared).
    .library(name: "MindVaultPerceptionSherpa", targets: ["MindVaultPerceptionSherpa"])
]
let sherpaTarget: [Target] = [
    // The ONNX runtime half: sherpa-onnx OfflineSpeakerDiarization behind the Diarizing
    // protocol. Apple-only; every line is compiled out elsewhere via canImport.
    .target(
        name: "MindVaultPerceptionSherpa",
        dependencies: [
            .target(name: "MindVaultPerception"),
            .product(
                name: "sherpa-onnx",
                package: "sherpa-onnx",
                condition: .when(platforms: [.iOS, .macOS])
            )
        ]
    )
]
#else
let sherpaDependency: [Package.Dependency] = []
let sherpaProduct: [Product] = []
let sherpaTarget: [Target] = []
#endif

let package = Package(
    name: "MindVaultPerception",
    platforms: [
        .iOS(.v18),
        .macOS(.v15)
    ],
    products: [
        .library(name: "MindVaultPerception", targets: ["MindVaultPerception"])
    ] + sherpaProduct,
    dependencies: [
        // TranscriptClean/TranscriptMerge for the chunk-path assembly (no duplication of the
        // overlap merge algorithm; CONTRACTS.md keeps them as shared contracts).
        .package(path: "../MindVaultCore")
    ] + sherpaDependency,
    targets: [
        // Pure logic: turns wire, turn planning, chunk audio, utterances, vector math,
        // model registry. Linux-testable; imports only Foundation (+ MindVaultCore for the
        // chunk-path merge).
        .target(
            name: "MindVaultPerception",
            dependencies: [
                .product(name: "MindVaultCore", package: "MindVaultCore")
            ]
        )
    ] + sherpaTarget + [
        .testTarget(
            name: "MindVaultPerceptionTests",
            dependencies: ["MindVaultPerception"]
        )
    ],
    swiftLanguageModes: [.v6]
)
