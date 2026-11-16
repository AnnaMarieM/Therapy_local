import Foundation

/// One utterance. [speaker] is the raw diarization label ("S0", "S1", ...); nil when the recording
/// was not diarized. Ported from `com.therapylocal.app.data.SegmentRow`.
public struct SegmentRow: Hashable, Sendable {
    public var idx: Int
    public var startMs: Int64
    public var endMs: Int64
    public var text: String
    public var speaker: String?

    public init(idx: Int, startMs: Int64, endMs: Int64, text: String, speaker: String? = nil) {
        self.idx = idx
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
        self.speaker = speaker
    }
}

/// The transcription of one planned piece. [speaker] is nil when the recording was not diarized.
/// Ported from `com.therapylocal.app.data.Piece`.
public struct Piece: Hashable, Sendable {
    public var startMs: Int64
    public var endMs: Int64
    public var text: String
    public var speaker: String?
    public var textEn: String?

    public init(startMs: Int64, endMs: Int64, text: String, speaker: String? = nil, textEn: String? = nil) {
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
        self.speaker = speaker
        self.textEn = textEn
    }
}

/// Pieces to utterances (the stored segments): empty texts are dropped, and neighbouring pieces of
/// the same speaker, which are the parts of a turn that was split to fit the recogniser or two
/// turns with a short pause, become one utterance. Index 1..n. Ported exactly from
/// `com.therapylocal.app.data.UtteranceBuilder`.
public enum UtteranceBuilder {
    public static func build(_ pieces: [Piece]) -> [SegmentRow] {
        var out = [SegmentRow]()
        for piece in pieces {
            let text = piece.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            // Unknown speakers are never merged: nil does not mean the same person.
            if let last = out.last, piece.speaker != nil, last.speaker == piece.speaker {
                out[out.count - 1] = SegmentRow(
                    idx: last.idx,
                    startMs: last.startMs,
                    endMs: max(last.endMs, piece.endMs),
                    text: last.text + " " + text,
                    speaker: last.speaker
                )
            } else {
                out.append(SegmentRow(idx: out.count + 1, startMs: piece.startMs, endMs: piece.endMs, text: text, speaker: piece.speaker))
            }
        }
        return out
    }

    /// The English text of each utterance of [build], by segment index: the English of its pieces
    /// joined, for the pieces that have one. An utterance none of whose pieces has English is not
    /// in the map. Uses the same merging, so the indices are those of [build].
    public static func english(_ pieces: [Piece]) -> [Int: String] {
        var out: [Int: [String]] = [:]
        var order = [Int]()
        var idx = 0
        var lastSpeaker: String?
        for piece in pieces {
            let text = piece.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { continue }
            let merges = idx > 0 && piece.speaker != nil && lastSpeaker == piece.speaker
            if !merges {
                idx += 1
                order.append(idx)
            }
            lastSpeaker = piece.speaker
            let en = (piece.textEn ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !en.isEmpty {
                if out[idx] == nil { out[idx] = [] }
                out[idx]?.append(en)
            }
        }
        return out.mapValues { $0.joined(separator: " ") }
    }

    /// The fallback when diarization was skipped: every utterance keeps its own piece and has no speaker.
    public static func unlabelled(_ pieces: [Piece]) -> [SegmentRow] {
        build(pieces.map { Piece(startMs: $0.startMs, endMs: $0.endMs, text: $0.text, speaker: nil, textEn: $0.textEn) })
    }
}
