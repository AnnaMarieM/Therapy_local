import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// A minimal HTTP/1.1 test server for the `URLTransfer` tests: one thread, one
/// accepted connection at a time, request read, handler called, response
/// written, socket closed. Lives on 127.0.0.1 at an ephemeral port, so tests
/// exercise the real `URLSession` stack against a real socket instead of a
/// mocked protocol layer — the same shape the review's out-of-repo harness
/// used to test resumable downloads (SHOULD 6, card #129).
///
/// Not a general HTTP server: no request body, no chunked encoding, no
/// keep-alive (`Connection: close` on every response). Exactly what the
/// transfer code asks a model host for.
final class TestHTTPServer: @unchecked Sendable {
    struct Request: Sendable {
        let path: String
        let headers: [String: String]
        /// The `Range` header parsed as the start offset the client already
        /// holds, or nil when the request carried none.
        var rangeStart: Int? {
            guard let range = headers["range"], range.hasPrefix("bytes=") else { return nil }
            let spec = range.dropFirst("bytes=".count)
            let start = spec.prefix(while: { $0.isNumber })
            return start.isEmpty ? nil : Int(start)
        }
    }

    struct Response: Sendable {
        let status: Int
        let headers: [String: String]
        let body: Data

        init(_ status: Int, _ body: Data, headers: [String: String] = [:]) {
            self.status = status
            self.headers = headers
            self.body = body
        }
    }

    private let handler: @Sendable (Request) -> Response
    private var listenFD: Int32 = -1
    private var port: UInt16 = 0
    private let lock = NSLock()
    private var stopped = false

    struct ServerError: Error, CustomStringConvertible {
        let step: String
        var description: String { "TestHTTPServer \(step) failed (errno \(errno))" }
    }

    init(handler: @escaping @Sendable (Request) -> Response) {
        self.handler = handler
    }

    /// Starts accepting and returns the base URL to request.
    func start() throws -> URL {
        lock.lock()
        defer { lock.unlock() }
        #if canImport(Glibc)
        let fd = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        #else
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        #endif
        guard fd >= 0 else { throw ServerError(step: "socket") }
        var one: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0  // ephemeral
        address.sin_addr = in_addr(s_addr: 0)  // INADDR_ANY
        let bindResult = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            close(fd)
            throw ServerError(step: "bind")
        }
        guard listen(fd, 16) == 0 else {
            close(fd)
            throw ServerError(step: "listen")
        }
        var bound = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &bound) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard nameResult == 0 else {
            close(fd)
            throw ServerError(step: "getsockname")
        }
        port = UInt16(bigEndian: bound.sin_port)
        listenFD = fd
        stopped = false
        let thread = Thread { [weak self] in self?.acceptLoop() }
        thread.stackSize = 256 * 1024
        thread.start()
        guard let url = URL(string: "http://127.0.0.1:\(port)/ggml-test.bin") else {
            throw URLError(.badURL)
        }
        return url
    }

    /// Stops the server: the listen socket closes, the accept loop ends.
    func stop() {
        lock.lock()
        stopped = true
        let fd = listenFD
        listenFD = -1
        lock.unlock()
        if fd >= 0 {
            close(fd)
        }
    }

    private func acceptLoop() {
        while true {
            lock.lock()
            let fd = listenFD
            let isStopped = stopped
            lock.unlock()
            if isStopped || fd < 0 { return }
            let connection = accept(fd, nil, nil)
            if connection < 0 { return }  // the listen socket was closed
            serve(connection)
            close(connection)
        }
    }

    private func serve(_ fd: Int32) {
        guard let request = readRequest(fd) else { return }
        let response = handler(request)
        var head = "HTTP/1.1 \(response.status) \(Self.reasonPhrase(response.status))\r\n"
        head += "Content-Length: \(response.body.count)\r\n"
        head += "Connection: close\r\n"
        for (name, value) in response.headers.sorted(by: { $0.key < $1.key }) {
            head += "\(name): \(value)\r\n"
        }
        head += "\r\n"
        var bytes = [UInt8](head.utf8)
        bytes.append(contentsOf: [UInt8](response.body))
        sendAll(fd, bytes)
    }

    private func sendAll(_ fd: Int32, _ bytes: [UInt8]) {
        var offset = 0
        while offset < bytes.count {
            let sent = bytes.withUnsafeBufferPointer { buffer -> Int in
                send(fd, buffer.baseAddress! + offset, bytes.count - offset, 0)
            }
            if sent <= 0 { return }
            offset += sent
        }
    }

    /// Reads one request head ("…\r\n\r\n"). The transfer client sends no
    /// request body, so the head is the whole request.
    private func readRequest(_ fd: Int32) -> Request? {
        var data = Data()
        let terminator = Data([0x0D, 0x0A, 0x0D, 0x0A])
        var buffer = [UInt8](repeating: 0, count: 4096)
        for _ in 0..<32 {
            let received = buffer.withUnsafeMutableBufferPointer { pointer -> Int in
                recv(fd, pointer.baseAddress, pointer.count, 0)
            }
            guard received > 0 else { break }
            data.append(contentsOf: buffer[0..<received])
            if let range = data.range(of: terminator) {
                let head = String(decoding: data[..<range.lowerBound], as: UTF8.self)
                return Self.parse(head)
            }
        }
        return nil
    }

    private static func parse(_ head: String) -> Request? {
        let lines = head.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = String(line[..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }
        return Request(path: String(parts[1]), headers: headers)
    }

    private static func reasonPhrase(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 206: return "Partial Content"
        case 403: return "Forbidden"
        case 416: return "Range Not Satisfiable"
        case 500: return "Internal Server Error"
        default: return "Status \(status)"
        }
    }
}
