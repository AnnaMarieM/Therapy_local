import Foundation

/// One chunk of 16 kHz mono audio, as Android's `asr/AsrEngine.kt` `Pcm` object:
/// the recorder hands over PCM16 (`Int16` little endian), the engines want floats.
/// Pure logic, Linux-tested.
public enum Pcm {
    /// Samples per second, same as `ChunkConfig.SAMPLE_RATE` on Android.
    public static let sampleRate = 16_000

    /// Duration of a buffer in seconds.
    public static func seconds(_ pcm: [Int16]) -> Double {
        Double(pcm.count) / Double(sampleRate)
    }

    /// Root mean square level in [0, 1]. A level, not content, so it is safe to log.
    public static func rms(_ pcm: [Int16]) -> Double {
        guard !pcm.isEmpty else { return 0 }
        var sum = 0.0
        for s in pcm { sum += Double(s) * Double(s) }
        return (sum / Double(pcm.count)).squareRoot() / 32768.0
    }

    /// PCM16 to [-1, 1) floats, the widening whisper.cpp takes: every sample divided by 32768
    /// (the same widening as Android `Pcm.toFloats`, `pcm[it] / 32768f`).
    public static func toFloats(_ pcm: [Int16]) -> [Float] {
        pcm.map { Float($0) / 32768.0 }
    }

    /// Reads a 16 kHz mono PCM16 file body (raw samples, no WAV header) into floats.
    /// Little endian, matching what Android records and what `Pcm.toFloats` widens.
    public static func floatsFromLittleEndianData(_ data: Data) -> [Float] {
        var ints = [Int16](repeating: 0, count: data.count / 2)
        ints.withUnsafeMutableBytes { raw in
            data.copyBytes(to: raw.bindMemory(to: UInt8.self))
        }
        return toFloats(ints)
    }
}
