import Foundation

/// Where to cut a long turn in two: the quietest 50 ms of the range, so the cut falls in a pause
/// and not in a word. Ported exactly from `com.therapylocal.app.data.QuietCut`.
public enum QuietCut {
    public static let windowMs: Int64 = 50
    private static let hopMs: Int64 = 10

    /// The time in [startMs, endMs) at the middle of the window with the lowest energy (sum of
    /// squared samples, like an RMS without the two roots); ties go to the one nearest the middle
    /// of the range.
    public static func at(_ pcm: [Int16], startMs: Int64, endMs: Int64) -> Int64 {
        let middle = (startMs + endMs) / 2
        let rate = Int64(PerceptionConfig.samplesPerMs)
        let window = Int(windowMs * rate)
        let from = Int(clamp(startMs * rate, low: 0, high: Int64(pcm.count)))
        let to = Int(clamp(endMs * rate, low: 0, high: Int64(pcm.count)))
        guard to - from >= window else { return middle }
        var best = Int64.max
        var bestDistance = Int64.max
        var at = middle
        var s = from
        let hop = Int(hopMs * rate)
        while s + window <= to {
            var energy: Int64 = 0
            for i in s..<(s + window) {
                energy += Int64(pcm[i]) * Int64(pcm[i])
            }
            let centre = Int64(s + window / 2) / rate
            let distance = abs(centre - middle)
            if energy < best || (energy == best && distance < bestDistance) {
                best = energy
                bestDistance = distance
                at = centre
            }
            s += hop
        }
        return at
    }

    private static func clamp(_ value: Int64, low: Int64, high: Int64) -> Int64 {
        min(max(value, low), high)
    }
}
