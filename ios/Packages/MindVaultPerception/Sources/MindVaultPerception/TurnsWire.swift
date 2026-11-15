import Foundation

/// The wire form of speaker turns: 12 bytes per turn (start ms, end ms, speaker, three big-endian
/// 32-bit integers), so an hour of audio with thousands of turns stays small. Milliseconds fit an
/// Int32 for 24 days of audio. Ported exactly from `com.therapylocal.app.engine.wire.TurnsWire`
/// (EngineCodec.kt); the JVM test that pins the bytes is mirrored in TurnsWireTests.
public enum TurnsWire {
    public enum WireError: Error, Equatable {
        /// A start or end does not fit the 32-bit field (Android: ArithmeticException).
        case msDoesNotFitInt32(Int64)
        /// The payload length is not a multiple of 12 (Android: IllegalArgumentException).
        case payloadNotMultipleOfTurn(Int)
    }

    public static let bytesPerTurn = 12

    public static func pack(_ turns: [DiarTurn]) throws -> [UInt8] {
        var bytes = [UInt8]()
        bytes.reserveCapacity(turns.count * bytesPerTurn)
        for turn in turns {
            bytes.append(contentsOf: try int32Bytes(startMs: turn.startMs, endMs: turn.endMs))
            bytes.append(contentsOf: int32Bytes(turn.speaker))
        }
        return bytes
    }

    public static func unpack(_ bytes: [UInt8]) throws -> [DiarTurn] {
        guard bytes.count % bytesPerTurn == 0 else {
            throw WireError.payloadNotMultipleOfTurn(bytes.count)
        }
        var turns = [DiarTurn]()
        turns.reserveCapacity(bytes.count / bytesPerTurn)
        for offset in stride(from: 0, to: bytes.count, by: bytesPerTurn) {
            let startMs = Int64(int32(at: offset, in: bytes))
            let endMs = Int64(int32(at: offset + 4, in: bytes))
            let speaker = int32(at: offset + 8, in: bytes)
            turns.append(DiarTurn(startMs: startMs, endMs: endMs, speaker: speaker))
        }
        return turns
    }

    private static func int32Bytes(startMs: Int64, endMs: Int64) throws -> [UInt8] {
        guard let start = Int32(exactly: startMs) else { throw WireError.msDoesNotFitInt32(startMs) }
        guard let end = Int32(exactly: endMs) else { throw WireError.msDoesNotFitInt32(endMs) }
        return int32Bytes(start) + int32Bytes(end)
    }

    /// Big-endian, the way java.nio.ByteBuffer writes an Int by default.
    private static func int32Bytes(_ value: Int32) -> [UInt8] {
        [UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]
    }

    private static func int32(at offset: Int, in bytes: [UInt8]) -> Int32 {
        let value = (UInt32(bytes[offset]) << 24) | (UInt32(bytes[offset + 1]) << 16)
            | (UInt32(bytes[offset + 2]) << 8) | UInt32(bytes[offset + 3])
        return Int32(bitPattern: value)
    }
}
