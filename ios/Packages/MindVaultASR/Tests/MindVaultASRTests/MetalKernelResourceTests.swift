import Testing
import Foundation
@testable import MindVaultASR

/// The Metal backend is built from the kernel *sources* this package ships as
/// resources: ggml compiles them on the device, at Metal device init, because
/// SwiftPM cannot run `xcrun metal` (upstream CMake builds `default.metallib`)
/// nor embed them as ASM (`GGML_METAL_EMBED_LIBRARY`). Everything below is the
/// part of that path that a Linux runner can prove: the sources are in the
/// bundle, in the layout `ggml_metal_library_init` walks, byte-identical to the
/// vendored pin, and cover every library kind the loader asks for. The Metal
/// compile itself, and hence GPU execution, needs Apple hardware — the device
/// smoke test on card #122 covers it.
@Suite("Metal kernels as SwiftPM resources")
struct MetalKernelResourceTests {

    /// The package root at test time: Tests/MindVaultASRTests/<this file>.
    private var packageRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // MindVaultASRTests
            .deletingLastPathComponent()   // Tests
            .deletingLastPathComponent()   // package root
    }

    private var vendoredMetal: URL {
        packageRoot.appendingPathComponent("Vendored/whisper/ggml/src/ggml-metal")
    }

    private func bundlePath() throws -> String {
        guard case let .ready(directory) = MetalKernels.layout() else {
            Issue.record("bundle layout is not ready: \(MetalKernels.layout())")
            throw CocoaError(.fileNoSuchFile)
        }
        return directory
    }

    /// Drift guard. SwiftPM cannot reference files outside a target directory,
    /// so the kernel sources exist twice: the vendored tree (the pin) and the
    /// Swift target's resources (what ships). An upstream bump that updates one
    /// and not the other fails here instead of on a device.
    @Test func bundledKernelsAreByteIdenticalToTheVendoredPin() throws {
        let fm = FileManager.default
        let bundle = try bundlePath()

        var compared = 0
        let vendoredKernels = vendoredMetal.appendingPathComponent("kernels")
        let names = try fm.contentsOfDirectory(atPath: vendoredKernels.path).sorted()
        #expect(!names.isEmpty)
        for name in names {
            let pin = vendoredKernels.appendingPathComponent(name)
            let shipped = URL(fileURLWithPath: bundle)
                .appendingPathComponent("kernels")
                .appendingPathComponent(name)
            guard let pinned = fm.contents(atPath: pin.path) else {
                Issue.record("vendored \(name) unreadable")
                continue
            }
            guard let bundled = fm.contents(atPath: shipped.path) else {
                Issue.record("bundled kernel \(name) missing")
                continue
            }
            #expect(pinned == bundled, "\(name) differs from the vendored pin")
            compared += 1
        }
        #expect(compared == names.count)

        // ggml-common.h sits one level above ggml-metal upstream; the loader
        // finds it at <dir>/ggml-common.h, so it ships at the bundle root.
        let headers: [(pin: URL, shipped: String)] = [
            (vendoredMetal.appendingPathComponent("ggml-metal-impl.h"), "ggml-metal-impl.h"),
            (packageRoot.appendingPathComponent("Vendored/whisper/ggml/src/ggml-common.h"), "ggml-common.h"),
        ]
        for header in headers {
            let bundled = URL(fileURLWithPath: bundle).appendingPathComponent(header.shipped)
            #expect(fm.contents(atPath: header.pin.path) == fm.contents(atPath: bundled.path),
                    "\(header.shipped) differs from the vendored pin")
        }
    }

    /// `ggml_metal_library_init` builds `<dir>/kernels/<name>.metal`, then
    /// flattens `#include "…"` against `<dir>/kernels`, `<dir>` and `<dir>/..`.
    /// So the bundle must hold the kernel sources *and* the two headers the
    /// kernels include (`ggml-metal-impl.h` via kernels/common.h, ggml-common.h
    /// via kernels/dequantize.h and quantize.h).
    @Test func bundleLayoutIsWhatTheGgmlLoaderWalks() throws {
        let bundle = try bundlePath()
        let fm = FileManager.default
        let kernels = (bundle as NSString).appendingPathComponent("kernels")
        #expect(fm.fileExists(atPath: kernels))
        for name in ["common.h", "dequantize.h", "quantize.h", "fa_common.metal", "fa_vec_common.metal"] {
            #expect(fm.fileExists(atPath: (kernels as NSString).appendingPathComponent(name)),
                    "kernels/\(name) missing")
        }
        #expect(fm.fileExists(atPath: (bundle as NSString).appendingPathComponent("ggml-metal-impl.h")))
        #expect(fm.fileExists(atPath: (bundle as NSString).appendingPathComponent("ggml-common.h")))
    }

    /// Every kind in the loader's `GGML_METAL_LIBS` table must have a source in
    /// the bundle. The table is read out of the vendored C, not restated here, so
    /// an upstream bump that adds a kernel is caught by this test as well as by
    /// the byte comparison.
    @Test func everyLoaderKindHasASource() throws {
        let bundle = try bundlePath()
        let device = try String(
            contentsOf: vendoredMetal.appendingPathComponent("ggml-metal-device.m"),
            encoding: .utf8
        )
        var kinds: [String] = []
        for line in device.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("X("), let close = trimmed.firstIndex(of: ")") else { continue }
            let body = trimmed[trimmed.index(trimmed.startIndex, offsetBy: 2)..<close]
            let parts = body.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count == 2 else { continue }
            kinds.append(parts[1])
        }
        #expect(kinds.count >= 30, "parsed \(kinds.count) kinds from ggml-metal-device.m")
        let fm = FileManager.default
        for kind in kinds {
            let path = (bundle as NSString).appendingPathComponent("kernels/\(kind).metal")
            #expect(fm.fileExists(atPath: path), "kernels/\(kind).metal missing (loader kind \(kind))")
        }
    }

    /// The documented set-once rule: an existing value is respected, an empty
    /// environment gets the bundle directory. This is what `WhisperEngine.init`
    /// relies on before the first whisper init.
    @Test func installRespectsAnExistingVariable() throws {
        let bundle = try bundlePath()
        #expect(MetalKernels.install(environment: [:]) == .installed(bundle))
        #expect(MetalKernels.install(environment: [MetalKernels.environmentVariable: "/tmp/custom"])
            == .keptExisting("/tmp/custom"))
    }

    /// Linux CI has the CPU backend only. This is the fail-loud path: a caller
    /// that asks for the GPU must get an error naming the devices, never a
    /// silent CPU decode.
    @Test func requestingGPUWithoutAMetalBackendFailsLoudly() throws {
        let diagnosis = WhisperEngine.gpuDiagnosis()
        #if canImport(Metal)
        // On Apple the Metal backend must be up: this is the assertion the
        // device smoke test (card #122) relies on. If the kernel resources are
        // missing from the app bundle this fails, which is the point.
        #expect(diagnosis.usable, "\(diagnosis.detail)")
        #expect(diagnosis.devices.contains { $0.contains("MTL") }, "\(diagnosis.devices)")
        #else
        #expect(diagnosis.usable == false)
        #expect(diagnosis.devices.contains { $0.contains("CPU") }, "\(diagnosis.devices)")

        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("mindvault-not-a-model-\(UUID().uuidString).bin")
        try Data("not a ggml model".utf8).write(to: scratch)
        defer { try? FileManager.default.removeItem(at: scratch) }
        do {
            _ = try WhisperEngine(path: scratch.path, useGPU: true)
            Issue.record("useGPU: true must throw .gpuUnavailable without a Metal backend")
        } catch let error as WhisperEngineError {
            guard case let .gpuUnavailable(systemInfo, detail) = error else {
                Issue.record("wrong error: \(error)")
                return
            }
            #expect(!systemInfo.isEmpty)
            #expect(detail.contains("no GPU device registered"), "\(detail)")
        }
        #endif
    }

    /// The CPU path is explicit and does not initialise the GPU (which on Apple
    /// would compile the Metal kernels). A missing model is `.loadFailed`, the
    /// JNI's handle-0.
    @Test func cpuPathReportsLoadFailureForAMissingModel() {
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("mindvault-absent-\(UUID().uuidString).bin")
        do {
            _ = try WhisperEngine(path: missing.path, useGPU: false)
            Issue.record("a missing model must throw .loadFailed")
        } catch let error as WhisperEngineError {
            #expect(error == .loadFailed(path: missing.path))
        } catch {
            Issue.record("wrong error: \(error)")
        }
    }
}
