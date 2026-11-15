# Brief — core-01

**Task:** W1-DATA (core/domain/persistence)  
**Baseline:** `/home/martin/projects/Therapy_local/orchestration/space/integration/ios`  
**Staging:** `/home/martin/projects/Therapy_local/orchestration/agents/core-01/ios`  
**Status:** ready

## Mission

Own the Linux-testable domain and persistence foundation. Keep the frozen models, pure algorithms, encrypted document store, and repositories source-compatible for the app and other squads.

## Owned files

- `Packages/MindVaultCore/Sources/MindVaultModels/`
- `Packages/MindVaultCore/Sources/MindVaultCore/`
- `Packages/MindVaultCore/Sources/MindVaultPersistence/`
- `Packages/MindVaultCore/Sources/MindVaultLogging/`
- `Packages/MindVaultCore/Sources/MindVaultDesign/`
- `Packages/MindVaultCore/Tests/`
- `Packages/MindVaultCore/Package.swift`

## Do not touch

- `Sources/MindVault/` app code
- Android source, Gradle, `.github/`, `KANBAN.md`, other agents' trees
- The integration tree; work in your staging copy and hand off

## Frozen contracts

- `Client.normalizedID` = first name + last initial, lowercased alphanumeric (`George M.` → `georgem`).
- `Session.externalID` = `normalizedID_yyyyMMdd-HHmmss`.
- Chunking: 16 kHz mono PCM16, 20 s chunks, 5 s overlap, 15 s step, min new 2 s, min recording 1 s.
- `Fact.evidence` is verbatim transcript text; extraction is extractive.
- `EncryptedDocumentStore` writes `VaultEnvelope` with schema version and AES-GCM ciphertext.
- Core modules must not import SwiftUI, AVFoundation, Speech, NaturalLanguage, CryptoKit, or Security.

## Acceptance

- `swift test --package-path Packages/MindVaultCore` passes on Linux; keep the existing 43 tests green and add coverage for any changed behavior.
- Round-trip and tamper tests cover encrypted persistence.
- Client deletion removes sessions, chunks, facts, mentions, and pending audio through the repository contract.
- Public API remains source-compatible; any change is proposed in `HANDOFF.md` before integration.
- No real patient data, secrets, or network code.

## Tests to add

- Boundary cases for `ChunkMath` (1 s, 2 s, 19/20/21/22 s, 37 s, 42 s, 52 s).
- Overlap merge and duplicate-fact cases with synthetic transcripts.
- Repository client-isolation test: two clients cannot see each other's facts or search results.
- Corrupt/tampered envelope rejection.

## Handoff

Use `STATUS_TEMPLATE.md` and `HANDOFF_TEMPLATE.md`. Include exact `swift test` output, test count, and any API changes. Request integration through `updates/<TASK-ID>.md`.
