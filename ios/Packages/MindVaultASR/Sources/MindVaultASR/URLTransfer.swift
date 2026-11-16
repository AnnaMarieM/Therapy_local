import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Notices a connection that is up but not delivering: fewer than
/// `minBytesPerSec` bytes on average over a `windowMs` window. A read that
/// blocks outright is caught by the session's request timeout; this catches the
/// trickle that never quite times out. Port of Android
/// `ResumableDownload.StallWatch`.
public struct StallWatch: Sendable {
    public let minBytesPerSec: Int64
    public let windowMs: Int64
    private var windowStart: TimeInterval = -1
    private var bytes: Int64 = 0

    public init(minBytesPerSec: Int64 = 16 * 1024, windowMs: Int64 = 30_000) {
        self.minBytesPerSec = minBytesPerSec
        self.windowMs = windowMs
    }

    /// Feed every read; true once a full window passed too slowly.
    public mutating func stalled(n: Int64, now: TimeInterval) -> Bool {
        if windowStart < 0 { windowStart = now }
        bytes += n
        let elapsed = (now - windowStart) * 1000
        if elapsed < Double(windowMs) { return false }
        let slow = Double(bytes) * 1000 / elapsed < Double(minBytesPerSec)
        windowStart = now
        bytes = 0
        return slow
    }
}

public enum URLTransferError: Error, Equatable {
    /// HTTP status other than 200/206/416.
    case http(Int)
    /// Under `StallWatch` minimum rate for a whole window.
    case stalled
    /// The connection closed before the pinned byte size was reached.
    case shortDownload(have: Int64, expected: Int64)
    /// The body was longer than the pinned byte size. Not a short download: the
    /// server (or a proxy) is wrong about the file, so the value is stated
    /// instead of being folded into `shortDownload`.
    case oversizeBody(received: Int64, expected: Int64)
    /// The server ignored the Range request, or the partial file does not fit.
    case resumeUnsupported
    /// The sha256 of the downloaded file does not match the pin.
    case checksumMismatch(expected: String, actual: String)
    /// The verified file could not be moved onto its final name (`errno`).
    case renameFailed(code: Int32)
}

/// Downloads a model file over a connection that drops all the time, and puts
/// the verified result in place. Port of Android `models/ResumableDownload.kt`
/// with the checksum gate of the Android `fetchSherpa` task folded in: every
/// attempt resumes from the bytes already on disk (HTTP Range), a dropped or
/// stalled connection is retried after a pause that grows only while attempts
/// make no progress, a bad partial file is thrown away, and the whole file is
/// accepted only when its byte size and its sha256 match the pinned values —
/// only then is it atomically renamed from `.part` to its final name.
public struct URLTransfer: Sendable {
    public struct Config: Sendable {
        /// Connect timeout and read-idle timeout (the session resets it on every
        /// chunk that arrives); the stall watch catches slow-but-alive reads.
        public var requestTimeout: TimeInterval = 15
        public var stallBytesPerSec: Int64 = 16 * 1024
        public var stallWindowMs: Int64 = 30_000
        public var maxFailuresWithoutProgress: Int = 15

        public init() {}
    }

    /// 1 s after the first failure without progress, doubling up to 30 s — the
    /// Android `ResumableDownload.backoffMs`.
    public static func backoffMs(failures: Int) -> Int64 {
        let shift = max(0, min(failures - 1, 5))
        return ((1_000 as Int64) << shift).clamped(to: 1_000...30_000)
    }

    public var config: Config

    public init(config: Config = Config()) {
        self.config = config
    }

    /// Downloads `url` into `destination`, verifying the whole file against the
    /// pinned `size` and `sha256` before it is renamed into place. The partial
    /// file (`destination.path + ".part"`) stays on disk for the next attempt
    /// when the task is cancelled or the connection fails; a checksum mismatch
    /// deletes it, so a bad byte never survives.
    public func verified(
        url: String,
        destination: URL,
        size: Int64,
        sha256: String,
        log: (@Sendable (String, [String: String]) -> Void)? = nil,
        progress: (@Sendable (Int64) async -> Void)? = nil
    ) async throws {
        let part = URL(fileURLWithPath: destination.path + ".part")
        try await fetch(url: url, part: part, size: size, log: log, progress: progress)

        let actual = try sha256OfFile(at: part)
        let expected = sha256.lowercased()
        guard actual == expected else {
            try? FileManager.default.removeItem(at: part)
            log?("checksum_mismatch", ["actual": actual])
            throw URLTransferError.checksumMismatch(expected: expected, actual: actual)
        }
        try Self.replaceAtomically(part, destination)
        log?("verified", ["size": "\(size)"])
    }

