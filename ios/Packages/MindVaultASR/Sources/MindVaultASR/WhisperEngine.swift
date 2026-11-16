#if canImport(Metal)
import Metal
#endif
import CWhisper
import Foundation

/// Timings of the last run, the four numbers the JNI `nativeTimings` returns:
/// `encode_ms`, `decode_ms` (decode + batched decode), `prompt_ms`,
/// `sample_ms`. C Whisper reports `float`; everything here is `Double` in the
/// Swift API.
public struct WhisperTimings: Equatable, Sendable {
    public let encodeMs: Double
    public let decodeMs: Double
    public let promptMs: Double
    public let sampleMs: Double

    public init(encodeMs: Double, decodeMs: Double, promptMs: Double, sampleMs: Double) {
        self.encodeMs = encodeMs
        self.decodeMs = decodeMs
        self.promptMs = promptMs
        self.sampleMs = sampleMs
    }
}

/// One whisper segment with its place in the audio. whisper.cpp times in
/// centiseconds (10 ms units); the app wants milliseconds, so `startMs`/`endMs`
/// are `t * 10`.
public struct WhisperSegment: Equatable, Sendable {
    public let startMs: Int64
    public let endMs: Int64
    public let text: String

    public init(startMs: Int64, endMs: Int64, text: String) {
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
    }
}

/// What one `run` produced: the timed segments, the timings, and the join of
/// every segment text with a space — exactly the string the JNI
/// `nativeRun` returned, so the app can trim it and store it like
/// `WhisperRunner.transcribe` does.
public struct WhisperRunResult: Equatable, Sendable {
    public let segments: [WhisperSegment]
    public let timings: WhisperTimings
    /// Segment texts joined by a single space.
    public let text: String

    public init(segments: [WhisperSegment], timings: WhisperTimings) {
        self.segments = segments
        self.timings = timings
        self.text = segments.map(\.text).joined(separator: " ")
    }
}

public enum WhisperEngineError: Error, Equatable {
    /// The model file could not be loaded (the JNI `nativeInit` returned 0).
    case loadFailed(path: String)
    /// The engine was freed.
    case freed
    /// `whisper_full` failed on this audio.
    case runFailed
    /// `samples` is empty.
    case emptyAudio
}

/// whisper.cpp for one loaded model file, mirroring the JNI contract of
/// `tools/whisper-android/whisper_jni.cpp` and the semantics of
/// `engine/runner/WhisperRunner.kt`: one context, one call per turn, greedy or
/// beam decoding, segments joined, timings read back. The Android build pins
/// `use_gpu = false`; this package enables Metal on Apple instead (the encoder
/// and the KV cache run on the GPU, the decoder on the CPU — whisper.cpp's own
/// split), CPU only on Linux.
///
/// Not thread safe: like on Android, one call per turn from one caller. `run`
/// and `free` are guarded by a lock so an accidental second caller blocks
/// instead of corrupting the context.
public final class WhisperEngine: @unchecked Sendable {
    private let lock = NSLock()
    private var context: OpaquePointer?
    public let modelPath: String

    /// Metal on Apple, CPU elsewhere (Linux tests, CI). The explicit `useGPU`
    /// argument overrides it for measurements.
    public static var defaultUseGPU: Bool {
        #if canImport(Metal)
        true
        #else
        false
        #endif
    }

    /// Loads the model. Returns nil when whisper.cpp cannot load the file — the
    /// same "handle or 0" the JNI `nativeInit` gives, so the caller's ladder
    /// (load, self-test, free on failure) maps one to one.
    public init?(path: String, useGPU: Bool = WhisperEngine.defaultUseGPU) {
        self.modelPath = path
        var params = whisper_context_default_params()
        params.use_gpu = useGPU
        params.flash_attn = false
        let buf = UnsafeMutablePointer<CChar>.allocate(capacity: path.utf8CString.count + 1)
        defer { buf.deallocate() }
        path.utf8CString.withUnsafeBufferPointer { src in
            buf.initialize(from: src.baseAddress!, count: src.count)
        }
        guard let context = whisper_init_from_file_with_params(buf, params) else {
            return nil
        }
        self.context = context
    }

