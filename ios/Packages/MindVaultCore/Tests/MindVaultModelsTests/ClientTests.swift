import XCTest
@testable import MindVaultModels

final class ClientTests: XCTestCase {
    func testDisplayNameAndNormalization() {
        let client = Client(firstName: " George ", lastName: "Miller")
        XCTAssertEqual(client.firstName, "George")
        XCTAssertEqual(client.displayName, "George M.")
        XCTAssertEqual(client.normalizedID, "georgem")
        XCTAssertEqual(client.initials, "GM")
    }

    func testValidationRejectsEmptyName() {
        let client = Client(firstName: "   ")
        XCTAssertThrowsError(try client.validated()) { error in
            XCTAssertEqual(error as? ClientValidationError, .emptyName)
        }
    }

    func testSessionFormatting() {
        let session = Session(
            clientID: Fixtures.anaID,
            externalID: "ana_20260101-000000",
            duration: 1_200,
            chunkCount: 2
        )
        XCTAssertEqual(session.clockDuration, "20:00")
        XCTAssertEqual(session.chunkLabel, "2 chunks")
    }

    func testDocumentRoundTrip() throws {
        let data = try MindVaultJSON.encode(Fixtures.document)
        let decoded = try MindVaultJSON.decode(VaultDocument.self, from: data)
        XCTAssertEqual(decoded, Fixtures.document)
    }

    func testFixtureEvidenceIsVerbatim() {
        for fact in Fixtures.facts {
            XCTAssertTrue(fact.evidence.isEmpty == false)
            let source = Fixtures.sessions.first { $0.id == fact.sessionID }?.transcript ?? ""
            XCTAssertTrue(TextNormalizationProxy.contains(fact.evidence, in: source), "Evidence must be verbatim")
        }
    }
}

private enum TextNormalizationProxy {
    static func contains(_ needle: String, in haystack: String) -> Bool {
        haystack.lowercased().contains(needle.lowercased())
    }
}
