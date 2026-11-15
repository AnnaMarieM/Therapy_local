import Foundation
import MindVaultCore
import os

struct OSLogLogger: AppLogger {
    private let logger = Logger(subsystem: PaperMetadata.bundleIdentifier, category: "app")

    func log(_ entry: LogEntry) {
        let message = LogRedaction.safe(entry.message)
        logger.log(level: osLogType(for: entry.level), "\(message, privacy: .private)")
    }

    private func osLogType(for level: AppLogLevel) -> OSLogType {
        switch level {
        case .debug: return .debug
        case .info: return .info
        case .warning: return .default
        case .error: return .error
        }
    }
}
