# Logging and tracing

Every stage of the app writes structured `key=value` lines, so a recording can be followed end to end and timed.

## Where the lines go

| Sink | How to read it |
|---|---|
| logcat, tag `TL` | `adb logcat -s TL` |
| Rotating file on the phone (1.5 MB x 5) | `/sdcard/Android/data/com.therapylocal.app/files/logs/tl.log`, pulled via `adb pull /sdcard/Android/data/com.therapylocal.app/files/logs/ .` |
| In-app viewer | Logs tab: Refresh, Copy, Share, Clear |

## Line format

```
ts=2026-10-24T13:20:04.509 lvl=I tag=ASR sid=ab12cd rid=3 cid=3.2 evt=span_end span=asr_transcribe dur_ms=1234.5 engine=canary model=canary-1b-v2 backend=CPU audio_sec=20.00 chars=212 rtf=0.06
```

| Field | Meaning |
|---|---|
| `ts`, `lvl` | time, level (D debug, I info, W warn, E error) |
| `tag` | component: `App`, `Audio`, `ASR`, `Embed`, `DB`, `Search`, `Download`, `Models`, `Device` |
| `sid` | session id, one per app process |
| `rid` | recording id (the SQLite row id) |
| `cid` | chunk id, `<rid>.<chunk index>` |
| `evt` | event: `span_start`, `span_end`, `span_error`, or a named event such as `backend_selected` |
| `span`, `dur_ms` | stage name and wall time in milliseconds, on span events |

Values with spaces or `=` are double-quoted.

## Stages that are timed (spans)

| Span | Tag | Notes |
|---|---|---|
| `chunk_cut` | Audio | one chunk cut from the live stream: `idx`, `start_ms`, `end_ms`, `overlap_ms` (all from `ChunkConfig`), `rms` is the signal level |
| `chunk_dropped_short` | Audio | the trailing chunk added too little new audio (`new_ms` against `min_new_ms`) and was not kept |
| `audio_wav_encode` / `audio_to_float` | ASR / Embed | preprocessing: WAV for the audio embedder, float for Canary |
| `model_download`, `model_verify` | Download | bytes fetched, resume offset, sha256 check |
| `asr_load`, `engine_init`, `engine_warmup` | ASR | which backend loaded (`backend`), model file, size; failed attempts are `backend_failed` with `reason` |
| `asr_transcribe` | ASR | `engine`, `model`, `backend`, `audio_sec`, `chars`, `rtf` (wall time / audio time), `chars_per_sec` |
| `embed_load`, `embed_engine_init`, `embed_selftest` | Embed | the self-test logs `sim_related`, `sim_unrelated` and an audio smoke test |
| `embed` | Embed | `modality` (audio or text), `role` (document or query), `backend` (text encoder), `audio_backend`, `dim`, `norm`; audio spans add `audio_sec` and `rtf` |
| `db_create_recording`, `db_insert_chunk`, `db_list_recordings` | DB | row counts and ids; inserts carry `start_ms`, `end_ms`, `overlap_ms` |
| `chunk_pipeline` | App | the whole chunk: embed audio, transcribe, embed transcript, store |
| `search`, `search_scan` | Search/App | `kind` (text or voice), `embed_ms`, `scan_ms`, `rows_scanned`, `top` as `rid.idx:score` (audio vector) and `alt` (transcript text vector, for comparison) |
| `search_collapse` | Search | `raw_hits`, `relevant` (within `SearchConfig.SCORE_MARGIN` of the best), `groups`, `merged` (adjacent chunks merged) |
| `transcript_merge` | App | `removed_words` per chunk: how many words of each 5 s overlap were dropped when showing the whole recording |
| `db_delete_recording`, `db_delete_all` | DB | rows removed and rows left |
| `delete_verified`, `delete_all_verified` | App | counts read back after a delete: `remaining_recordings`, `remaining_chunks`, `audio_files_on_disk` (no audio is ever stored) |

Voice and text queries have no recording id: they carry `cid=q<N>` (one counter per app session). A voice query is embedded as audio and is **never transcribed**, so its trace has an `embed modality=audio role=query` span and no `asr_transcribe`.

Model and backend events (tag `Memory` for the LLM, `Embed` for the embedder, `ASR` for Sherpa; `engine=` names which). Phones differ most here and the field logs are all we get, so every load is logged as one story under one `load_id`:

