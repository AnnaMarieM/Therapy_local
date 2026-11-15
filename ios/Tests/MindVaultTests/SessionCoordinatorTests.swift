import XCTest
import MindVaultCore
@testable import MindVault

@MainActor
final class SessionCoordinatorTests: XCTestCase {
    func testNewCoordinatorStartsIdleAndRequiresConsent() {
        let coordinator = makeCoordinator()

        XCTAssertEqual(coordinator.phase, .idle)
        XCTAssertFalse(coordinator.isRecording)
        XCTAssertFalse(coordinator.consentConfirmed)
    }

    func testStopWithoutRecordingReturnsNil() async {
        let coordinator = makeCoordinator()

        let session = await coordinator.stop()

        XCTAssertNil(session)
    }

    func testDiscardReturnsToIdle() {
        let coordinator = makeCoordinator()
        coordinator.consentConfirmed = true

        coordinator.discard()

        XCTAssertEqual(coordinator.phase, .idle)
        XCTAssertFalse(coordinator.consentConfirmed)
    }

    private func makeCoordinator() -> SessionCoordinator {
        let repository = InMemoryVaultRepository()
        let notices = NoticeCenter()
        let audio = AudioCaptureService(logger: NoopLogger())
        let liveCues = LiveCueService(embeddings: SystemEmbeddingService(), repository: repository)
        return SessionCoordinator(
            repository: repository,
            clock: SystemClock(),
            audio: audio,
            liveCues: liveCues,
            notices: notices,
            logger: NoopLogger()
        )
    }
}
