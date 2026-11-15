# iOS contracts — baseline v1

This document freezes the interfaces shared by the W1 squads. Treat it as an integration contract, not a suggestion. If a squad needs a change, propose it in its own handoff; the integrator decides whether to revise this file.

## Package graph

```text
MindVaultModels
MindVaultDesign
MindVaultLogging
MindVaultPersistence   (depends on Models, Logging)
MindVaultCore          (depends on Models, Persistence, Design, Logging)
```

`MindVaultCore` re-exports every module with `@_exported`. App code imports `MindVaultCore`; it does not need to import the submodules.

Rules:

- Models are `Codable`, `Hashable`, and `Sendable`.
- Persistence protocols are `Sendable`; actors implement mutable stores.
- Core algorithms are pure and must stay Linux-testable. Do not import SwiftUI, AVFoundation, Speech, NaturalLanguage, CryptoKit, or Security in core modules.
- No network APIs, analytics, or third-party packages.

## Domain models

Frozen fields (names and types) are defined in `Packages/MindVaultCore/Sources/MindVaultModels/`.

- `Client`: `id`, `firstName`, `lastNameInitial`, `normalizedID`, `initialNote`, `age`, `occupation`, `createdAt`, `updatedAt`. `normalizedID` is first name plus last initial, lowercased and alphanumeric-only (`George M.` → `georgem`). `displayName` is `"George M."`; `initials` is `"GM"`.
- `Session`: `id`, `clientID`, `externalID`, `startedAt`, `endedAt`, `duration`, `chunkCount`, `status`, `transcript`, `segments`, `createdAt`. `externalID` is `normalizedID + "_" + yyyyMMdd-HHmmss` (`georgem_20261009-140000`).
- `TranscriptSegment`: `id`, `sessionID`, `chunkIndex`, `startMs`, `endMs`, `text`, `createdAt`.
- `Chunk`: `id`, `sessionID`, `index`, `startMs`, `endMs`, `overlapMs`, `text`, `textEmbedding`, `audioEmbedding`, `createdAt`.
- `Fact`: `id`, `clientID`, `sessionID`, `category`, `subject`, `text`, `evidence`, `chunkID`, `embedding`, `createdAt`. `evidence` must be a verbatim substring of the source transcript.
- `Mention`: `id`, `factID`, `sessionID`, `chunkID`, `evidence`, `createdAt`.
- `MemoryCue`: `id`, `factID`, `clientID`, `sessionID`, `chunkID`, `category`, `text`, `score`, `createdAt`.
- `SearchResult`: `id`, `clientID`, `sessionID`, `chunkID`, `factID`, `text`, `snippet`, `score`, `startMs`, `endMs`.
- `VaultDocument`: aggregate root containing clients, sessions, chunks, facts, mentions, jobs, settings, and `savedAt`. See `MindVaultModels/Vault.swift` for the exact shape.

All fixtures are synthetic. Never add real patient data to tests, docs, or screenshots.

## Core algorithms

`ChunkMath` (`MindVaultCore/ChunkMath.swift`):

- 16 kHz mono PCM16; 20 s chunks; 5 s overlap; 15 s step; minimum new audio 2 s; minimum recording 1 s.
- `chunkCount(durationMs:)` and `chunkCount(duration:)` return the number of chunks for a recording. Examples: 19 s → 1, 22 s → 2, 37 s → 3, 52 s → 4.
- `ranges(durationMs:)` returns non-overlapping-start ranges with the documented overlap.

`TranscriptClean`, `TranscriptMerge`, `TranscriptWindows`:

- Cleaning is deterministic: normalize whitespace, remove bracketed non-speech markers, preserve words.
- Merge is overlap-aware and word-based, not string-concatenation based.
- Windows use the same 20/5 timing contract as audio chunks.

`FactParser` / `ParsedFact`:

- Extraction is strictly extractive. Every fact has verbatim `evidence`.
- Duplicate detection is deterministic and conservative; never invent a fact.
- Category is one of `person`, `relationship`, `event`, `place`, `preference`, `goal`, `concern`, `coping`, `other`.

`SearchRanking`:

- `rank`/`rankLexical` are deterministic and stable.
- `search(query:chunks:facts:sessions:clientID:)` returns snippets with source IDs and timestamps.
- `cues` returns at most `limit` cues above `minimumScore`.

`IDs`, `PauseState`, `JobQueue`, `DurationFormat`, `SessionDateFormat`, `TextFormatting`, `HashingEmbedding`, `VectorMath`, `SHA256`, `HMACSHA256`, `PBKDF2`:

- These are shared contracts. Do not duplicate them in app code.
- `PauseState` accumulates elapsed time across pause/resume and stops idempotently.

## Persistence contracts

`VaultCipher`:

```swift
public protocol VaultCipher: Sendable {
    func seal(_ plaintext: Data, context: String) throws -> Data
    func open(_ ciphertext: Data, context: String) throws -> Data
}
```

`EncryptedDocumentStore<Value>`:

- Actor. `save`, `load`, `loadRequired`, `delete`, `exists`.
- Writes a `VaultEnvelope` with schema version and algorithm; ciphertext must not contain plaintext.
- Context strings are stable: `mindvault.document.v1` for the aggregate document.

`VaultRepository`:

