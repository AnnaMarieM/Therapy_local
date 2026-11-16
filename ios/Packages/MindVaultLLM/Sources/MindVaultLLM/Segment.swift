/// One stored utterance of a transcript, ported from
/// app/src/main/java/com/therapylocal/app/data/SegmentRow.kt (the fields the pure
/// logic reads; the store row lives in the app). `idx` is the 1-based segment
/// number the store and every cache key use.
public struct Segment: Equatable, Sendable {
    public let idx: Int
    public let startMs: Int64
    public let endMs: Int64
    public var text: String
    public let speaker: String?

    public init(_ idx: Int, _ startMs: Int64, _ endMs: Int64, _ text: String, _ speaker: String? = nil) {
        self.idx = idx
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
        self.speaker = speaker
    }
}

/// What a speaker is in the session. `json` is the name stored in `speaker_maps.map_json`.
public enum Role: String, CaseIterable, Sendable {
    case therapist
    case patient
    case other

    public var json: String { rawValue }
}

/// The map from raw diarization ids ("S0") to roles, and its JSON form
/// `{"S0":"therapist","S1":"patient"}`. Utterances keep the raw ids and roles are
/// resolved at read time, so swapping roles never rewrites the transcript.
/// Ported from SpeakerMaps.kt (the append/swap-version seam stays app-side).
public enum SpeakerMaps {
    /// Sorted keys, `{"S0":"therapist"}` — the same form the Android encoder writes.
    public static func encode(_ map: [String: Role]) -> String {
        let keys = map.keys.sorted()
        let body = keys.map { "\(JSONValue.escape($0)):\(JSONValue.escape(map[$0]!.json))" }.joined(separator: ",")
        return "{" + body + "}"
    }

    /// Empty for malformed JSON; a role name that is not known counts as `other`.
    /// Kotlin `trim()` (code points ≤ 0x20) — a trailing `"\n"` in `map_json` must
    /// not turn a role into `other` (Android: `o.optString(k).trim().lowercase()`).
    public static func decode(_ json: String) -> [String: Role] {
        guard let v = JSONValue.parse(json), case .object(let o) = v else { return [:] }
        var out: [String: Role] = [:]
        for key in o.keys.sorted() {
            let name = TextUtil.ktTrim(o[key]?.asString ?? "").lowercased()
            out[key] = Role(rawValue: name) ?? .other
        }
        return out
    }

    /// A swap makes sense only with exactly one therapist and one patient.
    public static func canSwap(_ map: [String: Role]) -> Bool {
        map.values.filter { $0 == .therapist }.count == 1 && map.values.filter { $0 == .patient }.count == 1
    }

    /// Therapist and patient trade places, everything else stays; the map is returned
    /// unchanged when `canSwap` is false.
    public static func swap(_ map: [String: Role]) -> [String: Role] {
        guard canSwap(map) else { return map }
        return map.mapValues { r in
            switch r {
            case .therapist: return .patient
            case .patient: return .therapist
            case .other: return .other
            }
        }
    }

    /// Source of a map the person made by swapping, with full confidence.
    public static let SOURCE_USER = "user"
}
