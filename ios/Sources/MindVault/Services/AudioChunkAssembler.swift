import Foundation
import MindVaultCore

/// One closed slice of a recording, in the exact timeline `ChunkMath` describes:
/// 20 s chunks, 5 s overlap, 15 s step, 16 kHz mono PCM16.
struct FinalizedAudioChunk: Equatable, Sendable {
    let index: Int
    let startMs: Int
    let endMs: Int
    let overlapMs: Int
    let pcm16: Data

    var durationMs: Int {
        max(0, endMs - startMs)
    }

    var sampleCount: Int {
        pcm16.count / 2
    }
}

/// Pure streaming implementation of the `ChunkMath` contract. It owns only the current
/// chunk plus the 5 s overlap tail; callers append converted 16 kHz mono PCM16 blocks and
/// receive chunks as soon as they close. No AVFoundation dependency, so it is unit-testable
/// on macOS and in the iOS test target.
///
/// Boundary rule inherited from the Android reference: a full 20 s chunk is emitted as soon
/// as it fills. On `finish`, trailing audio is kept only when it adds at least 2 s beyond the
/// 5 s overlap (or, for recordings shorter than one chunk, at least 1 s in total). A
/// recording just over 20 s (but under 22 s) therefore keeps the first 20 s chunk and
/// drops the short tail; `ChunkMath`'s static ranges describe the same chunk count but
/// extend the single chunk to the recording end in that narrow case.
struct AudioChunkAssembler: Sendable {
    private(set) var totalSamples = 0
    private(set) var nextIndex = 0
    private(set) var isFinished = false
    private(set) var droppedTrailingSamples = 0

    private var current = Data()

    var currentSampleCount: Int {
        current.count / 2
    }

    var elapsedMs: Int {
        totalSamples * 1_000 / ChunkMath.sampleRate
    }

    /// Append a block of little-endian PCM16 samples. A dangling odd byte is ignored because
    /// callers always hand over whole frames; it cannot be interpreted as a sample.
    mutating func append(pcm16: Data) -> [FinalizedAudioChunk] {
        guard !isFinished else { return [] }
        let alignedCount = pcm16.count - (pcm16.count % 2)
        guard alignedCount > 0 else { return [] }

        current.append(pcm16.prefix(alignedCount))
        totalSamples += alignedCount / 2

        var closed: [FinalizedAudioChunk] = []
        let chunkBytes = ChunkMath.chunkSamples * 2
        let dropBytes = (ChunkMath.chunkSamples - ChunkMath.overlapSamples) * 2
        while current.count >= chunkBytes {
            let startMs = nextIndex * ChunkMath.stepMs
            let pcm = Data(current.prefix(chunkBytes))
            closed.append(
                FinalizedAudioChunk(
                    index: nextIndex,
                    startMs: startMs,
                    endMs: startMs + ChunkMath.chunkMs,
                    overlapMs: nextIndex == 0 ? 0 : ChunkMath.overlapMs,
                    pcm16: pcm
                )
            )
            nextIndex += 1
            // Keep the 5 s overlap for the next chunk *and* any audio already received
            // beyond this chunk's end, so a single large tap block cannot lose samples.
            current = Data(current.dropFirst(dropBytes))
        }
        return closed
    }

    /// Close the recording. Returns the trailing chunk when it carries enough new audio.
    mutating func finish() -> FinalizedAudioChunk? {
        guard !isFinished else { return nil }
        isFinished = true
        defer { current = Data() }

        guard totalSamples >= ChunkMath.minRecordingSamples else {
            droppedTrailingSamples = totalSamples
            return nil
        }

        let availableSamples = current.count / 2
        if nextIndex == 0 {
            let endMs = totalSamples * 1_000 / ChunkMath.sampleRate
            guard endMs > 0 else {
                droppedTrailingSamples = totalSamples
                return nil
            }
            return FinalizedAudioChunk(
                index: 0,
                startMs: 0,
                endMs: endMs,
                overlapMs: 0,
                pcm16: current
            )
        }

        let newSamples = availableSamples - ChunkMath.overlapSamples
        guard newSamples >= ChunkMath.minNewSamples else {
            droppedTrailingSamples = max(0, newSamples)
            return nil
        }

        let startMs = nextIndex * ChunkMath.stepMs
        return FinalizedAudioChunk(
            index: nextIndex,
            startMs: startMs,
            endMs: startMs + availableSamples * 1_000 / ChunkMath.sampleRate,
            overlapMs: ChunkMath.overlapMs,
            pcm16: current
        )
    }
}

enum PCM16 {
    /// Little-endian PCM16 bytes for a signed sample value.
    static func data(from samples: [Int16]) -> Data {
        var data = Data(capacity: samples.count * 2)
        for sample in samples {
            var value = sample.littleEndian
            withUnsafeBytes(of: &value) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// Number of whole samples in a block of PCM16 bytes.
    static func sampleCount(of data: Data) -> Int {
        data.count / 2
    }

    /// Root mean square in 0...1, matching the Android `Pcm.rms` meter.
    static func rms(of data: Data) -> Float {
        let count = sampleCount(of: data)
        guard count > 0 else { return 0 }
        var sum: Double = 0
        data.withUnsafeBytes { raw in
            for index in 0..<count {
                let value = raw.loadUnaligned(fromByteOffset: index * 2, as: Int16.self)
                let normalized = Double(Int16(littleEndian: value)) / 32_768.0
                sum += normalized * normalized
            }
        }
        return Float((sum / Double(count)).squareRoot())
    }
}
