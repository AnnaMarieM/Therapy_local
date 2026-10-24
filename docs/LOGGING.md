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
| `chunk_cut` | Audio | one chunk cut from the live stream; `rms` is the signal level |
| `audio_wav_encode` / `audio_to_float` | ASR | preprocessing for Gemma (WAV) or Whisper (float) |
| `model_download`, `model_verify` | Download | bytes fetched, resume offset, sha256 check |
| `asr_load`, `engine_init`, `engine_warmup` | ASR | which backend loaded (`backend`), model file, size; failed attempts are `backend_failed` with `reason` |
| `asr_transcribe` | ASR | `engine`, `model`, `backend`, `audio_sec`, `chars`, `rtf` (wall time / audio time), `chars_per_sec` |
| `whisper_decode` | ASR | the decode step alone |
| `embed_load`, `embed_engine_init`, `embed_selftest` | Embed | the self-test logs `sim_related` and `sim_unrelated` |
| `embed` | Embed | `role` (document or query), `dim`, `norm`, `backend` |
| `db_create_recording`, `db_insert_chunk`, `db_list_recordings` | DB | row counts and ids |
| `chunk_pipeline` | App | the whole chunk: transcribe, embed, store |
| `search`, `search_scan`, `voice_query_transcribe` | Search/App | `kind` (text or voice), `embed_ms`, `scan_ms`, `rows_scanned`, `top` as `rid.idx:score` |
| `db_delete_recording`, `db_delete_all` | DB | rows removed and rows left |

Start-up events log device facts once: `startup` (SoC, Android, RAM, storage, app version), `backend_hints`, and `model_files` with sizes.

## Read a run

```bash
scripts/traces.sh summary
```

prints the start-up facts, selected backends, per-span statistics, a per-chunk duration table, transcription speed, searches, deletes, and errors.

## Privacy: what is never logged

This is a therapy app. Transcript text, query text and audio are **never** logged by default. Lines carry lengths, counts, ids, scores and timings only.

`AppLog.text()` can write transcripts as `text=...` at debug level, but only when the `LOG_TEXT` build flag is true. It is **false** in `app/build.gradle.kts` and must stay false in anything shared. The Logs tab shows `text logging: false` so you can see the state at a glance. Logs on the phone are not encrypted, so treat any build with `LOG_TEXT` on as test-only and clear it afterwards.
