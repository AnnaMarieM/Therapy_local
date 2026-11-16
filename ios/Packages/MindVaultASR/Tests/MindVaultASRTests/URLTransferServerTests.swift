import Testing
import Foundation
@testable import MindVaultASR

/// The transfer against a real socket (`TestHTTPServer`), so `URLSession` and
/// the whole fetch/resume/verify/rename path are exercised the way the review
/// asked for (SHOULD 6, card #129): 200 happy path, resume from a `.part`
/// file, checksum mismatch, oversize body, non-200 status, and a server that
/// ignores the Range header. The bodies are deterministic, so every sha256 in
/// here is a fixed literal anyone can reproduce with `sha256sum`.
@Suite("URLTransfer over a local socket")
struct URLTransferServerTests {
    /// Deterministic pseudo-random bytes, so a test body always hashes to the
    /// same value.
    private static func payload(_ count: Int, seed: UInt64 = 0x9E37_79B9_7F4A_7C15) -> Data {
        var state = seed
        var data = Data(capacity: count)
        for _ in 0..<count {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            data.append(UInt8(truncatingIfNeeded: state >> 33))
        }
        return data
    }

    private func workDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mv-transfer-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// A handler that serves `body` and honors `Range` with a 206 — a model
    /// host that supports resume.
    private static func resumingHandler(_ body: Data) -> @Sendable (TestHTTPServer.Request) -> TestHTTPServer.Response {
        return { request in
            if let start = request.rangeStart, start > 0, start < body.count {
                return TestHTTPServer.Response(
                    206,
                    body.suffix(from: start),
                    headers: ["Content-Range": "bytes \(start)-\(body.count - 1)/\(body.count)"]
                )
            }
            return TestHTTPServer.Response(200, body)
        }
    }

    @Test func verifiedDownloadsVerifiesAndRenames() async throws {
        let body = Self.payload(300_000)
        let server = TestHTTPServer(handler: Self.resumingHandler(body))
        let url = try server.start()
        defer { server.stop() }

        let directory = try workDirectory()
        let destination = directory.appendingPathComponent("model.bin")
        try await URLTransfer().verified(
            url: url.absoluteString,
            destination: destination,
            size: Int64(body.count),
            sha256: SHA256.hexDigest(body)
        )

        let downloaded = try Data(contentsOf: destination)
        #expect(downloaded == body)
        #expect(!FileManager.default.fileExists(atPath: destination.path + ".part"))
    }

    @Test func verifiedResumesFromPartFile() async throws {
        let body = Self.payload(300_000)
        final class RangeLog: @unchecked Sendable {
            private let lock = NSLock()
            private var starts: [Int] = []
            var recorded: Bool { lock.lock(); defer { lock.unlock() }; return !starts.isEmpty }
            var first: Int? { lock.lock(); defer { lock.unlock() }; return starts.first }
            func record(_ start: Int?) {
                lock.lock()
                defer { lock.unlock() }
                if let start { starts.append(start) }
            }
        }
        let log = RangeLog()
        let server = TestHTTPServer { request in
            log.record(request.rangeStart)
            return Self.resumingHandler(body)(request)
        }
        let url = try server.start()
        defer { server.stop() }

        let directory = try workDirectory()
        let destination = directory.appendingPathComponent("model.bin")
        // A previous attempt got the first 100 kB down before dropping.
        try body.prefix(100_000).write(to: URL(fileURLWithPath: destination.path + ".part"))
        try await URLTransfer().verified(
            url: url.absoluteString,
            destination: destination,
            size: Int64(body.count),
            sha256: SHA256.hexDigest(body)
        )

        // The request resumed at the part's length, the file is complete, and
        // the content is intact at every offset (not a tail overwriting a head).
        #expect(log.recorded)
        #expect(log.first == 100_000)
        let downloaded = try Data(contentsOf: destination)
        #expect(downloaded.count == body.count)
        #expect(downloaded == body)
        #expect(!FileManager.default.fileExists(atPath: destination.path + ".part"))
    }

