import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class AppEnvironment: ObservableObject {
    let logger: any AppLogger
    let clock: any Clock
    let paths: VaultPaths
    let keyBox: VaultKeyBox
    let repository: any VaultRepository
    let vault: VaultController
    let router: AppRouter
    let notices: NoticeCenter
    let capabilities: CapabilityService
    let audio: AudioCaptureService
    let transcriber: any SpeechTranscribing
    let embeddings: any EmbeddingProviding
    let factExtractor: any FactExtracting
    let memory: any MemoryProcessing
    let search: any SearchServing
    let liveCues: LiveCueService
    let sessions: SessionCoordinator
    let notifications: NotificationService

    init(
        logger: any AppLogger,
        clock: any Clock,
        paths: VaultPaths,
        keyBox: VaultKeyBox,
        repository: any VaultRepository,
        vault: VaultController,
        router: AppRouter,
        notices: NoticeCenter,
        capabilities: CapabilityService,
        audio: AudioCaptureService,
        transcriber: any SpeechTranscribing,
        embeddings: any EmbeddingProviding,
        factExtractor: any FactExtracting,
        memory: any MemoryProcessing,
        search: any SearchServing,
        liveCues: LiveCueService,
        sessions: SessionCoordinator,
        notifications: NotificationService
    ) {
        self.logger = logger
        self.clock = clock
        self.paths = paths
        self.keyBox = keyBox
        self.repository = repository
        self.vault = vault
        self.router = router
        self.notices = notices
        self.capabilities = capabilities
        self.audio = audio
        self.transcriber = transcriber
        self.embeddings = embeddings
        self.factExtractor = factExtractor
        self.memory = memory
        self.search = search
        self.liveCues = liveCues
        self.sessions = sessions
        self.notifications = notifications
    }

    static func live() -> AppEnvironment {
        let logger = OSLogLogger()
        let clock = SystemClock()
        let paths = VaultPaths.live()
        let keyBox = VaultKeyBox()
        let cipher = CryptoKitVaultCipher(keyBox: keyBox)
        let store = EncryptedDocumentStore<VaultDocument>(fileURL: paths.vaultURL, cipher: cipher)
        let repository = EncryptedVaultRepository(store: store)
        let authenticator = LocalAuthenticator()
        let vault = VaultController(
            paths: paths,
            keyBox: keyBox,
            dekStore: KeychainVaultKeyStore(service: PaperMetadata.bundleIdentifier, account: "vault-dek"),
            credentialStore: KeychainVaultKeyStore(service: PaperMetadata.bundleIdentifier, account: "vault-credential"),
            authenticator: authenticator,
            clock: clock,
            logger: logger
        )
        let notices = NoticeCenter()
        let router = AppRouter()
        let audio = AudioCaptureService(logger: logger)
        let transcriber = OnDeviceSpeechTranscriber()
        let embeddings = SystemEmbeddingService()
        let extractor = ExtractiveFactExtractor(embeddings: embeddings)
        let memory = MemoryPipeline(repository: repository, extractor: extractor, logger: logger)
        let search = VaultSearchService(repository: repository)
        let liveCues = LiveCueService(embeddings: embeddings, repository: repository)
        let notifications = NotificationService()
        let sessions = SessionCoordinator(
            repository: repository,
            clock: clock,
            audio: audio,
            liveCues: liveCues,
            notices: notices,
            logger: logger
        )
        let capabilities = CapabilityService(
            transcriber: transcriber,
            embeddings: embeddings,
            authenticator: authenticator
        )
        return AppEnvironment(
            logger: logger,
            clock: clock,
            paths: paths,
            keyBox: keyBox,
            repository: repository,
            vault: vault,
            router: router,
            notices: notices,
            capabilities: capabilities,
            audio: audio,
            transcriber: transcriber,
            embeddings: embeddings,
            factExtractor: extractor,
            memory: memory,
            search: search,
            liveCues: liveCues,
            sessions: sessions,
            notifications: notifications
        )
    }

    static func preview() -> AppEnvironment {
        let logger = NoopLogger()
        let clock = SystemClock()
        let paths = VaultPaths.preview()
        let keyBox = VaultKeyBox()
        try? keyBox.set(Data(repeating: 7, count: 32))
        let repository = InMemoryVaultRepository(document: Fixtures.document)
        let vault = VaultController.previewUnlocked()
        let notices = NoticeCenter()
        let router = AppRouter()
        let audio = AudioCaptureService(logger: logger)
        let transcriber = OnDeviceSpeechTranscriber()
        let embeddings = SystemEmbeddingService()
        let extractor = ExtractiveFactExtractor(embeddings: embeddings)
        let memory = MemoryPipeline(repository: repository, extractor: extractor, logger: logger)
        let search = VaultSearchService(repository: repository)
        let liveCues = LiveCueService(embeddings: embeddings, repository: repository)
        let notifications = NotificationService()
        let sessions = SessionCoordinator(
            repository: repository,
            clock: clock,
            audio: audio,
            liveCues: liveCues,
            notices: notices,
            logger: logger
        )
        let capabilities = CapabilityService(
            transcriber: transcriber,
            embeddings: embeddings,
            authenticator: LocalAuthenticator()
        )
        return AppEnvironment(
            logger: logger,
            clock: clock,
            paths: paths,
            keyBox: keyBox,
            repository: repository,
            vault: vault,
            router: router,
            notices: notices,
            capabilities: capabilities,
            audio: audio,
            transcriber: transcriber,
            embeddings: embeddings,
            factExtractor: extractor,
            memory: memory,
            search: search,
            liveCues: liveCues,
            sessions: sessions,
            notifications: notifications
        )
    }
}
