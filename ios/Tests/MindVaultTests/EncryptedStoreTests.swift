import XCTest
import MindVaultCore
@testable import MindVault

final class EncryptedStoreTests: XCTestCase {
    func testStoreEncryptsAndRoundTrips() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("MindVaultStoreTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("vault.json")

        let keyBox = VaultKeyBox()
        try keyBox.set(Data(repeating: 0x33, count: 32))
        let store = EncryptedDocumentStore<VaultDocument>(
            fileURL: fileURL,
            cipher: CryptoKitVaultCipher(keyBox: keyBox)
        )

        try await store.save(Fixtures.document)
        let raw = try Data(contentsOf: fileURL)
        let rawText = String(data: raw, encoding: .utf8) ?? ""
        XCTAssertFalse(rawText.contains("George"))
        XCTAssertFalse(rawText.contains("synthetic"))

        let loaded = try await store.loadRequired()
        XCTAssertEqual(loaded.clients.first?.firstName, Fixtures.document.clients.first?.firstName)
        XCTAssertEqual(loaded.sessions.count, Fixtures.document.sessions.count)
    }

    func testStoreRejectsTamperedCiphertext() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("MindVaultStoreTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("vault.json")

        let keyBox = VaultKeyBox()
        try keyBox.set(Data(repeating: 0x44, count: 32))
        let store = EncryptedDocumentStore<VaultDocument>(
            fileURL: fileURL,
            cipher: CryptoKitVaultCipher(keyBox: keyBox)
        )
        try await store.save(.empty())

        var data = try Data(contentsOf: fileURL)
        data[data.count - 1] ^= 0xFF
        try data.write(to: fileURL)

        do {
            _ = try await store.load()
            XCTFail("Tampered data should not load")
        } catch {
            XCTAssertTrue(true)
        }
    }
}
