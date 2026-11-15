# MindVaultPerception — integration notes

The perception layer of the iOS port: speaker diarization, the turn plan that cuts a
recording into recognisable pieces, the turn wire, utterance assembly, the chunk-path
fallback, vector math and the embedding seams. Everything here is a port of the Android
app (the source file is named on every type); `ios/CONTRACTS.md` is law.

This file states exactly how the app will call the package. The actual wiring is the
B2/C2/D2 lane (`ios/Sources/**`), not this one.

## What runs where

| Target | Linux | macOS | iOS |
|---|---|---|---|
| `MindVaultPerception` (pure logic) | builds + tested | yes | yes |
| `MindVaultPerceptionSherpa` (diarizer runtime) | compiled out | yes | yes |

The pure target imports only Foundation (+ `MindVaultCore` for the chunk-path merge), so
`swift test` runs on Linux with no ONNX anywhere. The Sherpa target exists only for
Apple platforms: the `sherpa-onnx` dependency is declared with
`condition: .when(platforms: [.iOS, .macOS])` and the single source file is wrapped in
`#if canImport(SherpaOnnx)`. Nothing about the pure logic depends on the runtime; the
tests use fakes for `Diarizing`.

## Diarization runtime (why sherpa-onnx)

Android diarizes with sherpa-onnx `OfflineSpeakerDiarization` on the models
pyannote segmentation-3.0 + WeSpeaker resnet34 (AD-06), pinned in
`app/build.gradle.kts` as `sherpa-onnx-2.0.0.aar` (sha256
`f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc`). The iOS package
takes the same runtime through the official sherpa-onnx SwiftPM package, pinned to the
revision of the v2.0.0 release:

- **Revision** `8abb7c8f87eae85b2e75f9a4115236c978980add` ("Release v2.0.0"). That
  revision's own `Package.swift` pins the prebuilt iOS xcframework
  `sherpa-onnx-v2.0.0-ios-static.xcframework.zip` (URL, size 16,162,102 B, sha256
  `95550cc9e2d9699eccbc41d6d7462fb83cdfc12dd070d937533f8d0383a38286` — verified by
  download on 2026-11-16) and `onnxruntime-libs` v1.28.2 exactly. The pin is mirrored in
  `ModelRegistry.sherpaOnnxRevision` and asserted by `ModelRegistryTests`.
- **Rejected alternative**: raw `onnxruntime-swift` + reimplementing pyannote's
  segmentation → embedding → clustering pipeline by hand. That is a lot of new code to
  keep in parity for no gain; the sherpa-onnx wrapper already gives the exact pipeline
  Android runs, with segment objects shaped like Android's.

Runtime facts the app must respect (all in `PerceptionConfig`, all from
`DiarizeRunner.kt`):

- 16 kHz mono. Sample rate is checked at construction (`sampleRateMismatch`).
- One recording, one call, at most `diarizeMaxSamples` (2 h) — longer takes the chunk
  path before the diarizer is called.
- The diarizer is **not re-entrant**: serialize calls (Android uses a mutex). After a
  runtime failure the object is discarded and re-created, like Android's pool does.
- Construction runs a 2 s self-test on silence; silence must give no turns, or the
  backend cannot be trusted (`selfTestTurns`).
- Config is built explicitly: CPU threads 4, fast clustering threshold 0.5, min duration
  on 0.2 s / off 0.5 s. The Swift wrapper's own defaults differ (0.3 on), so every value
  is passed by hand. Its `setConfig` keeps a loaded model and replaces only the
  clustering, which is exactly how Android re-tunes the speaker hint per call.

## How the app calls it

### 1. Get the models (download + verify)

`ModelRegistry` carries every downloadable model with its URL, byte size and sha256 —
the same pins as Android's `ModelSpec.kt`, asserted by tests. Model files are never
committed to the repository. The app downloads each file and refuses it unless the size
matches **and** the sha256 matches:

```swift
let spec = ModelRegistry.byId("diarization-pyannote3-wespeaker")!
let dir = modelsDirectory.appending(component: spec.subdir!)
for file in spec.files {
    try await download(to: dir.appending(component: file.name), from: file.url)
    guard try sha256(at: dir.appending(component: file.name)) == file.sha256,
          try FileManager.default.attributesOfItem(atPath: dir.appending(component: file.name).path).size == file.size
    else { throw ModelDownloadError.badFile(file) }
}
```

`SherpaOnnxDiarizer.init(modelDirectory:)` re-checks both file sizes before the runtime
touches them (a truncated model can abort natively, where no catch sees it).

### 2. Diarize a whole recording and build the transcript

The flow is the Android `TranscribeFlow` decision core; all of it is in the pure target:

```swift
let pcm: [Int16] = /* 16 kHz mono of the whole recording */

// 1. The gates: skip before the diarizer is asked. Fast and Quality have no fallback.
if let reason = TranscriptPath.skipBeforeDiarize(
        durationMs: durationMs,
        modelReady: modelFilesPresent) {
    // chunk path (step 4); TranscribeFlow never fails the job on diarization in Spoken mode
} else {
    let turns: [DiarTurn] = try await diarizer.diarize(
        samples: Pcm.toFloats(pcm), speakerHint: PerceptionConfig.diarizeSpeakersHint)

    // 2. A single voice has no turns worth planning (Spoken mode): chunk path, "one_speaker".
    if let reason = TranscriptPath.skipAfterPlanning(
            speakers: turns.map { "S\($0.speaker)" }, mode: .spoken) {
        // chunk path
    } else {
        // 3. Plan the turns (merge small gaps, split long turns at a quiet point) and
        //    transcribe each piece with the recogniser, one text per piece.
        let planned = TranscriptPath.plannedTurns(turns, durationMs: durationMs, pcm: pcm)
        let texts = try await transcribe(pieces: planned)   // app lane B2/C2/D2: MindVaultASR
        let pieces = TranscriptPath.pieces(turns: planned, texts: texts)
        let segments = UtteranceBuilder.build(pieces)       // store these rows
        let english = UtteranceBuilder.english(pieces)      // when a piece got an English text
    }
}
```

`speakerHint: PerceptionConfig.diarizeSpeakersHint` is always 2 (a therapist and a
patient, AD-07). 0 would let the clustering threshold decide; the app does not use it.

### 3. The chunk path (fallback)

A recording that cannot be diarized is transcribed per 20 s chunk with a 5 s overlap,
the way the app does today. The saved chunk rows are enough to rebuild a timed
transcript:

```swift
let segments = SegmentBuilder.build(chunkRows)      // TranscriptMerge removes the repeated
                                                    // overlap, times narrow to the kept words
// or, when the app already holds texts:
let pieces = TranscriptPath.chunkPieces(chunkRows)
let segments = UtteranceBuilder.unlabelled(pieces)  // no speaker labels, pieces not merged
```

The skip reason travels with the outcome (`TurnPathOutcome`); the strings are the ones
Android logs so the traces of both ports read the same: `model_missing`, `failed`,
`too_long`, `one_speaker`.

### 4. The turn wire (12 bytes per turn)

When turns cross a boundary (a runner process, a job queue, a test fixture), they go as
the wire form — three big-endian int32 (start ms, end ms, speaker), 12 bytes per turn,
the exact bytes of Android's `EngineCodec`:

```swift
let bytes = try TurnsWire.pack(turns)             // throws when ms does not fit an Int32
let turns = try TurnsWire.unpack(bytes)           // throws when count % 12 != 0
```

An hour of audio at a turn every 0.36 s is 120 kB — small enough to stay inline.

### 5. Text embeddings

The seam is `TextEmbedding` (the app contract `EmbeddingProviding`, the Android
`CueEmbedder` seam). The app provides the implementation; the package guarantees the
shape and the fallback:

- `modelID` — vectors are only ever compared within one model id; the id is stored on
  every row.
- `isSemantic` — `false` for the hashing fallback, and the app must say so (CONTRACTS.md:
  capability gaps are visible, search quality is not pretended).
- `embed(text:query:)` — normalized, so cosine == dot product. Queries and documents are
  prefixed with `EmbeddingSpec.prefix(query:)` (`task: search query | text: ` /
  `task: search result | text: `) — EmbeddingGemma's task prefixes; they are what makes
  the vectors rank right.
- `embedAudio(pcm:)` — may return nil; the iOS baseline keeps it nil.

**iOS baseline** (what the app ships first): the existing `SystemEmbeddingService`
(NLEmbedding sentence embedding, `modelID = "system-sentence-embedding"`) with the
package's `HashingTextEmbedder` (`modelID = "hashing-768"`) as the deterministic
fallback when NL is unavailable. Both are dimension-compatible with EmbeddingGemma's
768-d space, so stores keep working when a semantic model arrives.

**The real model**: EmbeddingGemma 2 740M. Android runs the LiteRT-LM build
(`embeddinggemma-2-740m.litertlm`, text + audio into one 768-d space) — no litertlm
runtime exists on iOS yet, so the iOS text path is the ONNX build
(`onnx-community/embeddinggemma-2-ONNX`, revision
`daa72c51243991dfcaf9f9137d2c573d8f7790c0`), which is **registered and pinned** in
`ModelRegistry.embedOnnx` (three files, sizes + sha256 verified; text only — the repo's
audio encoder is a separate file and is not registered until a runtime story exists)
but **not yet run by this package**: it needs a tokenizer, and no Swift tokenizer
dependency is in scope for this lane. The follow-up recipe:

1. Add `onnxruntime-swift-package-manager` (was 1.24.2 when checked) as an Apple-only
   dependency, behind the same protocol seam.
2. Tokenize with `swift-transformers` (Apple's); `tokenizer.json` is already pinned.
3. Build input ids + attention mask, run `model_q4.onnx`, mean-pool the last hidden
   state, normalize (`EmbeddingSpec.normalized`), store under `EmbeddingSpec.modelID`.
4. Sizes come from `EmbeddingSpec`: pipeline text windows use `pipelineInputTokens`
   (2048); the live audio path sizes with `liveInputTokens(lookbackS:)` (20 s lookback +
   a 20 s query needs 516 tokens → input size 1024).

Open questions for that follow-up (kept open on purpose):

- Which token budget the ONNX build actually accepts (its graph is exported for a fixed
  input size; re-export may be needed per size, or padding handles it).
- Whether the ONNX text vectors match the LiteRT vectors closely enough that Android and
  iOS rows could ever share an index — until measured, the ports keep separate vector
  stores per model id and never compare across them.
- Whether NLEmbedding remains as a lower tier at all once the ONNX path lands, or is
  replaced everywhere (vectors are not comparable across model ids; a re-embedding job
  would be needed).

## What is out of scope

- **ASR** (Canary/Whisper): `MindVaultASR`, lane B1.
- **LLM extraction / translation**: `MindVaultLLM`, lane C1.
- **Live cues**: the live loop belongs to the app (`LiveCueService` in the replica);
  this package gives it `EmbeddingSpec` sizing and the `TextEmbedding` seam only.
- Running any model: the diarizer is the one runtime here; embeddings and recognisers
  live in their own packages / the app.

## Parity map

| Android | This package |
|---|---|
| `asr/Diarizer.kt`, `DiarTurn` | `Diarizing`, `Turns.DiarTurn` |
| `engine/runner/DiarizeRunner.kt` | `MindVaultPerceptionSherpa.SherpaOnnxDiarizer` |
| `engine/wire/TurnsWire` (EngineCodec.kt) | `TurnsWire` |
| `jobs/TurnPlanner.kt` | `TurnPlanner` |
| `audio/QuietCut.kt` | `QuietCut` |
| `data/ChunkAudio.kt`, `audio/AudioChunk.kt` | `ChunkAudio`, `AudioChunk` |
| `data/UtteranceBuilder.kt`, `data.Piece`, `data.SegmentRow` | `Utterances` |
| `data/SegmentBuilder.kt` | `SegmentBuilder` (+ `MindVaultCore.TranscriptMerge/Clean`) |
| `jobs/TranscribeFlow.kt` (decision core) | `TranscriptPath` |
| `config/AppConfig.kt` (perception constants) | `PerceptionConfig` |
| `config/EmbeddingConfig.kt` | `EmbeddingSpec` |
| `models/ModelSpec.kt` (perception + embed models) | `ModelRegistry` |
| `vector/VectorMath.kt` | `VectorMath` |

The JVM golden tests are mirrored 1:1 in `Tests/MindVaultPerceptionTests/`
(`TurnsWireTests`, `TurnPlannerTests`, `QuietCutTests`, `ChunkAudioTests`,
`UtteranceBuilderTests`, `VectorMathTests`, `SegmentBuilderTests`,
`EmbeddingInputSizeTest` → `EmbeddingSpecTests`), with Kotlin's `Random(seed)` replaced
by a seeded xorshift `SeededGenerator`.

## Building and testing on Linux

```sh
export PATH=<toolchain>/usr/bin:$PATH
LD_LIBRARY_PATH=/tmp/opencode/lib swift test --package-path ios/Packages/MindVaultPerception
```

`LD_LIBRARY_PATH` is only needed where the system libxml2 is newer than the toolchain's
soname (`libxml2.so.2`). The package needs no other setup: the sherpa-onnx dependency is
resolved but its binary artifacts are never built on Linux (they are iOS/macOS
xcframeworks), and `MindVaultPerceptionSherpa` compiles to nothing under `canImport`.

Measured on 2026-11-16, Swift 6.3.3, aarch64 Linux: **79 tests, 0 failures** across all
ten suites (`ChunkAudioTests`, `EmbeddingSpecTests`, `ModelRegistryTests`,
`QuietCutTests`, `SegmentBuilderTests`, `TranscriptPathTests`, `TurnPlannerTests`,
`TurnsWireTests`, `UtteranceBuilderTests`, `VectorMathTests`). The iOS compile itself is
the CI lane's job (`ios.yml`): the sherpa-onnx xcframeworks and the wrapper API cannot
be compiled on this machine — the wiring follows the pinned revision's own Swift API,
which was checked line by line (`SherpaOnnxOfflineSpeakerDiarizationWrapper`,
`sherpaOnnxOfflineSpeakerDiarizationConfig` and friends).

