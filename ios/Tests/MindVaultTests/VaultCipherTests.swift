import XCTest
import MindVaultCore
@testable import MindVault

final class VaultCipherTests: XCTestCase {
    func testSealOpenRoundTrip() throws {
        let keyBox = VaultKeyBox()
        try keyBox.set(Data(repeating: 0x11, count: 32))
        let cipher = CryptoKitVaultCipher(keyBox: keyBox)
        let plaintext = Data("synthetic session note".utf8)

        let sealed = try cipher.seal(plaintext, context: "unit-test")

        XCTAssertNotEqual(sealed, plaintext)
        XCTAssertEqual(try cipher.open(sealed, context: "unit-test"), plaintext)
    }

    func testWrongContextFailsAuthentication() throws {
        let keyBox = VaultKeyBox()
        try keyBox.set(Data(repeating: 0x22, count: 32))
        let cipher = CryptoKitVaultCipher(keyBox: keyBox)
        let sealed = try cipher.seal(Data("synthetic".utf8), context: "context-a")

        XCTAssertThrowsError(try cipher.open(sealed, context: "context-b"))
    }

    func testMissingKeyFails() {
        let cipher = CryptoKitVaultCipher(keyBox: VaultKeyBox())

        XCTAssertThrowsError(try cipher.seal(Data("synthetic".utf8), context: "unit-test"))
    }
}
