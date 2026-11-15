import Foundation

/// One saved chunk of a recording, the row the store keeps. [text] is the transcript of the chunk
/// once it has one. Ported from `com.therapylocal.app.data.ChunkRow`.
public struct ChunkRow: Hashable, Sendable {
    public var recordingId: Int64
    public var idx: Int
    public var startMs: Int64
    public var endMs: Int64
    public var overlapMs: Int64
    public var text: String?

    public init(recordingId: Int64, idx: Int, startMs: Int64, endMs: Int64, overlapMs: Int64, text: String?) {
        self.recordingId = recordingId
        self.idx = idx
        self.startMs = startMs
        self.endMs = endMs
        self.overlapMs = overlapMs
        self.text = text
    }
}

/// The PCM16 of one saved chunk: chunk [idx] covers [startMs, endMs) of the recording and shares
/// [overlapMs] with the chunk before it. Ported from `com.therapylocal.app.audio.AudioChunk`.
public struct AudioChunk: Sendable {
    public let idx: Int
    public let startMs: Int64
    public let endMs: Int64
    public let overlapMs: Int64
    public let pcm: [Int16]

    public init(idx: Int, startMs: Int64, endMs: Int64, overlapMs: Int64, pcm: [Int16]) {
        self.idx = idx
        self.startMs = startMs
        self.endMs = endMs
        self.overlapMs = overlapMs
        self.pcm = pcm
    }
}

/// The audio of one recording as a single timeline, read from its saved chunks. Consecutive chunks
/// share their overlap, so each chunk after the first contributes only the audio after its own
/// [ChunkRow.overlapMs]. Times are the chunks' own (milliseconds from the start of the recording),
/// the same as the turns use. Only the chunks a [slice] touches are loaded, one kept at a time, so
/// a long recording is never held whole unless the caller asks for all of it. Ported exactly from
/// `com.therapylocal.app.data.ChunkAudio`; [load] is the only I/O.
public final class ChunkAudio {
    private var cached: AudioChunk?
    private let sorted: [ChunkRow]
    private let firstIdx: Int?
    private let load: (Int) throws -> AudioChunk

    /// Where the recording ends.
    public let durationMs: Int64

    public init(rows: [ChunkRow], load: @escaping (Int) throws -> AudioChunk) {
        self.sorted = rows.sorted { $0.idx < $1.idx }
        self.firstIdx = self.sorted.first?.idx
        self.load = load
        self.durationMs = rows.map(\.endMs).max() ?? 0
    }

    /// 16 kHz PCM16 of [startMs, endMs); a stretch no chunk covers is silence.
    public func slice(startMs: Int64, endMs: Int64) throws -> [Int16] {
        let rate = Int64(PerceptionConfig.samplesPerMs)
        let from = max(startMs, 0)
        var out = [Int16](repeating: 0, count: Int((max(endMs, from) - from) * rate))
        for row in sorted {
            let own = row.idx == firstIdx ? row.startMs : row.startMs + row.overlapMs
            let a = max(from, own)
            let b = min(endMs, row.endMs)
            guard b > a else { continue }
            let chunk: AudioChunk
            if let cached, cached.idx == row.idx {
                chunk = cached
            } else {
                chunk = try load(row.idx)
                cached = chunk
            }
            let src = min(Int((a - row.startMs) * rate), chunk.pcm.count)
            let end = min(Int((b - row.startMs) * rate), chunk.pcm.count)
            guard end > src else { continue }
            let destination = Int((a - from) * rate)
            out.replaceSubrange(destination..<(destination + (end - src)), with: chunk.pcm[src..<end])
        }
        return out
    }
}
