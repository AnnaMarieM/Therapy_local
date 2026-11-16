# MindVaultASR — integration

How the iOS app (`ios/Sources`) calls this package. Written for the wiring lane
(ios/KANBAN.md card B2); the package itself contains no app code.

## What replaces what

| Android | This package | App-side iOS |
|---|---|---|
| `libwhisper_jni.so` + `WhisperLib` externs | `WhisperEngine` (vendored whisper.cpp, C targets) | — |
| `engine/runner/WhisperRunner.kt` | `WhisperEngine.run(...)` | calls it from the transcribe flow |
| `models/ModelSpec.kt` whisper entries | `ModelRegistry` | shows the download in settings |
| `models/ResumableDownload.kt` | `URLTransfer.verified(...)` | background download + progress |
| `asr/AsrEngine.kt` `Pcm` | `Pcm` | widens recorder PCM16 for the engine |
| `data/TranscriptMerge.kt` (timed) | `SegmentMerge` | merges chunk transcripts into a timeline |
| whisper bracket markers | `WhisperClean` | composes with `MindVaultCore.TranscriptClean` |

## Call sequence for one session

1. Download: `URLTransfer().verified(url:spec.files[0].url, destination: <appSupport>/models/<spec.subdir>/<spec.files[0].name>, size: spec.files[0].size, sha256: spec.files[0].sha256, progress: …)`. The `.part` file resumable convention matches Android exactly; a checksum mismatch deletes the partial.
2. Open once per session (or keep it warm like Android's `:batch` host): `WhisperEngine(path: modelFile.path, useGPU: true)` — `useGPU` is Metal on Apple and ignored on Linux. Returns nil when the model does not load (bad or missing file): treat as "model not ready", not as a crash.
3. Per recording (the Android Quality workflow order): diarize first (MindVaultPerception), then transcribe per diarization turn; without a diarization model, transcribe per recording chunk — the fallback the Android `TranscribeFlow` also uses.
4. Audio: recorder hands PCM16 `Int16` → `Pcm.toFloats` → `engine.run(samples:language:translate:beam:threads:prompt:)`. Defaults mirror Android `WhisperRunner`: `beam 1` (greedy), `threads 4`, `language nil` = auto, `translate` only for the Quality (en) workflow.
5. Merge: per chunk `WhisperClean.clean` then `SegmentMerge.merge(chunks:overlapMs:)` with the recorder's chunk math (20 s chunk / 5 s overlap / 15 s step, `ChunkMath` in MindVaultCore) — or stitch at diarization turn boundaries via `SegmentMerge.timedWords` per turn.
6. `engine.free()` when the session ends; `isFreed` guards double-free. `systemInfo` (build/flags/threads) belongs in the session log like Android's `WhisperRunner` timing line; `WhisperTimings` is the same encode/decode split the Android log line reports.

## Model install layout

`Application Support/models/<spec.subdir>/<spec.files[0].name>` — the same
subdirectory-per-model shape as Android's `getExternalFilesDir(null)/models/…`,
so a restore across platforms keeps the same paths inside the vault.

Free-space gate before starting a download: the model size plus the Android
`ChunkConfig.MIN_FREE_BYTES` margin (300 MB) — refuse to record below 300 MB free,
refuse to download without `size + 256 MB`.

## Registry pins

- `whisper-large-v3-turbo-q8_0` (874 MB) — hosted at
  `huggingface.co/ggerganov/whisper.cpp`, pinned by byte size + sha256; the default
  download (the candidate the ASR experiments validated for phones).
- `whisper-large-v3-q8_0` (1.66 GB) — our own quantisation, **no URL** (mirrors
  Android `WHISPER_Q8_URL = ""`); installed by hand until it is hosted. The Quality
  workflow model.

## Self-test

`engine.selfTest(threads:)` decodes 1 s of silence — the same readiness probe the
Android backend ladder runs (`Accelerators` proven-backend rule). Run it after
open; only a passing self-test marks the model usable. On Linux (CI) the engine
compiles CPU-only and the self-test needs a real model file, which CI does not
download — the pure-logic tests cover the contract there.