    /// Puts the verified `.part` file in place with one `rename(2)`.
    ///
    /// Same directory, so the rename is atomic: the destination is either the
    /// old file or the new one, never absent. (Removing it first and then
    /// moving — what this used to do — loses an already-verified model if the
    /// process dies in between.)
    static func replaceAtomically(_ part: URL, _ destination: URL) throws {
        guard rename(part.path, destination.path) == 0 else {
            throw URLTransferError.renameFailed(code: errno)
        }
    }

    /// The URL as it belongs in a log line: scheme, host and path, without the
    /// query or fragment — a pre-signed model URL keeps its signature there.
    /// Android logs `IOException.message`, which carries the whole URL for
    /// `HttpURLConnection`; this is the same information minus anything secret.
    static func logURL(_ raw: String) -> String {
        guard var components = URLComponents(string: raw),
              components.scheme != nil, components.host != nil else { return raw }
        components.query = nil
        components.fragment = nil
        return components.string ?? raw
    }

    /// The resumable fetch itself: `part` ends up holding exactly `size` bytes.
    public func fetch(
        url: String,
        part: URL,
        size: Int64,
        log: (@Sendable (String, [String: String]) -> Void)? = nil,
        progress: (@Sendable (Int64) async -> Void)? = nil
    ) async throws {
        let fm = FileManager.default
        var failures = 0
        while true {
            try Task.checkCancellation()
            var have = fileSize(of: part)
            if have > size {
                log?("part_too_long", ["have": "\(have)", "size": "\(size)"])
                try? fm.removeItem(at: part)
                have = 0
            }
            if have == size { return }
            let before = have
            do {
                try await attempt(url: url, part: part, size: size, log: log, progress: progress)
                let after = fileSize(of: part)
                if after == size { return }
                throw URLTransferError.shortDownload(have: after, expected: size)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // Progress resets the failure counter, like on Android: an
                // attempt that moved bytes is bad luck, one that moved nothing
                // counts toward giving up. An attempt that overshot the pinned
                // size does not count as progress — those bytes are dropped at
                // the top of the loop, so resetting the counter would retry a
                // wrong-sized file forever.
                let after = fileSize(of: part)
                let overshot = after > size
                failures = (after > before && !overshot) ? 1 : failures + 1
                log?("attempt_failed", [
                    "err": "\(error)",
                    "url": Self.logURL(url),
                    "have": "\(after)",
                    "failures": "\(failures)",
                ])
                if failures > config.maxFailuresWithoutProgress {
                    throw error
                }
                let pause = Self.backoffMs(failures: failures)
                try await Task.sleep(nanoseconds: UInt64(pause) * 1_000_000)
            }
        }
    }

    /// One HTTP attempt: Range from what the part file already holds, streamed
    /// into it, stall-watched.
    private func attempt(
        url: String,
        part: URL,
        size: Int64,
        log: (@Sendable (String, [String: String]) -> Void)?,
        progress: (@Sendable (Int64) async -> Void)?
    ) async throws {
        let fm = FileManager.default
        let have = fileSize(of: part)
        guard have <= size else {
            try? fm.removeItem(at: part)
            throw URLTransferError.resumeUnsupported
        }

        guard let requestURL = URL(string: url) else { throw URLTransferError.http(-1) }
        var request = URLRequest(url: requestURL)
        request.timeoutInterval = config.requestTimeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if have > 0 {
            request.setValue("bytes=\(have)-", forHTTPHeaderField: "Range")
        }

        let (status, stream, connection) = try await HTTP.stream(request)
        switch status {
        case 206, 200: break
        case 416:
            try? fm.removeItem(at: part)
            throw URLTransferError.resumeUnsupported
        default:
            throw URLTransferError.http(status)
        }
        let resumed = status == 206
        var done: Int64 = resumed ? have : 0
        log?("attempt_start", ["resumed_from": "\(done)", "size": "\(size)"])

        var out: FileHandle
        if resumed {
            // Resume: append at `have` bytes. Recreating the file here would
            // truncate the downloaded prefix (createFile with nil contents)
            // and a fresh handle writes at offset 0 — the tail would overwrite
            // the head, the short file would still pass the size check, and
            // every retry would repeat the corruption.
            out = try FileHandle(forWritingTo: part)
            try out.seek(toOffset: UInt64(have))
        } else {
            try? fm.removeItem(at: part)
            _ = fm.createFile(atPath: part.path, contents: nil, attributes: nil)
            out = try FileHandle(forWritingTo: part)
        }
        defer { try? out.close() }

        var watch = StallWatch(minBytesPerSec: config.stallBytesPerSec, windowMs: config.stallWindowMs)
        var lastReport = Date().timeIntervalSince1970
        for try await chunk in stream {
            try Task.checkCancellation()
            let n = Int64(chunk.count)
            guard n > 0 else { continue }
            try out.write(contentsOf: chunk)
            done += n
            let now = Date().timeIntervalSince1970
            if watch.stalled(n: n, now: now) {
                throw URLTransferError.stalled
            }
            if let progress, now - lastReport > 0.5 {
                lastReport = now
                await progress(done)
            }
        }
        if let failure = connection.failure {
            throw failure
        }
        if done > size {
            // Longer than the pin: say so, with both numbers. `have` is what
            // arrived and `expected` what the registry pins; the extra bytes are
            // dropped with the partial file on the next pass.
            throw URLTransferError.oversizeBody(received: done, expected: size)
        }
        if done != size {
            throw URLTransferError.shortDownload(have: done, expected: size)
        }
        if let progress {
            await progress(done)
        }
    }

