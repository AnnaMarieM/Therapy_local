import XCTest
@testable import MindVaultLLM

/// The pure SHA-256 (the same algorithm as MindVaultCore's, self-contained here).
final class SHA256Tests: XCTestCase {
    /// FIPS 180-2 test vectors.
    func testKnownVectors() {
        XCTAssertEqual(SHA256.hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256.hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
                       "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        XCTAssertEqual(SHA256.hex("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"),
                       "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1")
    }

    /// The padding boundaries (55, 56, 63, 64, 65 bytes) each hash — different inputs
    /// different digests, and no input crashes the padding loop.
    func testBlockBoundaries() {
        let digests = [55, 56, 57, 63, 64, 65, 128].map { n in SHA256.hex(String(repeating: "a", count: n)) }
        XCTAssertEqual(Set(digests).count, digests.count)
        digests.forEach { XCTAssertEqual($0.count, 64) }
    }

    func testHexIsStableAndLowercase() {
        let h = SHA256.hex("věta")
        XCTAssertEqual(h, SHA256.hex("věta"))
        XCTAssertEqual(h, h.lowercased())
        XCTAssertEqual(h.count, 64)
    }

    func testHashReturnsRawBytes() {
        XCTAssertEqual(SHA256.hash(Data()).count, 32)
        // The digest of "abc" as raw bytes (the FIPS vector above, not a hex string).
        XCTAssertEqual(Array(SHA256.hash(Data("abc".utf8))),
                       [0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
                        0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad])
    }
}
