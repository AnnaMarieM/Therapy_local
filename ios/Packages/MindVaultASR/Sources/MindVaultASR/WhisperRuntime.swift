import CWhisper
import Foundation

#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// The ggml/whisper log, surfaced to the app instead of being written to stderr
/// where nobody reads it. Only WARN and ERROR lines are forwarded: INFO/DEBUG
/// are frequent during a decode, and nothing this package logs ever contains
/// audio, transcript text or prompt text — ggml's own lines are about devices,
/// kernels and tensors.
///
/// Process-wide state: `whisper_log_set` is a single global hook in whisper.cpp,
/// so the first `WhisperEngine` that opens installs it. The app sets
/// `WhisperLog.handler` once at start (before any engine exists) to route the
/// lines to `os_log` / its session log; the default writes to stderr.
public enum WhisperLog {
    /// Receives one line per WARN/ERROR event, already trimmed and prefixed.
    public static var handler: (@Sendable (String) -> Void)? {
        get { sink.get() }
        set { sink.set(newValue) }
    }

    /// Installs the whisper.cpp → Swift log bridge. Idempotent; call sites are
    /// every engine open, the first one does the work.
    public static func install() {
        sink.install()
    }

    /// Emits one line through the same handler (used for this package's own
    /// backend diagnostics, so they land next to ggml's).
    public static func note(_ line: String) {
        sink.emit("mindvaultasr: " + line)
    }

    private static let sink = Sink()

    private final class Sink: @unchecked Sendable {
        private let lock = NSLock()
        private var handler: (@Sendable (String) -> Void)?
        private var installed = false

        func get() -> (@Sendable (String) -> Void)? {
            lock.lock()
            defer { lock.unlock() }
            return handler
        }

        func set(_ newValue: (@Sendable (String) -> Void)?) {
            lock.lock()
            handler = newValue
            lock.unlock()
        }

        func install() {
            lock.lock()
            let already = installed
            installed = true
            lock.unlock()
            guard !already else { return }
            whisper_log_set({ level, text, userData in
                // Error is 4, warn is 3; info/debug below that.
                guard level.rawValue >= GGML_LOG_LEVEL_WARN.rawValue, let text else { return }
                let line = String(cString: text).trimmingCharacters(in: .whitespacesAndNewlines)
                guard !line.isEmpty else { return }
                Sink.emitOnCurrentThread(line)
            }, nil)
        }

        func emit(_ line: String) {
            let target: (String) -> Void
            lock.lock()
            let captured = handler
            lock.unlock()
            if let captured {
                target = captured
            } else {
                target = { line in
                    FileHandle.standardError.write(Data((line + "\n").utf8))
                }
            }
            target(line)
        }

        static func emitOnCurrentThread(_ line: String) {
            WhisperLog.sink.emit(line)
        }
    }
}

/// Where ggml's Metal backend finds its kernels at run time.
///
/// Upstream CMake compiles `ggml/src/ggml-metal/kernels/*.metal` into
/// `default.metallib` with `xcrun metal`, or embeds the sources as ASM when
/// `GGML_METAL_EMBED_LIBRARY` is set. SwiftPM can do neither (no `unsafeFlags`,
/// no build-time `xcrun`), so this package ships the kernel *sources* in the
/// Swift target's resource bundle and points the loader at them: the vendored
/// `ggml_metal_library_init` reads `GGML_METAL_PATH_RESOURCES` and compiles
/// `<dir>/kernels/<kind>.metal` on the device (its own from-source fallback,
/// the same one `whisper-cli` uses when no metallib is installed).
///
/// The variable is read once per process, when the Metal device initialises, so
/// it is process-wide and set-once; `install()` runs before the first whisper
/// init. A `default.metallib` shipped by the app bundle still wins: the loader
/// tries the bundle before it looks at the variable.
public enum MetalKernels {
    public static let environmentVariable = "GGML_METAL_PATH_RESOURCES"

    /// What the resource bundle holds, relative to the directory the loader walks.
    public enum Layout: Equatable, Sendable {
        /// `kernels/`, `ggml-metal-impl.h` and `ggml-common.h` are all present.
        case ready(directory: String)
        /// The bundle is there but files are missing; the argument names them.
        case incomplete(directory: String, missing: [String])
        /// No resource bundle at all (SwiftPM did not stage the resources).
        case missingBundle
    }

    /// What `install()` did, for the session log.
    public enum Install: Equatable, Sendable {
        /// A `GGML_METAL_PATH_RESOURCES` was already set; left alone.
        case keptExisting(String)
        /// Set to the bundle directory.
        case installed(String)
        /// Nothing to point at; the caller must treat Metal as unusable.
        case unusable(Layout)
        /// The platform has no `setenv` (neither Darwin nor Glibc).
        case unsupported
    }

    /// The files `ggml_metal_library_init` needs, checked in the bundle.
    static let requiredFiles = ["ggml-metal-impl.h", "ggml-common.h"]

