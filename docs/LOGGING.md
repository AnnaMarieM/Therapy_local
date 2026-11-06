# Logging and tracing

Every stage of the app writes structured `key=value` lines, so a recording can be followed end to end and timed.

## Where the lines go

| Sink | How to read it |
|---|---|
| logcat, tag `TL` | `adb logcat -s TL` |
| Rotating file on the phone (1.5 MB x 5) | `/sdcard/Android/data/com.therapylocal.app/files/logs/tl.log`, pulled via `adb pull /sdcard/Android/data/com.therapylocal.app/files/logs/ .`. Each process writes its own file: `tl.log` (main), `engine-live.log`, `engine-batch.log` (same rotation); every line carries `proc=` and `pid=` |
| In-app viewer | Logs tab: Refresh, Copy, Share, Clear. It shows the three process files merged by time (`AppLog.tailAll`), so Share exports all of them in one text |
| Pinned backend file | `backend.log`, written by the main process only (below) |

The log export of [AD-01](AD/AD-01.md) ("bundles all files") is met two ways: `adb pull` of the whole `logs/` directory gives every file as written, and the Logs tab Share gives the merged tail of `tl.log`, `engine-live.log` and `engine-batch.log`. Clear in the Logs tab clears all of them: main deletes its own files and asks each bound host (`EngineOp.ClearLogs`) to clear its own; the files of hosts that are not running are deleted directly. A host that lost its file opens a new one on its next line.

## Line format

```
ts=2026-10-24T13:20:04.509 lvl=I tag=ASR sid=ab12cd rid=3 cid=3.2 evt=span_end span=asr_transcribe dur_ms=1234.5 engine=canary model=canary-1b-v2 backend=CPU audio_sec=20.00 chars=212 rtf=0.06
```

| Field | Meaning |
|---|---|
| `ts`, `lvl` | time, level (D debug, I info, W warn, E error) |
| `proc`, `pid` | the process that wrote the line: `main`, `live` or `batch`, and its Android pid. Every line has both |
| `tag` | component: `App`, `Audio`, `ASR`, `Embed`, `DB`, `Search`, `Download`, `Models`, `Device`, `Engine` |
| `sid` | session id, one per process. A host's `host_start` carries main's as `client_sid`, which joins the files |
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
- `last_exit`. Both are main-process lines. They name the process they describe and are read per process name, so a `:batch` death is not taken for a main-process death.
- `backend_died_while_loading`, `backend_died_while_loaded` and `backend_blocked` for strikes from crashes and kills.
- `backend_record` for every stored record.
- `backend_model_summary` for every model: the same view as the settings screen.

`job_failed` carries the error type, `cause_chain` (types only), `frames` and `is_oom`. Failures in stages that handle patient text (`embed`, `asr_transcribe`, `live_cue_tick`, `memory_lab_failed`) are written the same way (`AppLog.spanRedacted` / `eRedacted`): no message, no stack trace.

At start-up, `backend_attempt_interrupted` means an attempt was open when the process ended for a reason that may not be the backend's fault. It counts only if it happens again.

**`backend.log`.** In the main process, every INFO-or-above line with a `load_id`, and every `Backend` line, is also written to `files/logs/backend.log`. That file is never rotated with the others; past 400 KB its older half is dropped. So which hardware each model ran on, and why, survives hours of recording. Outside debug builds, DEBUG lines (span starts) go to logcat only, not to the files.

Start-up events log device facts once: `startup` (SoC, Android, RAM, storage, app version), `backend_hints`, and `model_files` with sizes.

## Engine processes (AD-01)

Models run in `:live` and `:batch`, bound by the main process ([AD-01](AD/AD-01.md)). The event names are one list, `engine/EngineEvents.kt` (`EngineEvents.ALL`), shared by the code and by the tests that check the log. Main writes the connection events to `tl.log`; the hosts write theirs to their own file. Connection events carry `role=live|batch`, and most carry `gen`, the number of the connection generation (it grows by one at every start of the process, so a stale callback from an old generation is told apart). `host_pid` is the host's pid, `req` the request id and `load_id` the id of one backend ladder attempt, as before.

