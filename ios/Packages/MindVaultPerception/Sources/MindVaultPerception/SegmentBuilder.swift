import Foundation
import MindVaultCore

/// The chunk-path transcript (the fallback when a recording cannot be diarized): the session
/// transcript as timed segments, one per chunk that kept any words after [TranscriptMerge] removed
/// the repeated overlap. Read in order, the segments are exactly the merged transcript. A
/// segment's times are the chunk's, narrowed to its kept words assuming they are spread evenly over
/// the chunk. Pure, so rebuilding from the same chunks gives the same segments. Ported exactly from
/// `com.therapylocal.app.data.SegmentBuilder`; the merge itself is MindVaultCore's
/// [TranscriptMerge], the same algorithm as on Android.
public enum SegmentBuilder {
    public static func build(_ chunks: [ChunkRow]) -> [SegmentRow] {
        let texts = chunks.map { TranscriptClean.stripPromptEcho($0.text ?? "") }
        let merged = TranscriptMerge.merge(texts)
        var out = [SegmentRow]()
        for i in chunks.indices {
            guard let kept = merged.kept[i] else { continue }
            let chunk = chunks[i]
            let words = TranscriptMerge.words(texts[i])
            guard !words.isEmpty else { continue }
            let msPerWord = Double(chunk.endMs - chunk.startMs) / Double(words.count)
            let text = words[kept.lowerBound...kept.upperBound].joined(separator: " ")
            out.append(
                SegmentRow(
                    idx: 0,
                    startMs: chunk.startMs + Int64(msPerWord * Double(kept.lowerBound)),
                    endMs: chunk.startMs + Int64(msPerWord * Double(kept.upperBound + 1)),
                    text: text
                )
            )
        }
        for n in out.indices {
            out[n].idx = n + 1
        }
        return out
    }
}
