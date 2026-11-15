# MindVault iOS baseline

Native SwiftUI baseline for the TherapyLocal iOS replica. The app is on-device only: it records a session, keeps transcripts and memory encrypted on the iPhone, and never makes network requests.

This tree is the integrator staging baseline at:

```text
/home/martin/projects/Therapy_local/orchestration/space/integration/ios
```

It is not yet a finished product. The Linux-tested core is complete enough for squad work; the SwiftUI target has only been syntax-parsed on Linux and still needs a macOS/Xcode build.

## What is here

- `Packages/MindVaultCore/` — SwiftPM package with models, design tokens, logging, encrypted persistence, and pure algorithms. It builds and tests on Linux.
- `Sources/MindVault/` — SwiftUI app shell, design system, services, and feature screens.
- `Tests/MindVaultTests/` — app unit tests (crypto/store/coordinator smoke).
- `Tests/MindVaultUITests/` — preview-mode launch smoke test.
- `Support/Info.plist` — privacy strings, document types, fonts, background modes.
- `Sources/MindVault/Resources/` — fonts, licenses, privacy manifest, asset catalog, app icon.
- `SQUAD_BRIEFS/` — ownership and acceptance briefs for W1 squads.
- `CONTRACTS.md` — frozen interfaces and integration rules.

## Requirements

- macOS with Xcode 26.x for the app target.
- XcodeGen (`brew install xcodegen`) to generate `MindVault.xcodeproj`.
- iOS 18.0+ simulator or device.
- Docker only for the Linux core checks (optional on macOS).

The app target uses Swift 5 language mode with minimal strict concurrency. The core package uses Swift 6.

## Quick start

### Core package on Linux (verified)

From this directory:

```bash
docker run --rm -v "$PWD:/work" -w /work swift:6.3 \
  swift test --package-path Packages/MindVaultCore
```

or:

```bash
make core-test-docker
```

### Parse app sources on Linux (verified)

This checks Swift syntax only; it does not resolve SwiftUI/AVFoundation or typecheck the app.

```bash
make parse-docker
```

### Generate and build on macOS

```bash
make project
make build
make test
```

`make test` runs unit and UI tests on the simulator. Override the simulator if needed:

```bash
make test SIMULATOR="iPhone 16"
```

Manual equivalent:

```bash
xcodegen generate
xcodebuild -project MindVault.xcodeproj -scheme MindVault \
  -destination 'platform=iOS Simulator,name=iPhone 16' test
```

### Run the preview-mode UI smoke test

The app accepts `-ui-testing` to launch an in-memory, unlocked preview environment:

```bash
xcodebuild test -project MindVault.xcodeproj -scheme MindVault \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:MindVaultUITests
```

## Privacy and security

- Audio, transcripts, facts, search indexes, and pending audio stay in the app container.
- No networking code, no analytics SDK, no third-party packages, and no model downloads.
- Speech recognition availability is checked for on-device recognition and fails closed when unavailable; chunk transcription is still a placeholder.
- The vault document is encrypted with AES-256-GCM; the data-encryption key is held in memory only while unlocked and is stored in the Keychain between launches.
- The app declares microphone and speech-recognition usage strings and background audio/processing modes in `Support/Info.plist`.
- The privacy manifest declares no tracking and no collected data.

**Known security gap:** the current baseline verifies the passphrase with PBKDF2 and stores the raw DEK in the Keychain. `security-01` must replace this with a password-derived KEK that wraps a sealed DEK, matching the Android vault model.

## Architecture

```text
MindVaultApp
  └─ RootView
      ├─ VaultGateView          unlock/create/erase
      └─ NavigationStack
          ├─ HomeView           client list + add client
          ├─ ProfileView        search, memory, sessions, start
          ├─ SettingsView       privacy/security/capabilities
          └─ SessionView        consent, capture, live transcript, hold-to-finish
              └─ SessionSummaryView  edit transcript, save/discard
```

Core modules:

- `MindVaultModels` — `Client`, `Session`, `Fact`, `SearchResult`, fixtures, formatting.
- `MindVaultDesign` — paper palette, typography, spacing, spring tokens.
- `MindVaultLogging` — logger protocol and PHI-redaction helper.
- `MindVaultPersistence` — `VaultCipher`, encrypted document store, pending audio, repositories.
- `MindVaultCore` — chunk math, transcript clean/merge/windows, fact parser, search ranking, IDs, PBKDF2, pause state, job queue.

## Known gaps

- `OnDeviceSpeechTranscriber.transcribe` is a placeholder; chunk transcription is owned by `audio-01`/`asr-01`.
- `AudioCaptureService` meters input but does not yet persist 20 s/5 s chunks or handle interruptions/background capture.
- `SessionCoordinator` keeps the live transcript empty until transcription is wired; the summary screen allows manual transcript entry.
- Live cues use text embeddings only; audio embeddings are unavailable.
- Fact extraction is deterministic and strictly extractive; Gemma/EmbeddingGemma are not bundled.
- Notifications request permission but are not yet emitted after processing.
- Client deletion removes the encrypted document records but not yet pending-audio files; the pending-audio store is not wired into deletion.
- The SwiftUI target has not been compiled or run on macOS in this environment.

## Contracts and squad workflow

`CONTRACTS.md` defines the frozen core APIs and file ownership. Squad owners copy this baseline into their own staging tree, work only there, and hand off with tests. `integrator-01` assembles accepted work back into this tree.

Do not edit another squad's files, Android source, Gradle configuration, or the integration tree directly.
