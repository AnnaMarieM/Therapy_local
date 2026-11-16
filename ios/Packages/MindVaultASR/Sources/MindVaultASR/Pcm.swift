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
    ///
    /// Little endian is decoded explicitly, byte by byte, not by reinterpreting
    /// the buffer in host byte order: Android records and reads PCM16 little
    /// endian (`ByteBuffer.order(LITTLE_ENDIAN)`), so the same bytes must widen
    /// to the same floats on any host. A trailing odd byte is ignored — it is
    /// not a sample.
    public static func floatsFromLittleEndianData(_ data: Data) -> [Float] {
        let count = data.count / 2
        guard count > 0 else { return [] }
        let bytes = [UInt8](data)
        var ints = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let lo = UInt16(bytes[2 * i])
            let hi = UInt16(bytes[2 * i + 1])
            ints[i] = Int16(bitPattern: lo | (hi << 8))
        }
        return toFloats(ints)
    }
}