**Written by main (`tl.log`)**

| Event | Meaning and fields |
|---|---|
| `engine_bind` | a bind was started: `gen`, `start_no`, `flags` |
| `engine_connected` | the host answered: `gen`, `host_pid`, `start_ms` (bind to connected) |
| `engine_bind_timeout` | no answer in `bindTimeoutMs`: `gen`, `why`, `timeout_ms` |
| `engine_stale_callback` | a callback from an older generation was ignored (debug level): `conn_gen`, `gen_now` |
| `engine_unbound` | the connection was closed on purpose: `gen`, `reason` |
| `engine_call` | a call finished: `engine`, `role`, `req`, `load_id`, `op`, `dur_ms`, `ok`. Info for loads and LLM calls, debug for embeddings and transcription |
| `engine_call_failed` | a call failed: `op`, `req`, `why` (`transport`, `stale_handle`, `wrong_thread`) or the error type. Text calls are written without message or stack trace |
| `engine_call_timeout` | the watchdog gave up on a call: `op`, `req`, `limit_ms`, `elapsed_ms`, `cpu_ms_delta`, `hard_cap`. Followed by `engine_kill` |
| `engine_retry` | a call is tried again on a fresh host: `why` (`benign_exit` or `transport`), `op`, `req`, `gen` |
| `quiet_crash_install` | a host installed (or could not install) the native crash handler: `result` (`installed`, `refused_not_chained`, `library_missing`, `error_N`) and `sigaction_owner` (the library that provided `sigaction`; `libsigchain.so` is expected) |
| `engine_died` | the host ended in a way nobody asked for. The one line to read first: `gen`, `host_pid`, `uptime_ms`, `source` (which callback noticed), `op`, `req`, `load_id` and `phase` of the call in flight, `calls`, `exit_kind` (`killed_for_memory`, `native_crash`, `crash`, `anr`, `unknown`), `reason_name`, `status`, `description` (a quiet crash is `reason_name=exit_self`, `exit_kind=native_crash`, `status=100+N`, `description="signal N"`; a logged Java crash is `exit_kind=crash`, `status=13`, `description=java_crash`, see [AD-04](AD/AD-04.md)), `pss_kb`, `rss_kb`, `hang`, `host_importance`, `client_importance`, `last_host_phase`, `last_host_op`, `deaths` (in the window), and the system memory fields |
| `engine_kill` | main killed the host: `host_pid`, `gen`, `why` (`hang`, `close_timeout`, `shutdown_timeout`, `stopping_wait`) |
| `engine_exit` | an expected, clean end (idle stop, close, shutdown): `gen`, `host_pid`, `reason`, `why`, `status` |
| `exit_reason_pending` | the system had not yet recorded the exit; main looks again for a short while: `gen`, `host_pid` |
| `engine_restart` | the next start after a death: `gen`, `backoff_ms`, `deaths_in_window` |
| `engine_circuit_open` | too many deaths in the window (`window_ms`): the host is refused until old deaths leave the window |
| `engine_unavailable` | a call was refused because the circuit is open: `why=storm`, `deaths`, `retry_in_ms` |
| `engine_idle_stop` | no client for `idle_ms`, so the process was stopped |
| `live_shutdown_requested`, `engine_live_shutdown_done` | `:live` is shut down before `:batch` opens a model: `waited_ms`, `forced` (killed after `liveShutdownMs`) |
| `job_deferred` | listed in the vocabulary; written by the job queue when no host can serve a job. Not written yet, see the note at the end of this section |
| `fault_plan_ignored` | listed in the vocabulary for debug fault injection. Not written yet, see the note |

**Written by a host (`engine-live.log`, `engine-batch.log`)**

