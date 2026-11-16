/// The transcript as text, one block per utterance, blocks separated by a blank line:
/// ```
/// [00:03:12]
/// Therapist: text
/// ```
/// The time is from the start of the session; hours have at least two digits. Labels
/// come from the caller, so the screen can use string resources.
/// Ported from app/src/main/java/com/therapylocal/app/data/TranscriptFormat.kt.
public enum TranscriptFormat {
    /// Utterances without text are left out.
    public static func format(_ segments: [Segment], label: (String?) -> String) -> String {
        segments.filter { !TextUtil.isBlank($0.text) }
            .map { "[\(clock($0.startMs))]\n\(label($0.speaker)): \(TextUtil.ktTrim($0.text))" }
            .joined(separator: "\n\n")
    }

    /// hh:mm:ss, hours growing past two digits when needed; a negative time counts as 0.
    public static func clock(_ ms: Int64) -> String {
        let s = max(ms, 0) / 1000
        return String(format: "%02d:%02d:%02d", Int(s / 3600), Int(s / 60 % 60), Int(s % 60))
    }

    /// "Speaker 1", "Speaker 2", ... in order of first appearance in `segments`;
    /// no speaker is "Speaker ?".
    public static func neutralLabels(_ segments: [Segment]) -> (String?) -> String {
        var order: [String: Int] = [:]
        var next = 1
        for s in segments {
            if let sp = s.speaker, order[sp] == nil {
                order[sp] = next
                next += 1
            }
        }
        return { sp in
            if let n = sp.flatMap({ order[$0] }) { return "Speaker \(n)" }
            return "Speaker ?"
        }
    }

    /// Therapist and patient get their name from `roleName` (the screen passes string
    /// resources); everyone else (other, not in `map`, no speaker) gets the `neutral`
    /// label, so a speaker without a role keeps their number.
    public static func roleLabel(_ map: [String: Role], roleName: @escaping (Role) -> String, neutral: @escaping (String?) -> String) -> (String?) -> String {
        { sp in
            let r = sp.flatMap { map[$0] }
            switch r {
            case .therapist, .patient: return roleName(r!)
            default: return neutral(sp)
            }
        }
    }
}