    /// The bundle resource directory, without checking its contents.
    public static var bundleDirectory: String? {
        guard let url = Bundle.module.resourceURL else { return nil }
        return url.path
    }

    /// Checks the bundled kernel sources against the layout the loader walks.
    public static func layout() -> Layout {
        guard let directory = bundleDirectory else { return .missingBundle }
        let fm = FileManager.default
        var missing: [String] = []
        if !fm.fileExists(atPath: (directory as NSString).appendingPathComponent("kernels")) {
            missing.append("kernels")
        }
        for name in requiredFiles where !fm.fileExists(atPath: (directory as NSString).appendingPathComponent(name)) {
            missing.append(name)
        }
        return missing.isEmpty ? .ready(directory: directory) : .incomplete(directory: directory, missing: missing)
    }

    /// Makes `GGML_METAL_PATH_RESOURCES` point at the bundled kernels. Call it
    /// before the first whisper init in the process — ggml reads the variable
    /// when the Metal device is created, and never again.
    @discardableResult
    public static func install(environment: [String: String] = ProcessInfo.processInfo.environment) -> Install {
        if let existing = environment[environmentVariable], !existing.isEmpty {
            return .keptExisting(existing)
        }
        switch layout() {
        case let .ready(directory):
            #if canImport(Darwin) || canImport(Glibc)
            // 1: overwrite. The variable was unset (checked above), so this only
            // races another thread doing the same thing with the same value.
            setenv(environmentVariable, directory, 1)
            return .installed(directory)
            #else
            return .unsupported
            #endif
        case let other:
            return .unusable(other)
        }
    }
}

/// What the GPU backend is doing, established by *initialising* it — the
/// proven-backend rule of the Android `Accelerators` ladder: a backend counts
/// only when it starts, not when it is listed.
public struct WhisperGPUDiagnosis: Equatable, Sendable {
    /// `useGPU` on the open that produced this diagnosis.
    public let requested: Bool
    /// True only when a GPU/IGPU device initialised and was released again.
    public let usable: Bool
    /// Every device ggml has registered, e.g. `["MTL0 (gpu)", "CPU (cpu)"]`.
    public let devices: [String]
    /// Human-readable reason, for the log; never contains audio or text.
    public let detail: String

    public init(requested: Bool, usable: Bool, devices: [String], detail: String) {
        self.requested = requested
        self.usable = usable
        self.devices = devices
        self.detail = detail
    }
}

enum GPUDiagnostic {
    /// Enumerates ggml's device registry and proves the first GPU/IGPU device by
    /// initialising it. `ggml_backend_dev_init` on Apple runs the Metal kernel
    /// compile, so a failing metallib/source layout shows up here as a failure
    /// instead of silently decoding on the CPU. The backend is freed right away;
    /// the device keeps the compiled library for whisper's own init.
    static func probe(requested: Bool) -> WhisperGPUDiagnosis {
        var devices: [String] = []
        var gpu: ggml_backend_dev_t?
        let count = ggml_backend_dev_count()
        for index in 0..<count {
            guard let device = ggml_backend_dev_get(index) else { continue }
            let type = ggml_backend_dev_type(device)
            let name = ggml_backend_dev_name(device).map { String(cString: $0) } ?? "?"
            devices.append("\(name) (\(describe(type)))")
            if gpu == nil, type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU {
                gpu = device
            }
        }
        guard requested else {
            // The CPU path never initialises the GPU: opening a device compiles
            // the Metal kernels, which is exactly the work `useGPU: false` asks
            // us not to do.
            return WhisperGPUDiagnosis(
                requested: false,
                usable: false,
                devices: devices,
                detail: "CPU requested, GPU not initialised (devices: \(devices.joined(separator: ", ")))"
            )
        }
        guard let gpu else {
            return WhisperGPUDiagnosis(
                requested: requested,
                usable: false,
                devices: devices,
                detail: "no GPU device registered (devices: \(devices.joined(separator: ", ")))"
            )
        }
        let name = ggml_backend_dev_name(gpu).map { String(cString: $0) } ?? "?"
        guard let backend = ggml_backend_dev_init(gpu, nil) else {
            return WhisperGPUDiagnosis(
                requested: requested,
                usable: false,
                devices: devices,
                detail: "\(name) device is registered but its backend failed to initialise"
            )
        }
        ggml_backend_free(backend)
        return WhisperGPUDiagnosis(
            requested: requested,
            usable: true,
            devices: devices,
            detail: "\(name) backend initialised"
        )
    }

    private static func describe(_ type: ggml_backend_dev_type) -> String {
        switch type {
        case GGML_BACKEND_DEVICE_TYPE_CPU: return "cpu"
        case GGML_BACKEND_DEVICE_TYPE_GPU: return "gpu"
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return "igpu"
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "accel"
        case GGML_BACKEND_DEVICE_TYPE_META: return "meta"
        default: return "type \(type.rawValue)"
        }
    }
}
