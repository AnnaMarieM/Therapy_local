import XCTest
import MindVaultCore
@testable import MindVault

@MainActor
final class AudioPermissionTests: XCTestCase {
    func testCaptureStartFailsClosedWithoutMicrophonePermission() throws {
        let audio = AudioCaptureService(logger: NoopLogger())
        guard audio.microphonePermission != .granted else {
            throw XCTSkip("Microphone permission is already granted on this simulator/device")
        }

        XCTAssertThrowsError(try audio.start()) { error in
            XCTAssertEqual(error as? AudioCaptureError, .permissionDenied)
        }
    }

    func testSpeechTranscriptionFailsClosedWithoutAuthorization() async throws {
        let transcriber = OnDeviceSpeechTranscriber()
        guard transcriber.speechAuthorizationStatus() != .authorized else {
            throw XCTSkip("Speech authorization is already granted on this simulator/device")
        }

        do {
            _ = try await transcriber.transcribe(pcm: Data([0, 0]), sampleRate: 16_000, language: "en-US")
            XCTFail("Transcription must not run without speech authorization")
        } catch {
            XCTAssertEqual(error as? TranscriptionError, .permissionDenied)
        }
    }
}
