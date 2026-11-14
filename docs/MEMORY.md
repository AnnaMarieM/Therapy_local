# Long-term memory

A journal that remembers for you, on the phone, with Gemma 4 (E2B or E4B, LiteRT-LM) and EmbeddingGemma. Each client has a list of facts: a **heading** (about 6 words) and **content** (1-3 short factual sentences, no interpretation or advice). Every fact keeps a verbatim quote and the transcript segment it came from, so tapping it shows the conversation around it, plus every later passage, in any session, where it came up again. The app gives no insights. It writes one thing besides the facts: a short summary of a session in a clinical format the therapist picks in Settings (SOAP by default, or DAP, BIRP, a plain narrative note; one id in `SummaryConfig`, part of the prompt version), made from its transcript and roles and shown for a client's last session ([AD-12](AD/AD-12.md), proposed, not yet decided). Otherwise it shows only the facts and each session's full transcript.

Facts are append-only: a fact that changes over time is not updated. A stale fact is superseded by a rebuild, never edited ([AD-10](AD/AD-10.md)).

## One database
Everything lives in `mind.db` (SQLCipher, see [SECURITY.md](SECURITY.md)), opened once per process by `MindStore.get` after unlock, with WAL and `foreign_keys=ON`. Every foreign key cascades on delete. There are no schema migrations: a database of another schema version is wiped and recreated ([AD-11](AD/AD-11.md)).

