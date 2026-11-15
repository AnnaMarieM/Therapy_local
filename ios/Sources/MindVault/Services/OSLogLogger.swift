import Foundation
import MindVaultCore
import os

struct OSLogLogger: AppLogger {
    private let logger = Logger(subsystem: PaperMetadata.bundleIdentifier, category: "app")

    func log(_ entry: LogEntry) {
        let message = LogRedaction.safe(entry.message)
        logger.log(level: level(for: entry.level), "\(message, privacy: .private)")
    }

    private func level(for level: AppLogLevel) -> Logger.Level {
        switch level {
        case .debug: return .debug
        case .info: return .info
        case .warning: return .warning
        case .error: return .error
        }
    }
}
