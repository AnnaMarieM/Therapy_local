# Logging and tracing

Every stage of the app writes structured `key=value` lines, so a recording can be followed end to end and timed.

## Where the lines go

| Sink | How to read it |
|---|---|
| logcat, tag `TL` | `scripts/traces.sh live` or `adb logcat -s TL` |
| Rotating file on the phone (1.5 MB x 5) | `/sdcard/Android/data/com.therapylocal.app/files/logs/tl.log`, pulled by `scripts/traces.sh pull` |
| In-app viewer | Logs tab: Refresh, Copy, Share, Clear |

## Line format

```
ts=2026-10-24T13:20:04.509 lvl=I tag=ASR sid=ab12cd rid=3 cid=3.2 evt=span_end span=asr_transcribe dur_ms=1234.5 engine=whisper model=whisper-base backend=CPU audio_sec=20.00 chars=212 rtf=0.06
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

Values with spaces or `=` are double-quoted. Grep any id: `scripts/traces.sh grep 'cid=3.2'`.

## Stages that are timed (spans)

| Span | Tag | Notes |
|---|---|---|
| `chunk_cut` | Audio | one chunk cut from the live stream: `idx`, `start_ms`, `end_ms`, `overlap_ms` (all from `ChunkConfig`), `rms` is the signal level |
| `chunk_dropped_short` | Audio | the trailing chunk added too little new audio (`new_ms` against `min_new_ms`) and was not kept |
| `audio_wav_encode` / `audio_to_float` | ASR / Embed | preprocessing: WAV for Gemma and for the audio embedder, float for Whisper |
| `model_download`, `model_verify` | Download | bytes fetched, resume offset, sha256 check |
| `asr_load`, `engine_init`, `engine_warmup` | ASR | which backend loaded (`backend`), model file, size; failed attempts are `backend_failed` with `reason` |
| `asr_transcribe` | ASR | `engine`, `model`, `backend`, `audio_sec`, `chars`, `rtf` (wall time / audio time), `chars_per_sec` |
| `whisper_decode` | ASR | the decode step alone |
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

Model and backend events: `model_info` (what the model bundle itself declares: modalities, supported backends per modality, NPU brand), `backend_selected` and `backend_failed` (with `reason`), and `npu_skipped reason=dispatch_library_missing`. NPU is attempted only when `libLiteRtDispatch_Qualcomm.so` is present in the native library dir (see `NpuRuntime`); the `litertlm-android` AAR does not ship it, so on this build the NPU is never used and the logs say so.

Start-up events log device facts once: `startup` (SoC, Android, RAM, storage, app version), `backend_hints`, and `model_files` with sizes.

## Read a run

```bash
scripts/traces.sh summary
```

prints the start-up facts, selected backends, per-span statistics, a per-chunk duration table, transcription speed, searches, deletes, and errors.

## Privacy: what is never logged

This is a therapy app. Transcript text, query text and audio are **never** logged by default. Lines carry lengths, counts, ids, scores and timings only.

`AppLog.text()` can write transcripts as `text=...` at debug level, but only when the `LOG_TEXT` build flag is true. It is **false** in `app/build.gradle.kts` and must stay false in anything shared. The Logs tab shows `text logging: false` so you can see the state at a glance. Logs on the phone are not encrypted, so treat any build with `LOG_TEXT` on as test-only and clear it afterwards.