| Event | Fields |
|---|---|
| `memory_llm_load_request`, `embed_load_request` | model, file size, context, audio, order asked for, debug override, cooldown left |
| `ladder_start` | `candidates`, `history` (every backend's state/strikes/proven before, e.g. `GPU=proven/0/proven_before`), full memory and thermal picture |
| `backend_skipped` | `reason` (missing library, `not enough free memory (… MB free, … MB needed)`, cooldown, Tensor/Mali rule) |
| `backend_skipped_blocked` | the record that blocked it: state, strikes, when, why |
| `backend_attempting` | `note=attempting`, attempt n of m, record before, memory before |
| `backend_phase` | `phase` (create, initialize, test), free memory. Written and **flushed to disk** before each native step, so after a native kill it is the last line. The step is also in the next start's `backend_died_while_loading` |
| `backend_open_slow` | every 30 s while an attempt is still running: `elapsed_ms`, free memory (a hang looks different from a kill) |
| `backend_skipped_demoted` | the backend failed on real prompts earlier in this run |
| `backend_proven` | `note="proven on this device"`, `first_time`, `open_ms`, record after, memory after |
| `backend_failed` (error, with stack trace) | `open_ms`, `error`, `reason`, `cause_chain`, `is_oom`, `next`, record after (strikes, blocked?), memory after |
| `backend_blocked` | the backend is off on this phone from now on |
| `ladder_done` / `ladder_failed` | `summary` (e.g. `NPU=skipped;GPU=failed(IllegalStateException,812ms);CPU=proven(2140ms)`), `total_ms` |
| `memory_llm_unloaded`, `embed_unloaded`, `asr_unloaded` | clean unloads (they clear strikes); `embed_close_deferred` when a call was still running |
| `embed_release_for_memory`, `proactive_embedder_unload` | the system asked for memory back; `cpu_only_for_ms` |
| `llm_call_failed` | an inference error: backend, sizes, `err`, `cause_chain` and `frames` with **types and code locations only** (an inference message can quote patient text), `demoted`, memory and thermal |

Memory fields on these lines come from `DeviceMemory.memorySnapshot` (system available/low/threshold, process PSS/RSS, graphics, Java and native heap, RAM tier) plus `thermal_status`, `thermal_headroom_10s` and `power_save`.

At start-up (tag `Backend`):
- `accelerator_profile`: SoC, board, hardware, fingerprint, kernel, ABIs, RAM class, GLES and Vulkan versions, whether the NPU runtime library is in the app, the app's native libraries, whether the system reports low-memory kills, thermal state.
- `process_exit` for each of the last five exits: reason, status, importance, PSS/RSS, description.
- `last_exit`.
- `backend_died_while_loading`, `backend_died_while_loaded` and `backend_blocked` for strikes from crashes and kills.
- `backend_record` for every stored record.
- `backend_model_summary` for every model: the same view as the settings screen.

`job_failed` carries the error type, `cause_chain` (types only), `frames` and `is_oom`. Failures in stages that handle patient text (`embed`, `asr_transcribe`, `live_cue_tick`, `memory_lab_failed`) are written the same way (`AppLog.spanRedacted` / `eRedacted`): no message, no stack trace.

At start-up, `backend_attempt_interrupted` means an attempt was open when the process ended for a reason that may not be the backend's fault. It counts only if it happens again.

**`backend.log`.** Every INFO-or-above line with a `load_id`, and every `Backend` line, is also written to `files/logs/backend.log`. That file is never rotated with the others; past 400 KB its older half is dropped. So which hardware each model ran on, and why, survives hours of recording. Outside debug builds, DEBUG lines (span starts) go to logcat only, not to the files.

Start-up events log device facts once: `startup` (SoC, Android, RAM, storage, app version), `backend_hints`, and `model_files` with sizes.

## Read a run

Pull the rotating log files from the device:

```bash
adb pull /sdcard/Android/data/com.therapylocal.app/files/logs/ .
```

## Privacy: what is never logged

This is a therapy app. Transcript text, query text and audio are **never** logged by default. Lines carry lengths, counts, ids, scores and timings only.

`AppLog.text()` can write transcripts as `text=...` at debug level, but only when the `LOG_TEXT` build flag is true. It is **false** in `app/build.gradle.kts` and must stay false in anything shared. The Logs tab shows `text logging: false` so you can see the state at a glance. `client` and `session` field values (the latter embeds the client name) are written as an 8-hex-character pseudonym (HMAC-SHA256 keyed by the vault key), never as the real id; while the vault is locked they read `locked`. The same session therefore has the same pseudonym within one vault, so a run can still be followed. The log file itself is not encrypted (it holds no patient text or names), and treat any build with `LOG_TEXT` on as test-only and clear it afterwards.
