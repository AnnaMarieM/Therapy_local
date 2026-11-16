// swift-tools-version: 6.0
import PackageDescription

// MindVaultASR: whisper.cpp (vendored, pinned) + the pure Swift logic around it.
//
// Vendored whisper.cpp is compiled as C targets mirroring the upstream CMake layout:
//   CGgmlBase    ggml core (tensor, alloc, backends, quants, threading)
//   CGgmlCpu     ggml CPU backend (arm64 paths; guarded code, no unsafeFlags)
//   CGgmlMetal   ggml Metal backend — Apple only, behind a platform-conditional
//                dependency, so Linux (`swift test`) never compiles it
//   CGgmlRegistry ggml backend registry (registers CPU always, Metal on Apple)
//   CWhisperCore whisper.cpp itself (the JNI's nativeRun counterpart)
//   CWhisper     public headers module (what Swift imports)
//
// The `CGgmlMetal` target is referenced only through
// `.when(platforms: [.macOS, .iOS])` conditions and belongs to no product, so
// Linux toolchains skip it entirely (verified on aarch64 Linux with Swift 6.3.3).
let package = Package(
    name: "MindVaultASR",
    platforms: [
        .iOS(.v18),
        .macOS(.v15)
    ],
    products: [
        .library(name: "MindVaultASR", targets: ["MindVaultASR"])
    ],
    targets: [
        // Public headers only. whisper.h includes ggml.h/ggml-cpu.h by name, so
        // the module map lives next to the ggml headers and pulls whisper.h in
        // from its upstream place one level up.
        .target(
            name: "CWhisper",
            path: "Vendored/whisper/ggml/include",
            publicHeadersPath: "."
        ),

        // ggml core. Sources are the upstream `ggml-base` set; the registry and
        // the backend directories are owned by their own targets below.
        .target(
            name: "CGgmlBase",
            dependencies: ["CWhisper"],
            path: "Vendored/whisper/ggml",
            sources: [
                "src/ggml.c", "src/ggml.cpp", "src/ggml-alloc.c", "src/ggml-backend.cpp",
                "src/ggml-backend-meta.cpp", "src/ggml-opt.cpp", "src/ggml-threading.cpp",
                "src/ggml-threading.h", "src/ggml-quants.c", "src/ggml-quants.h",
                "src/gguf.cpp", "src/ggml-impl.h", "src/ggml-backend-impl.h",
                "src/ggml-common.h", "src/ggml-feats.h",
            ],
            publicHeadersPath: ".",
            cSettings: [
                .define("GGML_SCHED_MAX_COPIES", to: "4"),
                .define("_GNU_SOURCE", .when(platforms: [.linux])),
                .define("_DARWIN_C_SOURCE", .when(platforms: [.macOS, .iOS])),
                .headerSearchPath("src"),
                .headerSearchPath("include"),
            ],
            linkerSettings: [
                .linkedLibrary("m", .when(platforms: [.linux]))
            ]
        ),

        // CPU backend, all platforms. The arm arch files are feature-guarded and
        // compile on any host; only generic arm64 codegen (no unsafeFlags allowed
        // in a dependency — see INTEGRATION.md).
        .target(
            name: "CGgmlCpu",
            dependencies: ["CWhisper", "CGgmlBase"],
            path: "Vendored/whisper/ggml/src/ggml-cpu",
            sources: [
                "ggml-cpu.c", "ggml-cpu.cpp", "ggml-cpu-impl.h", "common.h",
                "ops.cpp", "ops.h", "binary-ops.cpp", "binary-ops.h",
                "unary-ops.cpp", "unary-ops.h", "vec.cpp", "vec.h",
                "quants.c", "quants.h", "traits.cpp", "traits.h",
                "repack.cpp", "repack.h", "hbm.cpp", "hbm.h",
                "simd-mappings.h", "simd-gemm.h", "arch-fallback.h",
                "amx/amx.cpp", "amx/amx.h", "amx/mmq.cpp", "amx/mmq.h",
                "tiled/tiled.cpp", "tiled/tiled.h",
                "tiled/tiled-kernel.cpp", "tiled/tiled-kernel.h",
                "arch/arm/quants.c", "arch/arm/repack.cpp", "arch/arm/cpu-feats.cpp",
            ],
            publicHeadersPath: ".",
            cSettings: [
                .define("GGML_USE_CPU"),
                .define("_GNU_SOURCE", .when(platforms: [.linux])),
                .define("_DARWIN_C_SOURCE", .when(platforms: [.macOS, .iOS])),
                .headerSearchPath("."),
                .headerSearchPath(".."),
                .headerSearchPath("../../include"),
            ],
            linkerSettings: [
                .linkedFramework("Accelerate", .when(platforms: [.iOS, .macOS])),
            ]
        ),

        // Metal backend. Apple only; kept out of every product and referenced
        // only through platform-conditional dependencies.
        .target(
            name: "CGgmlMetal",
            dependencies: ["CWhisper", "CGgmlBase"],
            path: "Vendored/whisper/ggml/src/ggml-metal",
            sources: [
                "ggml-metal.cpp", "ggml-metal-common.cpp", "ggml-metal-device.cpp",
                "ggml-metal-fusion.cpp", "ggml-metal-ops.cpp", "ggml-metal-tuning.cpp",
                "ggml-metal-device.m", "ggml-metal-context.m",
                "ggml-metal-common.h", "ggml-metal-context.h", "ggml-metal-device.h",
                "ggml-metal-fusion.h", "ggml-metal-impl.h", "ggml-metal-ops.h", "ggml-metal-tuning.h",
            ],
            resources: [.copy("kernels")],
            publicHeadersPath: ".",
            cSettings: [
                .headerSearchPath("."),
                .headerSearchPath(".."),
                .headerSearchPath("../../include"),
            ],
            linkerSettings: [
                .linkedFramework("Metal", .when(platforms: [.iOS, .macOS])),
                .linkedFramework("Foundation", .when(platforms: [.iOS, .macOS])),
            ]
        ),

        // The backend registry: knows CPU everywhere and Metal on Apple, which
        // is what whisper.cpp's backend selection walks. (These three files live
        // in their own directory only because SwiftPM needs per-target dirs; the
        // contents are byte-identical to the pinned upstream files.)
        .target(
            name: "CGgmlRegistry",
            dependencies: [
                "CWhisper",
                "CGgmlBase",
                .target(name: "CGgmlCpu"),
                .target(name: "CGgmlMetal", condition: .when(platforms: [.macOS, .iOS])),
            ],
            path: "Vendored/whisper/ggml/src/registry",
            sources: ["ggml-backend-reg.cpp", "ggml-backend-dl.cpp", "ggml-backend-dl.h"],
            publicHeadersPath: ".",
            cSettings: [
                .define("GGML_USE_CPU"),
                .define("GGML_USE_METAL", .when(platforms: [.macOS, .iOS])),
                .define("_GNU_SOURCE", .when(platforms: [.linux])),
                .define("_DARWIN_C_SOURCE", .when(platforms: [.macOS, .iOS])),
                .headerSearchPath("."),
                .headerSearchPath(".."),
                .headerSearchPath("../../include"),
            ]
        ),

        // whisper.cpp. GGML_USE_METAL here only affects small device-selection
        // details inside whisper.cpp (e.g. the KV cache padding).
        .target(
            name: "CWhisperCore",
            dependencies: ["CWhisper", "CGgmlRegistry"],
            path: "Vendored/whisper",
            sources: [
                "src/whisper.cpp", "src/whisper-arch.h", "src/whisper-logging.h",
                "src/aneforge/whisper-aneforge.cpp", "src/aneforge/whisper-aneforge.h",
            ],
            publicHeadersPath: ".",
            cSettings: [
                .define("GGML_USE_METAL", .when(platforms: [.macOS, .iOS])),
                .define("WHISPER_VERSION", to: "\"1.9.5\""),
                .define("_GNU_SOURCE", .when(platforms: [.linux])),
                .define("_DARWIN_C_SOURCE", .when(platforms: [.macOS, .iOS])),
                .headerSearchPath("src"),
                .headerSearchPath("src/aneforge"),
                .headerSearchPath("include"),
                .headerSearchPath("ggml/include"),
            ]
        ),

        // The Swift API (mirrors the JNI contract) and the pure logic.
        .target(
            name: "MindVaultASR",
            dependencies: [
                "CWhisper",
                "CWhisperCore",
                .target(name: "CGgmlMetal", condition: .when(platforms: [.macOS, .iOS])),
            ],
            path: "Sources/MindVaultASR"
        ),

        .testTarget(
            name: "MindVaultASRTests",
            dependencies: ["MindVaultASR"],
            path: "Tests/MindVaultASRTests"
        ),
    ],
    swiftLanguageModes: [.v6]
)
