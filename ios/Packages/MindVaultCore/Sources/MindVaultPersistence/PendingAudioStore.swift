import Foundation
import MindVaultModels

public struct PendingAudioChunk: Codable, Hashable, Sendable {
    public var index: Int
    public var startMs: Int
    public var endMs: Int
    public var overlapMs: Int
    public var pcmData: Data

    public init(index: Int, startMs: Int, endMs: Int, overlapMs: Int, pcmData: Data) {
        self.index = index
        self.startMs = startMs
        self.endMs = endMs
        self.overlapMs = overlapMs
        self.pcmData = pcmData
    }
}

public protocol PendingAudioStore: Sendable {
    func write(_ chunk: PendingAudioChunk, sessionID: UUID) async throws
    func readChunks(sessionID: UUID) async throws -> [PendingAudioChunk]
    func hasPendingAudio(sessionID: UUID) async throws -> Bool
    func delete(sessionID: UUID) async throws
}

public actor EncryptedPendingAudioStore: PendingAudioStore {
    private struct Header: Codable, Sendable {
        var sessionID: UUID
        var chunkIndex: Int
        var startMs: Int
        var endMs: Int
        var overlapMs: Int
        var createdAt: Date
    }

    private static let magic = Data("MVPA1".utf8)
    private static let version: UInt8 = 1
    private static let headerLengthBytes = 4

    private let directory: URL
    private let cipher: any VaultCipher
    private let clock: @Sendable () -> Date

    public init(
        directory: URL,
        cipher: any VaultCipher,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.directory = directory
        self.cipher = cipher
        self.clock = clock
    }

    public var directoryURL: URL {
        directory
    }

    public func write(_ chunk: PendingAudioChunk, sessionID: UUID) async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let header = Header(
            sessionID: sessionID,
            chunkIndex: chunk.index,
            startMs: chunk.startMs,
            endMs: chunk.endMs,
            overlapMs: chunk.overlapMs,
            createdAt: clock()
        )
        let headerData = try MindVaultJSON.encode(header)
        let sealed = try cipher.seal(chunk.pcmData, context: context(sessionID: sessionID, chunkIndex: chunk.index))
        var container = Data()
        container.append(Self.magic)
        container.append(Self.version)
        Self.appendUInt32(UInt32(headerData.count), to: &container)
        container.append(headerData)
        container.append(sealed)
        let url = fileURL(sessionID: sessionID, index: chunk.index)
        try container.write(to: url, options: [.atomic])
    }

    public func readChunks(sessionID: UUID) async throws -> [PendingAudioChunk] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        let prefix = sessionID.uuidString.lowercased() + "-"
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        let matching = urls
            .filter { $0.lastPathComponent.lowercased().hasPrefix(prefix) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try matching.map { url in
            let data = try Data(contentsOf: url)
            return try decode(data, expectedSessionID: sessionID)
        }
    }

    public func hasPendingAudio(sessionID: UUID) async throws -> Bool {
        guard FileManager.default.fileExists(atPath: directory.path) else { return false }
        let prefix = sessionID.uuidString.lowercased() + "-"
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        return urls.contains { $0.lastPathComponent.lowercased().hasPrefix(prefix) }
    }

    public func delete(sessionID: UUID) async throws {
        guard FileManager.default.fileExists(atPath: directory.path) else { return }
        let prefix = sessionID.uuidString.lowercased() + "-"
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        for url in urls where url.lastPathComponent.lowercased().hasPrefix(prefix) {
            try FileManager.default.removeItem(at: url)
        }
    }

    private func decode(_ data: Data, expectedSessionID: UUID) throws -> PendingAudioChunk {
        guard data.count > Self.magic.count + 1 + Self.headerLengthBytes else {
            throw PersistenceError.corruptData
        }
        let magic = data.prefix(Self.magic.count)
        guard magic == Self.magic else { throw PersistenceError.corruptData }
        let versionOffset = Self.magic.count
        guard data[versionOffset] == Self.version else { throw PersistenceError.corruptData }
        let lengthOffset = versionOffset + 1
        guard let headerLength = Self.readUInt32(data, at: lengthOffset) else {
            throw PersistenceError.corruptData
        }
        let headerStart = lengthOffset + Self.headerLengthBytes
        let headerEnd = headerStart + Int(headerLength)
        guard headerEnd <= data.count else { throw PersistenceError.corruptData }
        let headerData = data.subdata(in: headerStart..<headerEnd)
        let header = try MindVaultJSON.decode(Header.self, from: headerData)
        guard header.sessionID == expectedSessionID else { throw PersistenceError.corruptData }
        let ciphertext = data.subdata(in: headerEnd..<data.count)
        let pcm = try cipher.open(ciphertext, context: context(sessionID: expectedSessionID, chunkIndex: header.chunkIndex))
        return PendingAudioChunk(
            index: header.chunkIndex,
            startMs: header.startMs,
            endMs: header.endMs,
            overlapMs: header.overlapMs,
            pcmData: pcm
        )
    }

    private func context(sessionID: UUID, chunkIndex: Int) -> String {
        "mindvault.pending-audio.v1:\(sessionID.uuidString.lowercased()):\(chunkIndex)"
    }

    private func fileURL(sessionID: UUID, index: Int) -> URL {
        let name = "\(sessionID.uuidString.lowercased())-\(String(format: "%05d", index)).mva"
        return directory.appendingPathComponent(name)
    }

    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= data.count else { return nil }
        return data.withUnsafeBytes { buffer in
            buffer.loadUnaligned(fromByteOffset: offset, as: UInt32.self).bigEndian
        }
    }
}
