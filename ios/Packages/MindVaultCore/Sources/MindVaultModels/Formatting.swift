import Foundation

public enum DurationFormat {
    public static func minSec(_ seconds: TimeInterval) -> String {
        minSec(Int(seconds.rounded(.down)))
    }

    public static func minSec(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        return "\(clamped / 60) min \(clamped % 60) sec"
    }

    public static func clock(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%02d:%02d", minutes, secs)
    }

    public static func chunkCount(_ count: Int) -> String {
        let safe = max(0, count)
        return safe == 1 ? "1 chunk" : "\(safe) chunks"
    }
}

public enum SessionDateFormat {
    public static func date(_ date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        guard let year = components.year, let month = components.month, let day = components.day else {
            return ""
        }
        return String(format: "%04d-%02d-%02d", year, month, day)
    }

    public static func short(_ date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateFormat = "d MMM yyyy"
        return formatter.string(from: date)
    }

    public static func timestamp(_ date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}

public enum TextFormatting {
    public static func sentence(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return trimmed }
        return String(first).uppercased() + trimmed.dropFirst()
    }

    public static func snippet(_ text: String, maxLength: Int = 140) -> String {
        let collapsed = text
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        guard collapsed.count > maxLength else { return collapsed }
        let end = collapsed.index(collapsed.startIndex, offsetBy: max(0, maxLength - 1))
        return String(collapsed[..<end]).trimmingCharacters(in: .whitespaces) + "…"
    }

    public static func speakerless(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?im)^\s*(?:speaker|spk|patient|therapist|client|doctor)\s*\d*\s*:\s*"#,
            with: "",
            options: .regularExpression
        )
    }
}
