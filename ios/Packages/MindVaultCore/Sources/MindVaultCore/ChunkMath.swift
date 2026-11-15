import Foundation

public struct ChunkRange: Identifiable, Codable, Hashable, Sendable {
    public var id: Int { index }
    public let index: Int
    public let startMs: Int
    public let endMs: Int
    public let overlapMs: Int

    public init(index: Int, startMs: Int, endMs: Int, overlapMs: Int) {
        self.index = index
        self.startMs = startMs
        self.endMs = endMs
        self.overlapMs = overlapMs
    }

    public var durationMs: Int {
        max(0, endMs - startMs)
    }
}

public enum ChunkMath {
    public static let sampleRate = 16_000
    public static let chunkSeconds = 20
    public static let overlapSeconds = 5
    public static let stepSeconds = chunkSeconds - overlapSeconds
    public static let minNewSeconds = 2
    public static let minRecordingSeconds = 1

    public static let chunkSamples = chunkSeconds * sampleRate
    public static let overlapSamples = overlapSeconds * sampleRate
    public static let stepSamples = stepSeconds * sampleRate
    public static let minNewSamples = minNewSeconds * sampleRate
    public static let minRecordingSamples = minRecordingSeconds * sampleRate

    public static let chunkMs = chunkSeconds * 1_000
    public static let overlapMs = overlapSeconds * 1_000
    public static let stepMs = stepSeconds * 1_000
    public static let minNewMs = minNewSeconds * 1_000
    public static let minRecordingMs = minRecordingSeconds * 1_000

    public static func startMs(index: Int) -> Int {
        max(0, index) * stepMs
    }

    public static func chunkCount(durationMs: Int) -> Int {
        let total = max(0, durationMs)
        guard total >= minRecordingMs else { return 0 }
        guard total > chunkMs else { return 1 }
        let fullChunks = 1 + (total - chunkMs) / stepMs
        let remainder = total - (chunkMs + (fullChunks - 1) * stepMs)
        return remainder >= minNewMs ? fullChunks + 1 : fullChunks
    }

    public static func chunkCount(duration: TimeInterval) -> Int {
        chunkCount(durationMs: Int((max(0, duration) * 1_000).rounded(.down)))
    }

    public static func ranges(durationMs: Int) -> [ChunkRange] {
        let total = max(0, durationMs)
        let count = chunkCount(durationMs: total)
        guard count > 0 else { return [] }
        var result: [ChunkRange] = []
        result.reserveCapacity(count)
        for index in 0..<count {
            let start = startMs(index: index)
            let isLast = index == count - 1
            if !isLast {
                result.append(ChunkRange(index: index, startMs: start, endMs: start + chunkMs, overlapMs: index == 0 ? 0 : overlapMs))
            } else if count == 1 {
                result.append(ChunkRange(index: index, startMs: 0, endMs: total, overlapMs: 0))
            } else {
                let end = start + overlapMs + (total - (chunkMs + (count - 2) * stepMs))
                result.append(ChunkRange(index: index, startMs: start, endMs: end, overlapMs: overlapMs))
            }
        }
        return result
    }

    public static func describe() -> String {
        "\(chunkSeconds) s chunks, \(overlapSeconds) s overlap"
    }
}