    @Test func serverIgnoringRangeStartsOver() async throws {
        let body = Self.payload(200_000)
        // A host without Range support: every request gets the whole file.
        let server = TestHTTPServer { _ in TestHTTPServer.Response(200, body) }
        let url = try server.start()
        defer { server.stop() }

        let directory = try workDirectory()
        let destination = directory.appendingPathComponent("model.bin")
        try body.prefix(50_000).write(to: URL(fileURLWithPath: destination.path + ".part"))
        try await URLTransfer().verified(
            url: url.absoluteString,
            destination: destination,
            size: Int64(body.count),
            sha256: SHA256.hexDigest(body)
        )

        // The stale prefix was discarded and the file rewritten from zero: the
        // result is the body, not a corrupted mix of old and new bytes.
        #expect(try Data(contentsOf: destination) == body)
    }

    @Test func checksumMismatchThrowsAndDeletesPart() async throws {
        let body = Self.payload(10_000)
        let served = Self.payload(10_000, seed: 0x0BAD)
        let server = TestHTTPServer { _ in TestHTTPServer.Response(200, served) }
        let url = try server.start()
        defer { server.stop() }

        let directory = try workDirectory()
        let destination = directory.appendingPathComponent("model.bin")
        do {
            try await URLTransfer().verified(
                url: url.absoluteString,
                destination: destination,
                size: Int64(body.count),
                sha256: SHA256.hexDigest(body)
            )
            Issue.record("a mismatched body must not verify")
        } catch let error as URLTransferError {
            #expect(error == .checksumMismatch(
                expected: SHA256.hexDigest(body),
                actual: SHA256.hexDigest(served)
            ))
        } catch {
            Issue.record("unexpected error type: \(error)")
        }
        // A bad byte never survives: the part is gone, the destination never
        // came into existence.
        #expect(!FileManager.default.fileExists(atPath: destination.path + ".part"))
        #expect(!FileManager.default.fileExists(atPath: destination.path))
    }

    @Test func oversizeBodyThrows() async throws {
        let body = Self.payload(100_000)
        let server = TestHTTPServer { _ in TestHTTPServer.Response(200, body) }
        let url = try server.start()
        defer { server.stop() }

        let directory = try workDirectory()
        let destination = directory.appendingPathComponent("model.bin")
        // The pin is 1 kB short of the body the server sends, and the retry
        // budget is zeroed so the failure surfaces immediately instead of
        // backing off.
        var config = URLTransfer.Config()
        config.maxFailuresWithoutProgress = 0
        do {
            try await URLTransfer(config: config).verified(
                url: url.absoluteString,
                destination: destination,
                size: Int64(body.count - 1_000),
                sha256: SHA256.hexDigest(body)
            )
            Issue.record("a body longer than the pin must not verify")
        } catch let error as URLTransferError {
            #expect(error == .oversizeBody(
                received: Int64(body.count),
                expected: Int64(body.count - 1_000)
            ))
        } catch {
            Issue.record("unexpected error type: \(error)")
        }
    }

    @Test func non200StatusThrows() async throws {
        let server = TestHTTPServer { _ in TestHTTPServer.Response(403, Data("denied".utf8)) }
        let url = try server.start()
        defer { server.stop() }

        let directory = try workDirectory()
        let destination = directory.appendingPathComponent("model.bin")
        var config = URLTransfer.Config()
        config.maxFailuresWithoutProgress = 0
        do {
            try await URLTransfer(config: config).verified(
                url: url.absoluteString,
                destination: destination,
                size: 6,
                sha256: SHA256.hexDigest(Data("denied".utf8))
            )
            Issue.record("a 403 must not verify")
        } catch let error as URLTransferError {
            #expect(error == .http(403))
        } catch {
            Issue.record("unexpected error type: \(error)")
        }
        #expect(!FileManager.default.fileExists(atPath: destination.path + ".part"))
    }
}
