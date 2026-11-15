import Foundation

public enum AppLogLevel: String, Codable, CaseIterable, Sendable {
    case debug
    case info
    case warning
    case error
}

public struct LogEntry: Codable, Hashable, Sendable {
    public var level: AppLogLevel
    public var category: String
    public var message: String
    public var metadata: [String: String]
    public var file: String
    public var function: String
    public var line: Int
    public var timestamp: Date

    public init(
        level: AppLogLevel,
        category: String,
        message: String,
        metadata: [String: String] = [:],
        file: String = #fileID,
        function: String = #function,
        line: Int = #line,
        timestamp: Date = Date()
    ) {
        self.level = level
        self.category = category
        self.message = message
        self.metadata = metadata
        self.file = file
        self.function = function
        self.line = line
        self.timestamp = timestamp
    }
}

public protocol AppLogger: Sendable {
    func log(_ entry: LogEntry)
}

public struct NoopLogger: AppLogger {
    public init() {}
    public func log(_ entry: LogEntry) {}
}

public final class InMemoryLogger: AppLogger, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [LogEntry] = []

    public init() {}

    public func log(_ entry: LogEntry) {
        lock.lock()
        defer { lock.unlock() }
        storage.append(entry)
    }

    public func entries() -> [LogEntry] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    public func entries(at level: AppLogLevel) -> [LogEntry] {
        lock.lock()
        defer { lock.unlock() }
        return storage.filter { $0.level == level }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        storage.removeAll()
    }
}

public protocol Pseudonymizing: Sendable {
    func pseudonym(for value: String) -> String
}

public enum LogRedaction {
    public static func redact(_ text: String) -> String {
        var value = text
        value = value.replacingOccurrences(
            of: #"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}"#,
            with: "[email]",
            options: [.regularExpression, .caseInsensitive]
        )
        value = value.replacingOccurrences(
            of: #"\+?\d[\d\s().-]{7,}\d"#,
            with: "[number]",
            options: .regularExpression
        )
        value = value.replacingOccurrences(
            of: #"\b\d{6,}\b"#,
            with: "[id]",
            options: .regularExpression
        )
        return value
    }

    public static func redact(_ metadata: [String: String]) -> [String: String] {
        metadata.mapValues { redact($0) }
    }

    public static func safe(_ message: String, limit: Int = 500) -> String {
        let redacted = redact(message)
        guard redacted.count > limit else { return redacted }
        return String(redacted.prefix(max(0, limit))) + "…"
    }
}