    /// The engine was freed (or never loaded). A freed engine is the JNI handle
    /// 0: `run` throws, `free` does nothing.
    public var isFreed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return context == nil
    }

    /// `whisper_print_system_info()`, what the JNI `nativeSystemInfo` returned
    /// and the self-test puts in the trace.
    public var systemInfo: String {
        String(cString: whisper_print_system_info()).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Transcribes one chunk: `WhisperRunner.transcribe` semantics. `language`
    /// nil or "" is auto-detect ("cs"/"en" pin the language), `translate` asks
    /// for English text whatever is spoken (the Whisper translate task),
    /// `beam` 0/1 is greedy and anything above 1 selects beam search of that
    /// width, `prompt` seeds the decoder, `threads` feeds `n_threads` (the
    /// Android runner uses 4).
    public func run(
        samples: [Float],
        language: String? = nil,
        translate: Bool = false,
        beam: Int = 1,
        threads: Int = 4,
        prompt: String? = nil
    ) throws -> WhisperRunResult {
        guard !samples.isEmpty else { throw WhisperEngineError.emptyAudio }
        lock.lock()
        defer { lock.unlock() }
        guard let context else { throw WhisperEngineError.freed }

        var params = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY)
        params.n_threads = Int32(max(1, threads))
        params.translate = translate
        params.detect_language = false
        params.print_progress = false
        params.print_realtime = false
        params.print_special = false
        params.print_timestamps = false
        params.no_context = true
        params.suppress_blank = false
        if beam > 1 { params.beam_search.beam_size = Int32(beam) }

        // The C strings must outlive the call: own allocations, freed after
        // whisper_full returns.
        func cString(_ s: String) -> UnsafeMutablePointer<CChar> {
            let buf = UnsafeMutablePointer<CChar>.allocate(capacity: s.utf8CString.count + 1)
            s.utf8CString.withUnsafeBufferPointer { src in
                buf.initialize(from: src.baseAddress!, count: src.count)
            }
            return buf
        }

        let languageC = cString(language?.isEmpty == false ? language! : "auto")
        params.language = UnsafePointer(languageC)
        var promptC: UnsafeMutablePointer<CChar>?
        if let prompt, !prompt.isEmpty {
            promptC = cString(prompt)
            params.initial_prompt = UnsafePointer(promptC!)
        }
        defer {
            params.language = nil
            params.initial_prompt = nil
            languageC.deallocate()
            promptC?.deallocate()
        }

        whisper_reset_timings(context)
        let ok = samples.withUnsafeBufferPointer { buf -> Bool in
            guard let base = buf.baseAddress else { return false }
            return whisper_full(context, params, base, Int32(buf.count)) == 0
        }
        guard ok else { throw WhisperEngineError.runFailed }

        var timings = WhisperTimings(encodeMs: 0, decodeMs: 0, promptMs: 0, sampleMs: 0)
        if let t = whisper_get_timings(context) {
            timings = WhisperTimings(
                encodeMs: Double(t.pointee.encode_ms),
                decodeMs: Double(t.pointee.decode_ms) + Double(t.pointee.batchd_ms),
                promptMs: Double(t.pointee.prompt_ms),
                sampleMs: Double(t.pointee.sample_ms)
            )
        }

        var segments: [WhisperSegment] = []
        let count = whisper_full_n_segments(context)
        if count > 0 {
            segments.reserveCapacity(Int(count))
            for i in 0..<count {
                guard let text = whisper_full_get_segment_text(context, i) else { continue }
                segments.append(
                    WhisperSegment(
                        startMs: whisper_full_get_segment_t0(context, i) * 10,
                        endMs: whisper_full_get_segment_t1(context, i) * 10,
                        text: String(cString: text)
                    )
                )
            }
        }
        return WhisperRunResult(segments: segments, timings: timings)
    }

    /// One second of silence, one greedy run, system info in the trace — the
    /// self-test of `WhisperRunner.open`, which proves the weights load and the
    /// graph runs before the model is accepted.
    public func selfTest(threads: Int = 4) throws -> WhisperRunResult {
        try run(
            samples: [Float](repeating: 0, count: Pcm.sampleRate),
            language: "en",
            translate: false,
            beam: 1,
            threads: threads,
            prompt: nil
        )
    }

    /// `nativeFree`: releases the context. Idempotent; deinit calls it too.
    public func free() {
        lock.lock()
        defer { lock.unlock() }
        guard let context else { return }
        self.context = nil
        whisper_free(context)
    }

    deinit {
        free()
    }
}