| Table | Holds |
|---|---|
| `clients(id, name, created_at)` | |
| `sessions(id, client_id, started_at, ended_at)` | |
| `recordings(id, session_id, started_at, engine, model, chunk_seconds)` | A recording until its session is processed |
| `chunks(recording_id, idx, start_ms, end_ms, overlap_ms, text)` | Raw speech-recognition output per audio chunk; `text` is empty until transcribed |
| `transcripts(session_id, version, sha256, recording_id, audio_sha256, created_at)` | The raw transcript as append-only versions: `sha256` is the hash of the segments, `audio_sha256` the hash of the audio they came from ([AD-10](AD/AD-10.md)) |
| `segments(session_id, version, idx, start_ms, end_ms, text, speaker)` | The segments of one transcript version; the current transcript is the session's highest version. Derived from `chunks` or the diarized turns |
| `windows(session_id, idx, seg_from, seg_to, text, embedding, embed_model, prov_*, stale)` | About 1000-token windows of the **role-labelled dialogue** (`[hh:mm:ss]` line, then `Therapist:` / `Patient:` and the words, as the screen shows it), cut on turn boundaries and starting with the preceding turns as context, for extraction and search. A window carries the labels of the speaker map in its `prov_speaker_map_version` |
| `facts(id, client_id, session_id, heading, content, evidence, seg_idx, seg_to, start_ms, end_ms, speaker, scope, category, embedding, created_at, prov_*, stale)` | The fact's first occurrence. `seg_idx`..`seg_to` are the segments its quote runs over (what a click on the fact jumps to), `start_ms`..`end_ms` their time range, `speaker` the raw diarization id of the first one (null: not diarized). The role is **not** stored: it is resolved from the session's current speaker map when the fact is read (D12). `embedding` is of `DOC_PREFIX + heading + ". " + content`. The provenance (`prov_*`) and `stale` are for the rebuild and never shown |
| `mentions(id, fact_id, session_id, evidence, seg_idx, seg_to, start_ms, end_ms, speaker, created_at)` | A later session where the fact came up again (never the fact's own session); same span and speaker fields as a fact, so a mention can take over as the fact (D2) |
| `extract_cache(session_id, idx, key, json)` | Extraction result per window, keyed by prompt version and model |
| `review_cache(session_id, cand_key, verdict)` | The LLM's duplicate verdict per candidate |
| `session_summaries(session_id, text, method, created_at, prov_*, stale)` | The session's summary and its provenance (AD-10 columns); stale when the transcript, the roles, the prompts or the model changed since ([AD-12](AD/AD-12.md)) |
| `segment_translations(session_id, version, idx, lang, text, model, prompt_version)` | The English transcript, one row per segment of a transcript version ([AD-17](AD/AD-17.md)); same idx and times as `segments`; gone with the transcript |
| `display_translations(session_id, source_sha, lang, text, model, prompt_version, created_at)` | What the screens show (fact headings and contents, summary sections) in the app language, found by the hash of the English text; gone with its session ([AD-17](AD/AD-17.md)) |
| `jobs(id, kind, session_id, recording_id, ref, status, attempts, max_attempts, error_code, created_at, updated_at)` | The job queue. `error_code` is a short code, never raw error text |
| `job_deps(job_id, depends_on)` | Job dependencies |

`windows` and `facts` (and a session summary later) carry the same provenance columns `prov_transcript_sha`, `prov_speaker_map_version`, `prov_prompt_version`, `prov_model` and the flag `stale` (`Provenance`, `MindStore.refreshStale`). A row is stale when its transcript, speaker map (roles are in the extraction input, AD-14), prompt or model is not the current one.

Deleting a client removes everything of it, jobs included. Deleting a session removes its rows; a fact first seen in it moves to its next mention (D2).

## Job graph
All processing runs as jobs in `mind.db`, so it survives a crash or a lock (`jobs/`).

```
transcribe(recording) -> translate(session) -> roles(session) -> window(session) -> extract(session, 1..n) -> commit(session) -> cleanup(recording)
```

`translate` ([AD-17](AD/AD-17.md)) puts the transcript into English for the jobs after it and does nothing for an English client or with the setting `processing_language` = `native`; `roles` ([AD-14](AD/AD-14.md)) is described in its record. `localize` (AD-17) is outside the chain: queued at the end of `commit` and `summary`, it translates what is shown into the app language.

1. **transcribe** fills in the chunks that have no text yet, with Canary 1B v2, the only speech recogniser ([AD-02](AD/AD-02.md)). `SegmentBuilder` then builds the segments from the overlapping chunks, keeping their timestamps, and `appendTranscript` stores them as the next transcript version with the hash of the audio (the same content again writes nothing).
2. **window** cuts the segments into windows (1000 tokens, 50 overlap, estimated at 1.4 tokens per word), embeds them with `DOC_PREFIX` and adds the rest of the graph. A window over the embedder's limit is embedded in halves and averaged.
3. **extract(i)** asks Gemma for the facts the patient states or confirms in window *i*, a dialogue with the roles as labels (`ExtractionDialogue`, `TranscriptWindows.byTurns`): one JSON object per line, twice, then a constrained schema as a last resort. A fact is kept only if its evidence quote occurs in the words of the window's segments (never in a label or a time); the quote is mapped to the segments it runs over (first to last word). One sentence can give several facts, each with its own quote; repeats are merged, facts that only share a sentence are kept (`FactParser.dedupe`). The cache key is prompt version, model, speaker-map version and window text, so a roles swap extracts that session again.
4. **commit** has two phases. Phase A, outside any transaction, is `CommitPlanner`: for each candidate in order, the nearest facts of the client's earlier sessions with cosine at least 0.6 are shown to Gemma, which answers whether it is a duplicate. No neighbour above the floor, or an unreadable reply twice, means a new fact. A candidate repeating a fact of an earlier session becomes a mention; one repeating a fact this session already adds is dropped. Phase B is one short transaction that replaces the session's facts and mentions with the plan.
5. **cleanup** moves the encrypted audio (`PendingAudio`) to `retained/<session>/<recording>` and deletes the recording row; its chunks go with it, the transcript versions stay. The retained audio is deleted after `PendingAudio.RETENTION_DAYS` (7) days, or at once with its session, its client or a vault erase ([AD-10](AD/AD-10.md)). Audio leaves the pending folder only here, after everything else is stored.

6. **summary** ([AD-12](AD/AD-12.md)) is queued behind `window` of a new session and on demand; it is not a dependency of `commit`. It writes the session's summary with the configured method (`SummaryConfig`, map-reduce over the windows by default) and stores it in `session_summaries` with the same provenance columns as windows and facts. A roles swap makes it stale (new speaker-map version) and queues it again.

Every handler can run again and ends in the same state: derived results are written whole (superseded: old generation out, new one in, one transaction) and the transcript only grows by a version when its content changed and LLM answers are cached under the prompt version (a short hash of the resolved prompt text) and the model.

Queue rules (`JobQueue`):
- A job is claimed only when all its dependencies are done and its kind may run now: the models it needs are downloaded (`ModelSetup.isReady`) and no session is recording (D3).
- A failure counts an attempt; after 3 the job stays failed and its dependents show as blocked until Retry. Stop puts the running job back without counting an attempt. A job whose session was deleted is cancelled, not failed.
- At startup running jobs go back to pending, and a recording with no job gets its jobs queued.
- `QueueSnapshot` (done/total per session, the running step, failed and blocked counts) drives the processing notice and the notification ("session n of N, step n of N").

`MemoryService` runs `JobRunner.drain()` in a foreground service with a wake lock. The runner keeps the models it loaded (`ModelPool`) across jobs and closes them when the queue is empty.

## Decisions
- **D1. One transcription path.** While recording, every audio chunk is saved encrypted (`PendingAudio`) with an empty `chunks` row. All speech recognition runs in the `transcribe` job after the session. There is no live transcript, and a crash mid-session loses no audio.
- **D2. Deleting a session that holds a fact's first occurrence.** The fact's earliest remaining mention (by session start) becomes the fact and takes over that mention's session, evidence and segment. A fact with no other mention is deleted. One transaction in `MindStore.deleteSession`.
- **D3. The runner pauses while a session is recording.** No new job is claimed; a running job finishes first. This keeps the LLM, ASR, embedder and live cues from being loaded together, which with E4B would run out of memory.
- **D4. A client's sessions are committed strictly in order of their start.** `commit(S)` waits for every unfinished `window`, `extract` and `commit` job of the client's previous session, so S is planned only against facts that are complete. Committing a session again (retry, reprocess) queues the commits of all its later, already committed sessions again, in order, since their mentions may point at its facts.
- **D5. The CI emulator runs debug builds with an `x86_64` ABI added** (debug and androidTest only). The end-to-end tests use fake `Llm`, `Embed` and `Asr` ports and never load the LiteRT or sherpa native libraries.
- **D6. Model inference runs in separate processes** (`:live` for live cues and search, `:batch` for the session pipeline). They are stateless model servers; the queue, vault, database, ladder and guard stay in the app process, so a model crash never closes the app. Full record: [AD-01](AD/AD-01.md).
- **D7. One model per role: Canary 1B v2 is the only speech recogniser** (chosen for its Czech). Gemma 4 is text only and processes the transcribed chunks, and EmbeddingGemma 2 still embeds audio. Whisper, Parakeet and Gemma speech recognition are removed. Full record: [AD-02](AD/AD-02.md).
- **D14. Canary stays the speech recogniser because of its Czech; Whisper is not added back.** On the real-voice corpus Canary beats Whisper small on both Czech sessions (Jana 23.5% against 35.1%, Tomáš 36.2% against 43.7%) and loses on English (about 3 to 5 points), which is tracked as a fix to the token leak and merged turns, not as a second engine. Full record: [AD-18](AD/AD-18.md).
- **D13. All processing runs in English; only what is shown is translated** (the transcript is translated in by a `translate` job, facts and summaries are stored in English and translated out by a `localize` job, the setting `processing_language` keeps the old path). Full record: [AD-17](AD/AD-17.md) (draft, not decided).
- **D9. Inputs are immutable and versioned; every derived item records what it came from.** The raw transcript is append-only by version and content hash, audio is referenced by hash while it is retained (7 days), the speaker map is append-only. Windows and facts carry transcript hash, speaker-map version, prompt version and model and a stored stale flag; a rebuild supersedes stale items in client and session order. One vault key and one database stay. Full record: [AD-10](AD/AD-10.md) (proposed).
- **D10. No migrations and no compatibility code before the first release.** The schema is one create path; a database of another version is wiped. Full record: [AD-11](AD/AD-11.md) (proposed).
- **D8. An engine host that crashes exits quietly** (native signal handler `_exit(100 + signal)`, Java crash `halt(13)`), so the system shows no "keeps stopping" dialog and writes no tombstone for a background host. Full record: [AD-04](AD/AD-04.md).
- **D12. Facts are extracted from the labelled dialogue, store who said them, and are patient-only by default** ([AD-14](AD/AD-14.md), amends AD-10 point 5). The patient's answer needs the therapist's question, so extraction reads the role-labelled dialogue. Roles are therefore in the input: a roles swap makes the session's windows and facts stale (the speaker-map version is compared) and a rebuild extracts again. Each fact stores the raw speaker id and its segment and time range; reads resolve the role from the current map and return only the patient's facts (and those whose speaker cannot be resolved) unless `includeTherapist = true`, which only the pipeline (duplicate check, categories) and the debug export use.
- **D11. The app writes one summary per session** (SOAP, short paragraphs, from the transcript with the confirmed roles), as a `summary` job on the same queue and model; it is stale after a roles swap and rebuilt. This reverses "writes no summaries" (accepted). Full record: [AD-12](AD/AD-12.md).
- **D9. The NPU runs only on SoCs validated on our own hardware** (each recorded in the model catalogue, `ModelRegistry.BACKEND_BUILDS`); every other device uses the GPU, then the CPU. Full record: [AD-09](AD/AD-09.md).
- **D13. Gemma E2B runs on the GPU with FLOAT32 activations, on every device** (`activationDataType = FLOAT32` for the GPU backend only; no device list). The default GPU precision fails the second output proof on the Pixel 8 and the Galaxy Tab S7; FLOAT32 passes on all three test devices with no speed cost. E4B is not covered until it is measured on the smaller devices. Full record, with the benchmarks: [AD-19](AD/AD-19.md).
- **D14. The human-voice test recordings are synthetic (scripts written by Claude) and may be published**: repo, CI artifacts, cloud runners. Real recordings stay out. Full record: [AD-20](AD/AD-20.md).
- **D15. On-device GPU and NPU inference uses the same model as the CPU**: phones and handhelds have unified memory, so the backend is chosen for speed, never to shrink the model. Full record: [AD-21](AD/AD-21.md).

## Live cues
During a session `LiveCues` embeds the last seconds of audio directly (EmbeddingGemma audio, no transcript) and compares them with the client's fact embeddings. Scores are averaged over ticks per fact, and the top 10 above the floor are shown by heading. Audio stays in an in-memory ring of `LiveCueConfig.MAX_LOOKBACK_S` (327 s) and is never written. Settings are read at session start, from "Knobs for nerds" in the settings (`LiveCueSettings`, values clamped):

| Setting | Default | Range |
|---|---|---|
| Interval | 2000 ms | 1000-10000 ms |
| Lookback | 20 s | 5-327 s |
| Smoothing (weight of the newest tick) | 0.5 | 0.05-1.0 |
| Score floor | 0.0 | 0-1 |

A tick that takes longer than the interval is followed by the next one after 250 ms; the effective interval is logged. The cap comes from the embedder's context: `EmbeddingConfig.MAX_TOKENS` is 8192, the model's full context and one of the LiteRT build's input sizes, and audio costs 25 tokens per second (google/embeddinggemma-2 model card, "Context Limits"). That leaves about 327 s once a small margin is reserved for special tokens. If the model reports a smaller context, each tick embeds only as much audio as fits (`Embedder.maxAudioSeconds`).

**The engine is opened at the input size the use needs, not at 8192.** The model's cap is `EmbeddingConfig.MAX_TOKENS` (8192), but the engine's graph and buffers (and so its GPU memory) are sized for the input length it is opened with, and the sizes the build has are `EmbeddingConfig.INPUT_SIZES` (128 to 8192, powers of two). So the live (audio) embedder opens at the smallest size that holds the Settings lookback and a 20 s voice search query (`EmbeddingConfig.liveInputTokens`: 20 s is 516 tokens, so 1024; up to 81 s 2048; up to 163 s 4096; above that 8192), and the pipeline's text embedder in `:batch` at 2048 (`PIPELINE_INPUT_TOKENS`: a window is about 1050 estimated tokens, and `Pipeline.embedFitting` splits a text that still does not fit). When the lookback is changed in Settings a loaded embedder reloads at the new size (`Embedder.ensureInput`, from `Engines.onLiveCueSettingsChanged`, or at the next start of the cues if a session is being recorded); `maxAudioSeconds` follows what was loaded. The GPU memory gate follows the size too (`MemoryConfig.embedGpuCostBytes`): measured on the Galaxy Tab S7 (Adreno 650) the fall in available memory while loading was about 110 MB at 1024, 250 MB at 2048, at least 1.5 GB at 4096 (lmkd killed `:live` at the low watermark) and at least 2.2 GB at 8192 (same kill). At 1024 the tablet runs the embedder on the GPU: text 102 ms against 124 ms on the CPU, cosine to the CPU vectors 0.9974-0.9984, no lmkd activity. The audio encoder stays on the CPU.

## Search
The client profile has a search field: the query is embedded with `QUERY_PREFIX` and matched against the client's windows, and the facts are matched by heading and content. A result opens the transcript there. Tapping a fact in the profile opens its first occurrence in the same way, from the stored segment range and times (`FactJump`, AD-14): scrolled to its lines and highlighted until a line is tapped; later mentions are listed and open at their own lines; if the stored times no longer match the transcript (a newer version) nothing is highlighted and the stored time is scrolled to. Holding the mic button records up to 20 s; the audio embedding is searched directly, without transcription.

## Model choice
`ModelChoice` resolves the `gemma_variant` setting to Gemma 4 E2B (default) or E4B. The settings suggest E4B on phones with about 12 GB of RAM or more (our own threshold), show the download size, ask before downloading and offer to remove the unused model. While the new model downloads, jobs that need it wait instead of failing.

## Accelerators and fallback
Every model is loaded through `BackendLadder` (`models/BackendLadder.kt`) over the accelerators in `models/Accelerators.kt` (built in: NPU, then GPU, then CPU). The ladder keeps the first backend that loads *and* passes a test (on an accelerator the LLM passes two proofs, see "Bad output fails the proof" below; the CPU is kept without them; the embedder runs its similarity self-test). Nothing a backend throws escapes: it is closed and the next one is tried. The CPU is always last, also when a backend is forced from the developer screen (`BackendPolicy.order`). The Sherpa recogniser (Canary) goes through the same ladder: the provider asked for (CPU in the app, NNAPI in the evaluations), then the CPU.

A backend is skipped without being tried when:
- its runtime is missing (NPU: `libLiteRtDispatch_Qualcomm.so`, see `NpuRuntime`);
- (free memory is **not** a reason: the GPU and NPU are tried every time, [AD-08](AD/AD-08.md). No device name, SoC or total-RAM tier takes part in any choice either; they are only logged, in `accelerator_profile`. Whether a phone can really run a GPU is what the guard learns by trying, in the engine process, AD-01);
- this phone has blocked it (below).

**What this phone has shown it can do** (`BackendGuard`, kept in the `backend_guard` preferences with synchronous writes):
- **Notes on every load.** Before a load starts its record says `attempting`. Before each native step (`create`, `initialize`, `test`) the step is noted and the log is flushed to disk. After the test passes, the record says `proven on this device`. Several loads can be open at once and each is tracked.
- **Strikes from caught failures.** A caught load failure is a strike.
- **Strikes recovered on the next start** (`Backends.recoverAtStartup`), for what cannot be caught:
  - An attempt still open counts if the system killed the process for memory, it crashed natively, or it hung (ANR). The reason names the step it died in.
  - An exit that may not be the backend's fault (a Java crash elsewhere, an unknown reason) counts only after it happens twice in a row during the same attempt. The user swiping the app away never counts.
  - A GPU/NPU engine that was loaded counts if the foreground app was killed for memory, crashed natively or hung.
- **Deaths demote.** A GPU/NPU whose host died while loading or loaded is struck and, besides, not tried again in this run (`demoted`), so a tablet session cannot lose `:live` once per load until the block threshold.
- **Bad output fails the proof.** The embedder's self-test compares its vectors for fixed inputs with the CPU reference vectors of the same model file (`assets/embed_golden.json`, `EmbedGolden`, cosine at least `EmbedGolden.MIN_COSINE`); a mismatch is a failed load, so a strike. The LLM proofs run on accelerators only. The CPU is the reference and the last resort: it loads with no generation at all, and `memory_llm_engine_test_reply` carries `proof_skipped=fallback`. Measured 2026-11-07, the CPU proofs cost 22 s per load on a Pixel 8, 10 s on a Galaxy Tab S7 and 7 to 8 s on the POCO, and a failed CPU proof could only log, so they were dropped there. A failed proof on GPU or NPU still throws and strikes the backend; the accelerator proofs are unchanged. The first LLM proof (`engine/runner/LlmProof.kt`, called from `LlmRunner.proveOutput`) asks for a JSON array of four `{kategorie, text}` objects and fails the backend on: `empty_reply`, `invalid_json`, `duplicate_key`, `wrong_count`, `wrong_keys`, `empty_value`, `missing_fact:<n>`, `wrong_category:<n>`. The second proof (accelerators only) uses the app's own kind of instruction: `LlmProof.SYSTEM2` ("one JSON object per line with keys heading, content, evidence (an exact quote)") on three made-up sentences (`LlmProof.checkFacts`, `LlmRunner.proveFacts`). It needs at least three objects with three distinct keys each, no repeated key, no empty value, and a last value that is a quote of the text, each quote once; it does not depend on the key names (the CPU writes Klíč/Obsah/Důkaz). *Why a second one (issue #48, measured 2026-11-07 on the SM-T970, Adreno 650, LiteRT-LM 0.18.1):* the first proof passed the generic Gemma file on that GPU although the app's extraction instruction came back as ONE merged object with a repeated key. Length is not the trigger (the second proof is 115 tokens of prompt, the first 156); the instruction style is. The second proof fails that GPU (`too_few_objects`, 2 of 2) and the `-gpu` file on the GPU (`invalid_json`, 2 of 2), and passes the CPU (2 of 2) and the phone GPU. It adds about 6 s to a passing accelerator load on the phone and about 11 s on the tablet GPU. Rule: never weaken either proof to get a pass; if a backend fails, the ladder strikes it.
- **Prompt failures.** A loaded engine that fails on 2 real prompts in a row gets a strike and is demoted for the rest of the run. Its `Llm.healthy` turns false and `ModelPool` reloads it on the next backend. A single bad prompt is only noted.
- **When a backend is blocked:**
  - never proven on this build: after 2 strikes;
  - proven: after 3, and it gets one more try after 7 days.
- **Clearing strikes.** Strikes are cleared when the app unloads the engine itself (it survived its work), not when it merely loads.
- **The CPU.** It is never blocked. If a model keeps getting killed even on the CPU, `model_too_large_for_device` is logged.
- **Updates and reset.** An app or system update forgets everything. **Test everything again** forgets the records but keeps attempts that are in flight.

**Adding an accelerator** (a vendor NPU, an ONNX execution provider, a chip one model was compiled for) is one registration in `Application.onCreate`, plus its runtime binding:

```kotlin
Accelerators.register(Accelerator(id = "MTK_APU", priority = 50, models = setOf("gemma-4-e2b"), litert = true,
    available = { ctx -> if (File(ctx.applicationInfo.nativeLibraryDir, "libLiteRtDispatch_MediaTek.so").exists()) null else "dispatch library missing" }))
LiteRtBindings.bind("MTK_APU") { ctx -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir) }
// or, for the Sherpa/ONNX models: Accelerator(id = "QNN_HTP", priority = 80, onnxProvider = "qnn")
```

- `priority` sets the order; lower is tried first (NPU 100, GPU 200, CPU last).
- `models` limits it to the models it serves; null serves every model.
- From that one registration the new chip gets everything else: the ordered try with the CPU behind it, the attempting / proven / blocked history per device, the **On this device** settings rows, the debug override chip, and every log line.

The settings screen shows all of this under **On this device**, followed by one row per engine process (`:live`, `:batch`): running, stopped, starting, stopping, restarting after a crash, or unavailable after repeated crashes; pid, generation and uptime; the models loaded and their backend; the host's memory (PSS, refreshed once per resume, off the main thread, only while the host is bound); recent crashes and the last exit (kind and time). It reads `Engines.state` and never starts a process. The ladder and guard stay in the main process, so the **On this device** rows describe the same view as before: per model, what it runs on and each backend's state with the technical reason, and **Test everything again** (`Backends.reset`). QA probes (`MemProbeActivity`) ignore blocks but still record their outcome.

The embedder is never closed underneath a running call: `Embedder.close()` takes the lock or, if a call is in flight, defers the close to the end of that call. While sessions are processed it is suspended and `load()` does nothing, so it never shares the GPU with the LLM. Leaving the app closes it; real memory pressure (`RUNNING_CRITICAL`, `COMPLETE`, `onLowMemory`) unloads it too, and the next load is not gated by free memory (AD-08; there is no timed CPU-only period any more). The pipeline's own text embedder is no longer pinned to the CPU: `ModelPool` unloads the LLM before loading it. If loading Gemma with its audio encoder fails, speech recognition falls back to Canary (`ModelPool.asr`). The Sherpa recognisers decode one second of silence before they count as proven. NNAPI is registered but opt-in (`auto = false`).

**Where it runs.** The ladder runs in the main process; one attempt is one `Open` call on the engine process of the model's role (`:live`: EmbeddingGemma with audio; `:batch`: Gemma, the text embedder and Canary), so a GPU driver crash or a kill by the low-memory killer ends only that process ([AD-01](AD/AD-01.md)). The main process reads the exit reason of that pid from `ApplicationExitInfo`, logs `engine_died`, restarts the host with backoff and the ladder goes on to the next backend. `:live` is shut down before any `Open` in `:batch`, and a host with no client stops after the idle timeout. `ENGINE_IN_PROCESS` (build config) puts every model back in the main process through `LocalEngineHost`, as an emergency switch. Events and how to read them: [LOGGING.md](LOGGING.md#engine-processes-ad-01).

**Forcing one backend for testing (debug and QA builds only).** `BackendPolicy.override` with `BackendPolicy.force = true` skips the guard's blocks and demotions and tries exactly that accelerator, so a phone's real failure can be provoked. The engine process may die; the main process must not. `MemProbeActivity` sets it with `--ez force true` (see [LOGGING.md](LOGGING.md#debug-override-and-force)). No release path sets it.

*To revisit after the strike, deferral and quiet-pause steps land:* the "Strikes recovered on the next start" list (the host's death now strikes at once), and the pause of live cues and search while `:live` is shut down.

## Developer harness (debug builds)
- Put `job.json`, `prompts/*.txt` (from `demo/prompts/`) and text transcripts in `in/` under `/sdcard/Android/data/com.therapylocal.app/files/memory/`. `job.json`: `{"resetProfiles": false, "stripLabels": false, "sessions": [{"file": "<client>_<n>.md", "date": "yyyy-MM-dd"}]}`. A prompt file there overrides the one shipped in the app.
- Unlock the app, then start from the Memory tab or `adb shell am start -n com.therapylocal.app/.MainActivity --ez memory_lab true`. Each transcript becomes a session whose segments are its non-blank lines, queued from the window job on. `out/DONE` marks the end of the run. Use synthetic transcripts only.

## Findings so far (Snapdragon 8 Elite, GPU; the NPU is not available, the Qualcomm dispatch library is not in the app)
- Prefill about 1,200-2,000 tok/s, decode about 37 tok/s unconstrained, about 17 tok/s constrained.
- Schema-constrained decoding of the large extraction schema looped on whitespace; small schemas (the review answer) work.
- A review call takes about 6 s wall although prefill is 0.3 s: fixed overhead still to be located.
- Unrelated facts can score 0.75-0.89 cosine, so cosine alone does not decide duplicates; the 0.6 floor only skips the LLM for clearly new facts.
- Still to measure on a device: embed latency at 20 s, 60 s and 327 s lookback, text-window embed latency with the 8192 input size, Gemma E4B speech recognition, and memory use with E4B.