    private func fileSize(of url: URL) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func sha256OfFile(at url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var hasher = SHA256()
        while let chunk = try file.read(upToCount: 4 * 1024 * 1024), !chunk.isEmpty {
            hasher.update(chunk)
        }
        return hasher.hexDigestValue
    }
}

extension SHA256 {
    /// Lowercase hex of the current hash state, consumed.
    var hexDigestValue: String {
        var hasher = self
        return hasher.finish().map { String(format: "%02x", $0) }.joined()
    }
}

private extension Int64 {
    func clamped(to range: ClosedRange<Int64>) -> Int64 {
        Swift.max(range.lowerBound, Swift.min(range.upperBound, self))
    }
}

/// One data task per attempt: the status resolved before the body is consumed,
/// then the body as an async stream of chunks. Built on the data-delegate API,
/// which every SwiftPM platform implements (`URLSession.bytes(for:)` does not).
enum HTTP {
    /// Streams the body of `request`. The status is resolved first; the returned
    /// stream throws when the transfer fails midway. Cancelling the stream
    /// cancels the task and invalidates the session.
    static func stream(_ request: URLRequest) async throws -> (Int, AsyncStream<Data>, StreamDelegate) {
        let delegate = StreamDelegate()
        let config = Session.config
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        let task = session.dataTask(with: request)

        let stream = AsyncStream<Data>(bufferingPolicy: .unbounded) { continuation in
            continuation.onTermination = { _ in
                task.cancel()
                session.finishTasksAndInvalidate()
            }
            delegate.attach(continuation)
            task.resume()
        }
        let status = try await delegate.waitForStatus()
        return (status, stream, delegate)
    }
}

final class StreamDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var statusContinuation: CheckedContinuation<Int, Error>?
    private var status: Int = -1
    private var buffered: [Data] = []
    private var body: AsyncStream<Data>.Continuation?
    private var completed = false
    private var completionError: Error?

    /// The error `didCompleteWithError` reported; nil until the stream ended.
    var failure: Error? {
        lock.lock()
        defer { lock.unlock() }
        return completed ? completionError : nil
    }

    func attach(_ continuation: AsyncStream<Data>.Continuation) {
        lock.lock()
        body = continuation
        let pending = buffered
        buffered = []
        let done = completed
        lock.unlock()
        for chunk in pending {
            continuation.yield(chunk)
        }
        if done {
            continuation.finish()
        }
    }

    func waitForStatus() async throws -> Int {
        try await withCheckedThrowingContinuation { cont in
            lock.lock()
            if completed {
                lock.unlock()
                cont.resume(returning: status)
                return
            }
            statusContinuation = cont
            lock.unlock()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        lock.lock()
        status = (response as? HTTPURLResponse)?.statusCode ?? -1
        let cont = statusContinuation
        statusContinuation = nil
        let code = status
        lock.unlock()
        cont?.resume(returning: code)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if let body {
            lock.unlock()
            body.yield(data)
        } else {
            buffered.append(data)
            lock.unlock()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        completed = true
        completionError = error
        let statusCont = statusContinuation
        statusContinuation = nil
        let body = body
        lock.unlock()
        if let statusCont {
            // The response callback never ran: the request failed outright.
            statusCont.resume(throwing: error ?? URLError(.badServerResponse))
        }
        body?.finish()
    }
}

enum Session {
    /// `timeoutIntervalForRequest` is the idle-read timeout (reset on every
    /// chunk); the resource timeout stays far above a whole multi-gigabyte
    /// model, so only the stall watch and the request timeout can end a slow
    /// but healthy download.
    static var config: URLSessionConfiguration {
        let c = URLSessionConfiguration.default
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = 15
        c.timeoutIntervalForResource = 24 * 60 * 60
        return c
    }
}
