import Testing
import Foundation
@testable import MindVaultASR

/// Known-answer vectors for the downloader's checksum gate. Every expected
/// digest was computed with two independent implementations (`sha256sum` and
/// python `hashlib`, which agree byte for byte) — none is derived from the
/// code under test. The single-block cases (`""`, `"abc"`) passed even while
/// `update()` was dropping every block after the first; the multi-block
/// boundary cases are what pin the fix.
@Suite("SHA256 known-answer vectors (sha256sum + hashlib verified)")
struct SHA256VectorTests {

    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// The 0…255 byte pattern repeated `times` times.
    private func pattern(_ times: Int) -> Data {
        Data((0..<times).flatMap { _ in (0..<256).map { UInt8($0) } })
    }

    @Test func fipsVectors() {
        #expect(SHA256.hexDigest(Data()) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        #expect(SHA256.hexDigest(Data("abc".utf8)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    /// The padding boundaries: 55 bytes fills one block to the length field
    /// exactly; 56 forces the two-block padding; 64 is exactly one message
    /// block plus a padding block; 128 is two full message blocks (the input
    /// class the old single-call `update` dropped half of).
    @Test func blockBoundaries() {
        #expect(SHA256.hexDigest(Data(repeating: 0x00, count: 55))
            == "02779466cdec163811d078815c633f21901413081449002f24aa3e80f0b88ef7")
        #expect(SHA256.hexDigest(Data(repeating: 0x00, count: 56))
            == "d4817aa5497628e7c77e6b606107042bbba3130888c5f47a375e6179be789fbb")
        #expect(SHA256.hexDigest(Data(repeating: 0x00, count: 64))
            == "f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b")
        #expect(SHA256.hexDigest(Data(repeating: 0x00, count: 128))
            == "38723a2e5e8a17aa7950dc008209944e898f69a7bd10a23c839d341e935fd5ca")
    }

    @Test func dataPatterns() {
        #expect(SHA256.hexDigest(Data((0..<256).map { UInt8($0) }))
            == "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880")
        #expect(SHA256.hexDigest(pattern(4))
            == "785b0751fc2c53dc14a4ce3d800e69ef9ce1009eb327ccf458afe09c242c26c9")
    }

    /// The multi-block, multi-megabyte vector — and, fed in 64 KiB updates, the
    /// exact shape of the downloader's hashing (URLTransfer reads the file in
    /// 4 MiB chunks into `update`); a 64 KiB update is 1024 blocks, so this
    /// fails on any implementation that loses blocks between updates.
    @Test func millionA_OneShotAndChunked() {
        let data = Data(repeating: UInt8(ascii: "a"), count: 1_000_000)
        let expected = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        #expect(SHA256.hexDigest(data) == expected)

        var hasher = SHA256()
        var offset = 0
        while offset < data.count {
            let end = Swift.min(offset + 65_536, data.count)
            hasher.update(data.subdata(in: offset..<end))
            offset = end
        }
        #expect(hex(hasher.finish()) == expected)
    }

    /// Streaming must be identical to one-shot whatever the update split is —
    /// including updates smaller than one block and exactly one block.
    @Test func chunkedEqualsOneShot() {
        let data = pattern(7)  // 1792 bytes
        let expected = SHA256.hexDigest(data)
        for chunk in [1, 3, 55, 56, 63, 64, 65, 127, 128, 129, 1024] {
            var hasher = SHA256()
            var offset = 0
            while offset < data.count {
                let end = Swift.min(offset + chunk, data.count)
                hasher.update(data.subdata(in: offset..<end))
                offset = end
            }
            #expect(hex(hasher.finish()) == expected, "chunk size \(chunk)")
        }
    }

    @Test func digestLength() {
        #expect(SHA256.digest(Data()).count == 32)
        #expect(SHA256.digestLength == 32)
    }
}