| Event | Meaning and fields |
|---|---|
| `host_start` | twice per start: once when the service is created (`role`, `pid`) and once at `attach` (`step=attach`, `client_pid`, `client_sid`). `client_sid` is the `sid` of the main process, so the files join |
| `host_open` | an `Open` arrived: `model`, `backend_try`, `kind`, plus the call fields |
| `backend_phase` | the native step about to run (`create`, `initialize`, `test`), written and flushed before it. After a native kill it is the last line of the file |
| `engine_role_violation` | a host was asked for a model its role does not serve: `kind`, `text_only`, `role` |
| `host_client_died` | the main process died, so the host leaves |
| `host_exit` | the host ends: `role`, `why`, `reason` |

The ladder events of "Model and backend events" below are written by whichever process runs the ladder step: `ladder_*`, `backend_skipped*` and `backend_proven`/`backend_failed` stay in `tl.log`, `backend_phase` and the native work are in the host files.

### Reading one story across three files

Lines of one story share an id, so merge the files by `ts` (the Logs tab does it for you) and filter on the ids:

```bash
cd logs/
grep -h "req=7f3a" tl.log engine-batch.log | sort          # one call, both sides
grep -h "load_id=L12" tl.log engine-*.log | sort            # one ladder attempt
grep -h "gen=3" tl.log | grep "role=batch"                   # one process generation, main's side
grep -h "pid=18211" tl.log engine-batch.log | sort          # everything about one process
```

A crash during a load reads, in time order:
1. `engine-batch.log`: `host_open` then `backend_phase phase=initialize` (the last line the host wrote);
2. `tl.log`: `engine_died ... exit_kind=native_crash phase=... last_host_phase=initialize`;
3. `tl.log`: `backend_failed` (the strike with the exit reason), then the next backend, ending in `ladder_done`;
4. `tl.log`: `engine_restart` with the backoff, and later `engine_connected` with a higher `gen`.

To join the host files to the right run, take `client_sid` from `host_start` and find the same value as `sid=` in `tl.log`. A host started by an earlier run of the app dies with it (`host_client_died`), so one file can hold several `client_sid` values.

### Debug override and force

The developer screen can force a backend (`BackendPolicy.override`). With `BackendPolicy.force` also on, the engines skip the free-memory gate and the guard's blocks and demotions and try exactly that one accelerator (a missing runtime library still refuses it). The engine process then either survives or dies, and main goes on; this is how a host death on real hardware is provoked. `force` is for debug and QA only and no release path sets it. The QA probe sets it with an extra:

```bash
adb shell am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.MemProbeActivity --es variant e4b --es backend GPU --ez force true --es tag e4b_gpu_forced
```

Expect `engine_died` in `tl.log` and a probe result file that records the error; the app must stay up.

*To revisit after the strike, deferral and fault-injection steps land:* `job_deferred` and `fault_plan_ignored` fields, the immediate strike line, and the debug fault points.

## Read a run

Pull the rotating log files of all three processes from the device:

```bash
adb pull /sdcard/Android/data/com.therapylocal.app/files/logs/ .
```

## Privacy: what is never logged

This is a therapy app. Transcript text, query text and audio are **never** logged by default. Lines carry lengths, counts, ids, scores and timings only.

`AppLog.text()` can write transcripts as `text=...` at debug level, but only when the `LOG_TEXT` build flag is true. It is **false** in `app/build.gradle.kts` and must stay false in anything shared. The Logs tab shows `text logging: false` so you can see the state at a glance. `client` and `session` field values (the latter embeds the client name) are written as an 8-hex-character pseudonym (HMAC-SHA256 keyed by the vault key), never as the real id; while the vault is locked they read `locked`. The same session therefore has the same pseudonym within one vault, so a run can still be followed. The log file itself is not encrypted (it holds no patient text or names), and treat any build with `LOG_TEXT` on as test-only and clear it afterwards.
