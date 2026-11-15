# Brief — audio-01

**Task:** W1-AUDIO + W1-ASR  
**Baseline:** `/home/martin/projects/Therapy_local/orchestration/space/integration/ios`  
**Staging:** `/home/martin/projects/Therapy_local/orchestration/agents/audio-01/ios`  
**Status:** ready

## Mission

Deliver real on-device audio capture and chunk transcription: visible consent, 16 kHz mono PCM16 capture, 20 s/5 s overlap chunks, encrypted pending audio, interruption/background handling, and on-device speech recognition with no cloud fallback.

## Owned files

- `Sources/MindVault/Services/AudioCaptureService.swift`
- `Sources/MindVault/Services/SpeechTranscriber.swift`
- New capture/transcription protocols consumed by `SessionCoordinator`; coordinate with `ui-01` for UI wiring
- New audio/transcription service files under `Sources/MindVault/Services/`
- New audio/ASR tests under `Tests/MindVaultTests/`

## Do not touch

- Core `ChunkMath`/`TranscriptMerge` contracts without a proposal
- `VaultController`/Keychain code; use the repository and pending-audio APIs
- Android source, Gradle, `.github/`, `KANBAN.md`, other agents' trees
- The integration tree; work in your staging copy

## Requirements

- Request microphone permission and show explicit consent before capture.
- Configure `AVAudioSession` for record/measurement; handle interruptions, route changes, and background audio.
- Capture 16 kHz mono PCM16; use `ChunkMath` for boundaries; persist chunks only through `EncryptedPendingAudioStore`.
- `SFSpeechRecognizer` must set `requiresOnDeviceRecognition = true`; fail closed if unavailable.
- Merge chunk transcripts with `TranscriptMerge`; clean with `TranscriptClean`; produce timestamped `TranscriptSegment`s.
- No network calls, no model downloads, no plaintext audio.
- Expose progress/partial transcript through a service API; `ui-01` wires it into `SessionCoordinator` and the UI.

## Acceptance

- Chunk files are encrypted and match `ChunkMath` ranges.
- Transcription works offline with on-device assets; unavailable assets produce a clear error, not cloud fallback.
- Interruption/background test or documented simulator limitation.
- Unit tests for chunk boundaries, merge, and permission-denied behavior.
- `make parse-docker` passes; macOS build/tests recorded.
- No real patient audio; synthetic fixtures only.

## Handoff

Use `STATUS_TEMPLATE.md` and `HANDOFF_TEMPLATE.md`. Include test output, device/simulator limitations, and wiring notes for `SessionCoordinator`. Request integration through `updates/<TASK-ID>.md`.