```swift
public protocol VaultRepository: Sendable {
    func loadDocument() async throws -> VaultDocument
    func saveDocument(_ document: VaultDocument) async throws
    func upsertClient(_ client: Client) async throws
    func deleteClient(id: UUID) async throws
    func upsertSession(_ session: Session) async throws
    func deleteSession(id: UUID) async throws
    func appendFacts(_ facts: [Fact], mentions: [Mention]) async throws
    func replaceJobs(_ jobs: [Job]) async throws
    func updateSettings(_ settings: AppSettings) async throws
}
```

`EncryptedVaultRepository` and `InMemoryVaultRepository` implement it. `deleteClient` must remove that client's sessions, chunks, facts, mentions, and pending audio. Client isolation is mandatory: no search or cue may cross `clientID`.

`PendingAudioStore` / `EncryptedPendingAudioStore`:

- Pending audio is encrypted at rest and scoped by client/session.
- `EncryptedPendingAudioStore` must not leave plaintext chunk files.

## App service contracts

These live in `Sources/MindVault/Services/` and are app-only.

- `Clock`: `now() -> Date`; `SystemClock` is the production implementation.
- `AppLogger`: `log(LogEntry)`; `OSLogLogger` redacts with `LogRedaction`; `NoopLogger` is for previews/tests.
- `SpeechTranscribing`: `availability(language:)` and `transcribe(pcm:sampleRate:language:)`. On-device only; throw when unavailable.
- `EmbeddingProviding`: `modelID`, `isSemantic`, `embed(text:)`, `embedAudio(pcm:)`. `embedAudio` may return `nil` in the baseline.
- `FactExtracting`: deterministic extraction from transcript; no model download.
- `MemoryProcessing`: `process(session:client:)` appends facts and mentions.
- `SearchServing`: `search(query:clientID:)` over the encrypted document.
- `LocalAuthenticating`: biometric availability and authentication; used by `VaultController`.
- `VaultKeyStore`: Keychain-backed key/credential storage with `save/load/delete/exists`.

`VaultController` owns vault state: `checking`, `needsSetup`, `locked`, `working`, `unlocked`, `failed`. The key box holds the DEK only while unlocked. `lock()` clears it. `eraseAll()` deletes credentials, key material, vault file, and pending audio.

## Security contract

- AES-256-GCM for documents and pending audio; unique context strings per use.
- DEK is 32 random bytes, never logged and never written to plaintext.
- Keychain items use `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` unless a documented migration says otherwise.
- No plaintext PHI in logs, filenames, notifications, screenshots, or test fixtures.
- The passphrase is never stored; only a salted PBKDF2 verifier is stored in the baseline.
- **Required upgrade:** replace the raw-Keychain-DEK baseline with password-derived KEK + sealed DEK. This is the `security-01` acceptance condition.
- No network calls anywhere. `Info.plist` has no ATS exceptions.

## UI/design contract

- Palette: Paper `#EDE6D9`, Ink `#2B221B`, Tonal `#DED5C4`, Rec `#C9361F`, Rev `#2C7A62`, OnColor white; alpha tokens InkSoft .72, InkFaint .55, Hairline .16, Scrim .22.
- Type: Fraunces display, Geist UI, Geist Mono label. Fonts are registered through `UIAppFonts` and live at the root of `Resources/`.
- Motion: spring tokens `Pull`, `Pop`, `Sheet`, `Throw`, `Reel`, `Fade`, `Arrive`; respect Reduce Motion.
- Screens: Vault gate, Home/client list, Profile (brief, search, memory, sessions), Session (consent, recording, live cues, hold-to-finish), Session summary, Settings.
- Minimum touch target 44×44 pt. Primary controls must be reachable one-handed. Accessibility identifiers used by UI tests: `vault.gate`, `vault.password`, `vault.setup`, `vault.unlock`, `home.screen`, `session.screen`, `session.begin`, `session.finish`, `settings.screen`.
- No visible “AI” claims that the baseline cannot support. Capability gaps must be visible in Settings and README.

## File ownership

| Owner | Owned paths |
|---|---|
| `core-01` | `Packages/MindVaultCore/Sources/MindVaultModels`, `MindVaultCore`, `MindVaultPersistence`, `MindVaultLogging`, `MindVaultDesign`; core tests |
| `security-01` | `Sources/MindVault/Services/Vault*.swift`, `LocalAuthenticator.swift`; security tests |
| `audio-01` | `Sources/MindVault/Services/AudioCaptureService.swift`, `SpeechTranscriber.swift`; audio tests |
| `memory-01` | `Sources/MindVault/Services/EmbeddingService.swift`, `FactExtractionService.swift`, `MemoryPipeline.swift`, `SearchService.swift`, `LiveCueService.swift`; memory tests |
| `ui-01` | `Sources/MindVault/App`, `DesignSystem`, `Features`; UI tests |
| `integrator-01` | this integration tree, `project.yml`, `Support/`, `README.md`, `CONTRACTS.md`, `SQUAD_BRIEFS/`, final assembly |

No squad edits Android source, Gradle, `.github/`, another squad's staging tree, or the integration tree.

## Integration rules

1. Copy the approved baseline into `orchestration/agents/<agent-id>/ios/`; never edit `scaffold-01` or another agent.
2. Keep public APIs source-compatible unless `CONTRACTS.md` is revised.
3. Add tests with every behavior change. Core tests must run on Linux. App tests run on macOS.
4. Hand off with `STATUS.md` and `HANDOFF.md`; include exact commands and results.
5. The integrator resolves conflicts, reruns core tests and app parse, and records macOS build/test evidence when available.
