# MindVaultLLM — integration

How the iOS app (`ios/Sources`) calls this package. Written for the wiring lane
(ios/KANBAN.md card C2); the package itself contains no app code.

## What replaces what

| Android | This package | App-side iOS |
|---|---|---|
| `liblitertlm` + `LlmLib` externs | `LlamaRunner` (pinned llama.cpp xcframework, `MindVaultLLMLlama`) | calls it from the memory pipeline |
| `engine/runner/LlmRunner.kt` | `LlamaRunner.open/ask/close` | the same open → ask → close ladder |
| `engine/runner/LlmProof.kt` (checker) | `LlmProof` (pure) + `LlamaProof` (engine-side) | — (runs inside `open`) |
| `models/ModelSpec.kt` Gemma entries | `ModelRegistry` | shows the download in settings |
| `models/ResumableDownload.kt` | `URLTransfer.verified(...)` (MindVaultASR) | background download + progress |
| `memory/MemoryFiles.kt` (assets `prompts/`) | `PromptStore` (package resources) | loads prompts by `Prompts` name |
| `PromptBudget` (Android) | `PromptBudget` | sizes the context before `open` |
| `SamplerConfig` | `LlamaRunner.AskOptions` | — (defaults already mirror `AppConfig`) |

## Call sequence for one session

1. Download: `URLTransfer().verified(url: spec.files[0].url, destination:
   <appSupport>/models/<spec.subdir>/<spec.files[0].name>, size: spec.files[0].size,
   sha256: spec.files[0].sha256, progress: …)` — identical to the ASR lane; a checksum
   mismatch deletes the partial.
2. Size the context **app-side** before opening (the Android call does this too):
   `let needed = PromptBudget.required(system, user, maxOutputTokens)` then
   `let context = PromptBudget.contextFor(needed, floor)` (`MAX_CONTEXT` 16384, step 512).
   The engine context is created once with that budget — `LlamaRunner` does not re-check
   prompt sizes. An over-long **prompt** does not truncate: `llama_decode` returns -1 and
   `ask` throws `LlamaError.decodeFailed(-1)` (b11960 has no trimming). Only an over-long
   **reply** stops cleanly at the context edge (LiteRT parity).
