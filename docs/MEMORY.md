# Long-term memory

A journal that takes notes for you, on the phone, with Gemma 4 (E2B or E4B, LiteRT-LM) and EmbeddingGemma. Each client has a list of facts: a **heading** (about 6 words) and **content** (1-3 short factual sentences, no interpretation or advice). Every fact keeps a verbatim quote and the transcript segment it came from, so tapping it shows the conversation around it, plus every later passage, in any session, where it came up again. The app gives no insights and writes no summaries. A session's meeting notes are its facts, its transcript and the therapist's own notes.

Facts are append-only: a fact that changes over time is not updated.

## One database
Everything lives in `mind.db` (SQLCipher, see [SECURITY.md](SECURITY.md)), opened once per process by `MindStore.get` after unlock, with WAL and `foreign_keys=ON`. Every foreign key cascades on delete. Opening it deletes the legacy `recordings.db` and `memory.db`; their data is not migrated.

| Table | Holds |
|---|---|
| `clients(id, name, created_at)` | |
| `sessions(id, client_id, started_at, ended_at, observations)` | `observations` are the therapist's notes |
| `recordings(id, session_id, started_at, engine, model, chunk_seconds)` | A recording until its session is processed |
| `chunks(recording_id, idx, start_ms, end_ms, overlap_ms, text)` | Raw speech-recognition output per audio chunk; `text` is empty until transcribed |
| `segments(session_id, idx, start_ms, end_ms, text)` | The session's transcript with timestamps, derived from `chunks` (rebuilt whole) |
| `windows(session_id, idx, seg_from, seg_to, text, embedding, embed_model)` | About 1000-token windows over the segments, for extraction and search |
| `facts(id, client_id, session_id, heading, content, evidence, seg_idx, embedding, llm_model, prompt_version, created_at)` | The fact's first occurrence. `embedding` is of `DOC_PREFIX + heading + ". " + content`. `llm_model` and `prompt_version` are for debugging and never shown |
| `mentions(id, fact_id, session_id, evidence, seg_idx, created_at)` | A later session where the fact came up again (never the fact's own session) |
| `extract_cache(session_id, idx, key, json)` | Extraction result per window, keyed by prompt version and model |
| `review_cache(session_id, cand_key, verdict)` | The LLM's duplicate verdict per candidate |
| `jobs(id, kind, session_id, recording_id, ref, status, attempts, max_attempts, error_code, created_at, updated_at)` | The job queue. `error_code` is a short code, never raw error text |
| `job_deps(job_id, depends_on)` | Job dependencies |

Deleting a client removes everything of it, jobs included. Deleting a session removes its rows; a fact first seen in it moves to its next mention (D2).

## Job graph
All processing runs as jobs in `mind.db`, so it survives a crash or a lock (`jobs/`).

```
transcribe(recording) -> window(session) -> extract(session, 1..n) -> commit(session) -> cleanup(recording)
```

1. **transcribe** fills in the chunks that have no text yet, with the engine chosen in the settings (Whisper, or Gemma on the LLM's engine; a Gemma build without audio input falls back to Whisper). `SegmentBuilder` then rebuilds the segments from the overlapping chunks, keeping their timestamps.
2. **window** cuts the segments into windows (1000 tokens, 50 overlap, estimated at 1.4 tokens per word), embeds them with `DOC_PREFIX` and adds the rest of the graph. A window over the embedder's limit is embedded in halves and averaged.
3. **extract(i)** asks Gemma for the facts of window *i*: one JSON object per line, twice, then a constrained schema as a last resort. A fact is kept only if its evidence quote occurs in the window; the quote is mapped to the segment of its first word.
4. **commit** has two phases. Phase A, outside any transaction, is `CommitPlanner`: for each candidate in order, the nearest facts of the client's earlier sessions with cosine at least 0.6 are shown to Gemma, which answers whether it is a duplicate. No neighbour above the floor, or an unreadable reply twice, means a new fact. A candidate repeating a fact of an earlier session becomes a mention; one repeating a fact this session already adds is dropped. Phase B is one short transaction that replaces the session's facts and mentions with the plan.
5. **cleanup** deletes the encrypted audio (`PendingAudio`) and the recording row; its chunks go with it, the segments stay. Audio is deleted only here, after everything else is stored.

Every handler can run again and ends in the same state: results are written whole (delete, then insert) and LLM answers are cached under the prompt version (a short hash of the resolved prompt text) and the model.

Queue rules (`JobQueue`):
- A job is claimed only when all its dependencies are done and its kind may run now: the models it needs are downloaded (`ModelSetup.isReady`) and no session is recording (D3).
- A failure counts an attempt; after 3 the job stays failed and its dependents show as blocked until Retry. Stop puts the running job back without counting an attempt. A job whose session was deleted is cancelled, not failed.
- At startup running jobs go back to pending, and a recording with no job gets its jobs queued.
- `QueueSnapshot` (done/total per session, the running step, failed and blocked counts) drives the processing notice and the notification ("session n of N, step n of N").

`MemoryService` runs `JobRunner.drain()` in a foreground service with a wake lock. The runner keeps the models it loaded (`ModelPool`) across jobs and closes them when the queue is empty; Gemma speech recognition shares the LLM's engine, so one Gemma file is never loaded twice.

## Decisions
- **D1. One transcription path.** While recording, every audio chunk is saved encrypted (`PendingAudio`) with an empty `chunks` row. All speech recognition runs in the `transcribe` job after the session. There is no live transcript, and a crash mid-session loses no audio.
- **D2. Deleting a session that holds a fact's first occurrence.** The fact's earliest remaining mention (by session start) becomes the fact and takes over that mention's session, evidence and segment. A fact with no other mention is deleted. One transaction in `MindStore.deleteSession`.
- **D3. The runner pauses while a session is recording.** No new job is claimed; a running job finishes first. This keeps the LLM, ASR, embedder and live cues from being loaded together, which with E4B would run out of memory.
- **D4. A client's sessions are committed strictly in order of their start.** `commit(S)` waits for every unfinished `window`, `extract` and `commit` job of the client's previous session, so S is planned only against facts that are complete. Committing a session again (retry, reprocess) queues the commits of all its later, already committed sessions again, in order, since their mentions may point at its facts.
- **D5. The CI emulator runs debug builds with an `x86_64` ABI added** (debug and androidTest only). The end-to-end tests use fake `Llm`, `Embed` and `Asr` ports and never load the LiteRT or sherpa native libraries.

## Live cues
During a session `LiveCues` embeds the last seconds of audio directly (EmbeddingGemma audio, no transcript) and compares them with the client's fact embeddings. Scores are averaged over ticks per fact, and the top 10 above the floor are shown by heading. Audio stays in an in-memory ring of `LiveCueConfig.MAX_LOOKBACK_S` (60 s) and is never written. Settings are read at session start, from "Knobs for nerds" in the settings (`LiveCueSettings`, values clamped):

| Setting | Default | Range |
|---|---|---|
| Interval | 2000 ms | 1000-10000 ms |
| Lookback | 20 s | 5-60 s |
| Smoothing (weight of the newest tick) | 0.5 | 0.05-1.0 |
| Score floor | 0.0 | 0-1 |

A tick that takes longer than the interval is followed by the next one after 250 ms; the effective interval is logged. The embedder limit is `EmbeddingConfig.MAX_TOKENS` (2048). How many audio tokens a second of speech takes is not measured yet, so the 60 s cap is a constant to revisit after a device measurement.

## Search
The client profile has a search field: the query is embedded with `QUERY_PREFIX` and matched against the client's windows, and the facts are matched by heading and content. A result opens the transcript there. Holding the mic button records up to 20 s; the audio embedding is searched directly, without transcription.

## Model choice
`ModelChoice` resolves the `gemma_variant` setting to Gemma 4 E2B (default) or E4B. The settings suggest E4B on phones with about 12 GB of RAM or more (our own threshold), show the download size, ask before downloading and offer to remove the unused model. While the new model downloads, jobs that need it wait instead of failing.

## Developer harness (debug builds)
- Put `job.json`, `prompts/*.txt` (from `demo/prompts/`) and text transcripts in `in/` under `/sdcard/Android/data/com.therapylocal.app/files/memory/`. `job.json`: `{"resetProfiles": false, "stripLabels": false, "sessions": [{"file": "<client>_<n>.md", "date": "yyyy-MM-dd"}]}`. A prompt file there overrides the one shipped in the app.
- Unlock the app, then start from the Memory tab or `adb shell am start -n com.therapylocal.app/.MainActivity --ez memory_lab true`. Each transcript becomes a session whose segments are its non-blank lines, queued from the window job on. `out/DONE` marks the end of the run. Use synthetic transcripts only.

## Findings so far (Snapdragon 8 Elite, GPU; the NPU is not available, the Qualcomm dispatch library is not in the app)
- Prefill about 1,200-2,000 tok/s, decode about 37 tok/s unconstrained, about 17 tok/s constrained.
- Schema-constrained decoding of the large extraction schema looped on whitespace; small schemas (the review answer) work.
- A review call takes about 6 s wall although prefill is 0.3 s: fixed overhead still to be located.
- Unrelated facts can score 0.75-0.89 cosine, so cosine alone does not decide duplicates; the 0.6 floor only skips the LLM for clearly new facts.
- Still to measure on a device: embed latency at 20 s and 60 s lookback, the embedder at 2048 tokens, Gemma E4B speech recognition, and memory use with E4B.
