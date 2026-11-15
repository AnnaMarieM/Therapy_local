import XCTest
@testable import MindVaultLogging

final class LoggingTests: XCTestCase {
    func testRedaction() {
        let text = "Email a@b.com phone +1 (555) 123-4567 id 123456789"
        let redacted = LogRedaction.redact(text)
        XCTAssertFalse(redacted.contains("a@b.com"))
        XCTAssertFalse(redacted.contains("555"))
        XCTAssertFalse(redacted.contains("123456789"))
        XCTAssertTrue(redacted.contains("[email]"))
        XCTAssertTrue(redacted.contains("[number]"))
    }

    func testSafeTruncates() {
        let message = String(repeating: "a", count: 600)
        let safe = LogRedaction.safe(message, limit: 100)
        XCTAssertEqual(safe.count, 101)
        XCTAssertTrue(safe.hasSuffix("…"))
    }

    func testInMemoryLogger() async {
        let logger = InMemoryLogger()
        logger.log(LogEntry(level: .info, category: "test", message: "hello"))
        logger.log(LogEntry(level: .error, category: "test", message: "bad"))
        let entries = logger.entries()
        XCTAssertEqual(entries.count, 2)
        let errors = logger.entries(at: .error)
        XCTAssertEqual(errors.count, 1)
    }
}