3. Open once per session: `LlamaRunner().open(modelPath: options:onPhase:)` with
   `OpenOptions(context: threads: batchSize: gpuLayers: prove:)` — defaults 512 batch,
   `gpuLayers 99` (all layers on Metal; `0` = CPU only), `prove: true`. `onPhase` reports
   `"load"`, `"context"`, `"test"`, `"test2"` for the app's log spans (the names the
   Android runner logs). On success `contextTokens` holds the context the engine actually
   created (log it like Android's `MemoryLlm` logs `max_tokens`).
   `LlamaRunner.gpuOffloadSupported` (llama.cpp's `llama_supports_gpu_offload`) says
   whether Metal offload exists at all — read it before choosing `gpuLayers` and log the
   capability like Android logs the accelerator list.
4. **The two `LlmProof` self-tests run inside `open`** (`prove: true`): the fixed
   reformat task (`LlmProof.check`, greedy: top-k 1, temperature 0) and the extraction
   task (`LlmProof.checkFacts`). A wrong answer throws `LlamaError.proofFailed(reason)`
   and the runner closes itself — the app's backend ladder strikes the choice. The reply
   is never logged (only the reason).
5. Per extraction call: `let (reply, stats) = try runner.ask(system: user: options:
   onToken:)`. A fresh conversation per ask (the KV cache is cleared, the model stays
   loaded — the Android runner creates a new conversation object instead).
   `AskOptions(maxOutputTokens:temperature:topK:topP:seed:grammar:)` defaults mirror the
   app's extraction config: temperature 0.1, topK 20, topP 0.9, seed 1. `onToken` streams
   every sampled piece on the runner's queue (nil = collect only); multi-byte UTF-8
   characters are held until they decode cleanly. `stats` (`promptTokens`, `outputTokens`,
   `wallMs`) goes to `PerfStats` like the Android `LlmCall` numbers.
6. Stop from the UI: `runner.stop()` — generation ends at the next decode boundary and
   `ask` returns what it has. It is soft and one-shot: the next `ask` resets the flag.
   Nothing to stop → no effect. Never call it to free memory; `close()` is that.
7. `runner.close()` when the session ends; safe twice, safe before `open`. One engine
   instance at a time (`open` while open throws `LlamaError.alreadyOpen`).

## Prompts

`PromptStore.load(name, language)` reads the template files the package ships
(`Resources/<name>` English, `Resources/cs/<name>` Czech), trimmed like Android's
`MemoryFiles.prompt` (Kotlin `trim()`, code points ≤ 0x20). No cache — a prompt picked
up on the next call mirrors the Android dev-override behaviour. Prompt names are the
`Prompts` constants (`extract.system`, `review.user`, `summary.birp.system`, …).

**Not shipped here:** `translate.system.txt` and `localize.system.txt` (AD-17) —
translation stays app-side (`Prompts.TRANSLATE_SYSTEM`).

The chat template is the engine's business: at `open` the runner reads the GGUF's
`tokenizer.chat_template` and probes it through `llama_chat_apply_template` —
b11960 does not interpret Jinja, it fingerprints 54 known formats and returns -1
for anything else. A template llama.cpp fingerprints is applied by llama.cpp (the
built-in `"gemma"` name is the fallback when the GGUF carries none). A template
llama.cpp rejects gets the Swift Gemma-4 builder (`Gemma4ChatTemplate`, markers
verified against the model's own template; the pinned Gemma-4 GGUFs are exactly
this case — their Jinja template matches none of llama.cpp's 76 fingerprint
literals, so without the builder every `open(prove: true)` throws
`templatingFailed`). An unknown template that is also not Gemma-4 retries the
built-in `"gemma"` name once, then throws `templatingFailed(code, firstLine)` —
the rejected template names itself in the error. The app never templates, it only
hands the two strings (the Android `ConversationConfig(systemInstruction:)` +
`sendMessage` split).

## Grammar (constrained decoding)

`AskOptions.grammar` takes **GBNF**, not a JSON schema. llama.cpp's
json-schema→GBNF converter lives in its `common` library, which the pinned
xcframework does not include (only `llama`). If the app wants schema-constrained
extraction later, it must convert the schema to GBNF itself (a small deterministic
step, testable on Linux) or drop the grammar and rely on `LlmProof`-style checking.
The proofs run without a grammar.

## Model install layout

`Application Support/models/<spec.subdir>/<spec.files[0].name>` — the same
subdirectory-per-model shape as the ASR package and Android's
`getExternalFilesDir(null)/models/…`, so a restore across platforms keeps the same
paths inside the vault.

Free-space gate: the model size plus the ASR lane's margin (256 MB) — the Q8_0 build
is 4.97 GB and the E4B Q4_0 4.59 GB, so check before offering them in settings.

## Registry pins

Pinned by exact byte size + sha256 (the HF LFS oid), verified at download before use.
Re-checked 2026-11-16 against the `ggml-org` HF repos; the same sizes the docs lane
recorded in `scratchpad/ios/PLAN.md` (2.84 / 4.97 / 4.59 GB).

- `gemma-4-e2b-q4_0` (2.84 GB) — the default; the variant+quant the CPU benchmark ran
  (`bench/bench.py --model e2b --quant Q4_0 --llama-build b11960`).
- `gemma-4-e2b-q8_0` (4.97 GB) — higher quality; load only on devices with the memory.
- `gemma-4-e4b-q4_0` (4.59 GB) — the Android Quality-ladder counterpart.

Ids carry the quant (a different quant is a different file with different memory
needs); Android's ids `gemma-4-e2b`/`gemma-4-e4b` name the `.litertlm` builds. The
GGUFs are `ggml-org` conversions of the same Gemma 4 weights under the Gemma licence.

## Name collision (known, for the integrator)

This package and MindVaultASR both export `ModelFile`, `ModelSpec`, and `ModelRegistry`
(same names as Android's `models/ModelSpec.kt`, which both engines share). An app file
that imports both packages must qualify (`MindVaultLLM.ModelSpec` vs
`MindVaultASR.ModelSpec`) — or the packages get renamed later by the integrator, in
which case this file and the ASR `INTEGRATION.md` both say so. Renaming is the
integrator's call, not this lane's.

The same collision class, one level deeper: this package's `Segment` duplicates core's
`TranscriptSegment` (`ios/CONTRACTS.md` shared contracts), and `SpeakerMaps`/`Role`
repeat Android's data types too. Moving them to `MindVaultCore` is the integrator's
decision — deliberately NOT done in this PR (the review asks for it as a note only).

## Platform notes

- The engine target `MindVaultLLMLlama` and the `llama` binary target are compiled only
  under `#if !os(Linux)` — the Linux CI tests the pure logic (`LlmProof`, `PromptStore`,
  `PromptBudget`, `ModelRegistry`, …) in the `swift:6.3` image. **The macOS/iOS compile
  of the engine side has not been exercised in this lane** (no Apple machine here);
  the wiring lane's first CI run must compile it for iOS before anything else.
- **The pinned xcframework has no simulator slice**: only `ios-arm64` (device,
  minOS 16.4) and `macos-arm64_x86_64` (13.3) — no `ios-arm64-simulator`. Any simulator
  build of a target depending on `MindVaultLLMLlama` fails at link; device-only.
- The runner serializes all work on one private queue; `onToken` fires on that queue, so
  the app hops back to the main actor for UI. Calls are synchronous and throwing.
  Dropping the runner without `close()` is safe too: `deinit` frees the model and
  context directly (no queue sync — the last reference can be released on the runner's
  own queue, where a sync would deadlock).
- Errors carry no reply text (`LlamaError`) — an inference error can quote the prompt,
  and prompts hold client material (the Android rule).
