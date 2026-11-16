// swift-tools-version: 6.0
import PackageDescription

// MindVaultASR: whisper.cpp (vendored, pinned) + the pure Swift logic around it.
//
// Vendored whisper.cpp is compiled as C targets mirroring the upstream CMake layout:
//   CGgmlBase    ggml core (tensor, alloc, backends, quants, threading)
//   CGgmlCpu     ggml CPU backend (arch-selected: ARM kernels via our
//                arm-repack-shim.cpp, generic + arch-fallback.h elsewhere;
//                guarded code, no unsafeFlags)
//   CGgmlMetal   ggml Metal backend — Apple only, behind a platform-conditional
//                dependency, so Linux (`swift test`) never compiles it
//   CGgmlRegistry ggml backend registry (registers CPU always, Metal on Apple)
//   CWhisperCore whisper.cpp itself (the JNI's nativeRun counterpart)
//   CWhisper     public headers module (what Swift imports)
//
// The `CGgmlMetal` target is referenced only through
// `.when(platforms: [.macOS, .iOS])` conditions and belongs to no product, so
// Linux toolchains skip it entirely (verified on aarch64 Linux with Swift 6.3.3).
//
// Metal kernels. Upstream CMake either compiles `kernels/*.metal` with
// `xcrun metal` into `default.metallib` or embeds the sources as ASM
// (`GGML_METAL_EMBED_LIBRARY`); SwiftPM can do neither, so the Metal kernels
// ship as *sources* in the Swift target's resource bundle and
// `WhisperEngine` points the loader at them with `setenv(GGML_METAL_PATH_RESOURCES)`
// before the first whisper init. The bundle layout is the one
// `ggml_metal_library_init` walks:
//     <bundle>/kernels/<kind>.metal   (+ kernels/common.h, dequantize.h, …)
//     <bundle>/ggml-metal-impl.h      (from kernels/common.h)
//     <bundle>/ggml-common.h          (from kernels/dequantize.h, quantize.h)
// `kernels`, `ggml-metal-impl.h` and `ggml-common.h` at the target root are
// byte-identical copies of the vendored files (a test fails the build if they
// drift — SwiftPM cannot reference files outside a target's directory).
// `.copy("kernels")` keeps the directory name, so it lands at the bundle root.
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
                "arch/arm/quants.c", "arch/arm/cpu-feats.cpp",
                // The ARM repack kernels compile only on ARM, like upstream's
                // CMake does: arm-repack-shim.cpp (ours, not vendored) includes
                // arch/arm/repack.cpp under upstream's own arch condition.
                // Compiled unconditionally, that file's scalar #else branches
                // redefined all 27 repack kernels on x86_64 — on top of
                // arch-fallback.h's x86 renames inside repack.cpp — and the
                // linux-core link failed ("multiple definition"); see
                // arm-repack-shim.cpp and VENDORED.md.
                "arm-repack-shim.cpp",
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
        // only through platform-conditional dependencies. No `resources:` here:
        // a resource of a C target lands in `<Package>_CGgmlMetal.bundle`, and
        // the vendored loader cannot reach it — `SWIFTPM_MODULE_BUNDLE` is
        // defined below to the bundle the class was loaded from (the app
        // bundle) and `pathForResource:@"kernels/x"` does not resolve a path.
        // The kernel *sources* are resources of the Swift target instead — see
        // the header comment above.
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
            publicHeadersPath: ".",
            cSettings: [
                // SwiftPM defines SWIFT_PACKAGE for Clang targets as well
                // (`-DSWIFT_PACKAGE=1`, verified with swift build -v) but only
                // generates `resource_bundle_accessor.h` for Swift targets, so
                // the vendored `#ifdef SWIFT_PACKAGE → SWIFTPM_MODULE_BUNDLE`
                // spelling would not compile here. Spell it the way the
                // vendored file's "NSBundle Path Hack" intends: the bundle the
                // class was loaded from, which for a statically linked target is
                // the app bundle — also where an app-supplied default.metallib
                // sits.
                .define("SWIFTPM_MODULE_BUNDLE", to: "[NSBundle bundleForClass:[GGMLMetalClass class]]", .when(platforms: [.macOS, .iOS])),
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
        // `kernels/` + `ggml-metal-impl.h` + `ggml-common.h` are the Metal kernel
        // sources (see the header comment); they are compiled by ggml at Metal
        // device init, on the device, not by SwiftPM.
        .target(
            name: "MindVaultASR",
            dependencies: [
                "CWhisper",
                "CWhisperCore",
                .target(name: "CGgmlMetal", condition: .when(platforms: [.macOS, .iOS])),
            ],
            path: "Sources/MindVaultASR",
            resources: [
                .copy("kernels"),
                .copy("ggml-metal-impl.h"),
                .copy("ggml-common.h"),
            ]
        ),

        .testTarget(
            name: "MindVaultASRTests",
            dependencies: ["MindVaultASR"],
            path: "Tests/MindVaultASRTests"
        ),
    ],
    swiftLanguageModes: [.v6]
)
