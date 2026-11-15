import XCTest
@testable import MindVaultModels
@testable import MindVaultPersistence

private struct TestCipher: VaultCipher {
    enum Failure: Error { case invalid }
    let key: UInt8

    func seal(_ plaintext: Data, context: String) throws -> Data {
        var output = Data([0xAA])
        let contextBytes = Array(context.utf8)
        for (index, byte) in plaintext.enumerated() {
            let mask = key ^ contextBytes[index % contextBytes.count] ^ UInt8(index & 0xff)
            output.append(byte ^ mask)
        }
        return output
    }

    func open(_ ciphertext: Data, context: String) throws -> Data {
        guard ciphertext.first == 0xAA else { throw Failure.invalid }
        var output = Data()
        let contextBytes = Array(context.utf8)
        for (index, byte) in ciphertext.dropFirst().enumerated() {
            let mask = key ^ contextBytes[index % contextBytes.count] ^ UInt8(index & 0xff)
            output.append(byte ^ mask)
        }
        return output
    }
}

final class EncryptedDocumentStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let directory {
            try? FileManager.default.removeItem(at: directory)
        }
    }

    func testRoundTripAndCiphertextAtRest() async throws {
        let url = directory.appendingPathComponent("vault.json")
        let cipher = TestCipher(key: 0x42)
        let store = EncryptedDocumentStore<VaultDocument>(fileURL: url, cipher: cipher)
        try await store.save(Fixtures.document)
        let loaded = try await store.load()
        XCTAssertEqual(loaded, Fixtures.document)

        let raw = try Data(contentsOf: url)
        XCTAssertNil(raw.range(of: Data("George".utf8)))
        XCTAssertNil(raw.range(of: Data("sister Maya".utf8)))
    }

    func testMissingFileReturnsNil() async throws {
        let url = directory.appendingPathComponent("missing.json")
        let store = EncryptedDocumentStore<VaultDocument>(fileURL: url, cipher: TestCipher(key: 1))
        let loaded = try await store.load()
        XCTAssertNil(loaded)
        let exists = await store.exists()
        XCTAssertFalse(exists)
    }

    func testDelete() async throws {
        let url = directory.appendingPathComponent("vault.json")
        let store = EncryptedDocumentStore<VaultDocument>(fileURL: url, cipher: TestCipher(key: 1))
        try await store.save(.empty())
        let existsBefore = await store.exists()
        XCTAssertTrue(existsBefore)
        try await store.delete()
        let existsAfter = await store.exists()
        XCTAssertFalse(existsAfter)
    }
}

final class PendingAudioStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let directory {
            try? FileManager.default.removeItem(at: directory)
        }
    }

    func testWriteReadDelete() async throws {
        let store = EncryptedPendingAudioStore(directory: directory, cipher: TestCipher(key: 0x33))
        let sessionID = UUID()
        let first = PendingAudioChunk(index: 0, startMs: 0, endMs: 20_000, overlapMs: 0, pcmData: Data([1, 2, 3, 4]))
        let second = PendingAudioChunk(index: 1, startMs: 15_000, endMs: 35_000, overlapMs: 5_000, pcmData: Data([5, 6, 7]))
        try await store.write(first, sessionID: sessionID)
        try await store.write(second, sessionID: sessionID)

        let hasAudio = try await store.hasPendingAudio(sessionID: sessionID)
        XCTAssertTrue(hasAudio)
        let chunks = try await store.readChunks(sessionID: sessionID)
        XCTAssertEqual(chunks, [first, second])

        try await store.delete(sessionID: sessionID)
        let stillHasAudio = try await store.hasPendingAudio(sessionID: sessionID)
        XCTAssertFalse(stillHasAudio)
        let remaining = try await store.readChunks(sessionID: sessionID)
        XCTAssertEqual(remaining, [])
    }
}

final class VaultRepositoryTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let directory {
            try? FileManager.default.removeItem(at: directory)
        }
    }

    func testMutationsPersist() async throws {
        let store = EncryptedDocumentStore<VaultDocument>(
            fileURL: directory.appendingPathComponent("vault.json"),
            cipher: TestCipher(key: 9)
        )
        let repository = EncryptedVaultRepository(store: store)
        let client = Client(firstName: "Test", lastName: "Person")
        try await repository.upsertClient(client)
        let session = Session(clientID: client.id, externalID: "testperson_20261030-120000", status: .saved)
        try await repository.upsertSession(session)

        var document = try await repository.loadDocument()
        XCTAssertEqual(document.clients.count, 1)
        XCTAssertEqual(document.sessions.count, 1)

        let fact = Fact(
            clientID: client.id,
            sessionID: session.id,
            subject: "Test",
            text: "Test likes walking.",
            evidence: "I like walking."
        )
        try await repository.appendFacts([fact], mentions: [])
        document = try await repository.loadDocument()
        XCTAssertEqual(document.facts.count, 1)

        try await repository.deleteClient(id: client.id)
        document = try await repository.loadDocument()
        XCTAssertTrue(document.clients.isEmpty)
        XCTAssertTrue(document.sessions.isEmpty)
        XCTAssertTrue(document.facts.isEmpty)
    }

    func testInMemoryRepository() async throws {
        let repository = InMemoryVaultRepository()
        let client = Client(firstName: "Memory")
        try await repository.upsertClient(client)
        let document = try await repository.loadDocument()
        XCTAssertEqual(document.clients.first?.displayName, "Memory")
    }
}
