import Foundation

/// Turns the diarizer's turns into the pieces of audio that are transcribed one by one: tidied
/// (sorted, clamped to the recording, overlaps cut, zero-length turns dropped), turns too short to
/// transcribe absorbed by a neighbour, same-speaker turns with a tiny gap joined, and pieces longer
/// than the recogniser's chunk split into near-equal parts. Ported exactly from
/// `com.therapylocal.app.data.TurnPlanner`; the JVM test is mirrored in TurnPlannerTests.
public enum TurnPlanner {
    /// A turn shorter than this is a click or a breath; the recogniser makes nothing of it.
    public static let minTurnMs: Int64 = 400

    /// The same speaker pausing for less than this has not stopped talking.
    public static let mergeGapMs: Int64 = 300

    /// No piece is longer than the recogniser's chunk.
    public static let maxPieceMs: Int64 = PerceptionConfig.chunkMs

    /// How far around the even split point the cut-point chooser may look (in total, half on each side).
    public static let cutSearchMs: Int64 = 2_000

    /// [cut] gets a range and returns where to cut inside it; the caller can pass the quietest
    /// sample of the audio ([QuietCut]). The default is the middle. The result is clamped into a
    /// range that keeps every part within [maxPieceMs].
    public static func plan(
        _ turns: [TimedTurn],
        durationMs: Int64,
        cut: (Int64, Int64) -> Int64 = { start, end in (start + end) / 2 }
    ) -> [TimedTurn] {
        split(joinSameSpeaker(absorbShort(tidy(turns, durationMs: durationMs))), cut: cut)
    }

    private static func tidy(_ turns: [TimedTurn], durationMs: Int64) -> [TimedTurn] {
        var out = [TimedTurn]()
        var lastEnd: Int64 = 0
        for turn in turns.sorted(by: { $0.startMs == $1.startMs ? $0.endMs < $1.endMs : $0.startMs < $1.startMs }) {
            let start = max(turn.startMs, lastEnd)
            let end = min(turn.endMs, durationMs)
            guard end > start else { continue }
            out.append(TimedTurn(startMs: start, endMs: end, speaker: turn.speaker))
            lastEnd = end
        }
        return out
    }

    /// A short turn extends the one before it, or, at the start, the one after it. Alone, it is dropped.
    private static func absorbShort(_ turns: [TimedTurn]) -> [TimedTurn] {
        var out = [TimedTurn]()
        var pendingStart = Int64(-1)
        for turn in turns {
            let short = turn.endMs - turn.startMs < minTurnMs
            if !out.isEmpty && short {
                out[out.count - 1] = TimedTurn(startMs: out.last!.startMs, endMs: turn.endMs, speaker: out.last!.speaker)
            } else if out.isEmpty && short {
                if pendingStart < 0 { pendingStart = turn.startMs }
            } else {
                if pendingStart >= 0 {
                    out.append(TimedTurn(startMs: pendingStart, endMs: turn.endMs, speaker: turn.speaker))
                } else {
                    out.append(turn)
                }
                pendingStart = -1
            }
        }
        return out
    }

    private static func joinSameSpeaker(_ turns: [TimedTurn]) -> [TimedTurn] {
        var out = [TimedTurn]()
        for turn in turns {
            if let last = out.last, last.speaker == turn.speaker, turn.startMs - last.endMs < mergeGapMs {
                out[out.count - 1] = TimedTurn(startMs: last.startMs, endMs: turn.endMs, speaker: last.speaker)
            } else {
                out.append(turn)
            }
        }
        return out
    }

    private static func split(_ turns: [TimedTurn], cut: (Int64, Int64) -> Int64) -> [TimedTurn] {
        var out = [TimedTurn]()
        for turn in turns {
            var start = turn.startMs
            while turn.endMs - start > maxPieceMs {
                let rest = turn.endMs - start
                let parts = (rest + maxPieceMs - 1) / maxPieceMs
                let even = start + rest / parts
                // The left part stays within maxPieceMs and what remains still fits into the parts left.
                let low = max(start + 1, turn.endMs - (parts - 1) * maxPieceMs)
                let high = start + maxPieceMs
                let at = clamp(
                    cut(max(low, even - cutSearchMs / 2), min(high, even + cutSearchMs / 2)),
                    low: low, high: high
                )
                out.append(TimedTurn(startMs: start, endMs: at, speaker: turn.speaker))
                start = at
            }
            out.append(TimedTurn(startMs: start, endMs: turn.endMs, speaker: turn.speaker))
        }
        return out
    }

    /// kotlin.coerceIn: the bounds are always ordered here (parts >= 2 whenever the loop runs).
    private static func clamp(_ value: Int64, low: Int64, high: Int64) -> Int64 {
        min(max(value, low), high)
    }
}
