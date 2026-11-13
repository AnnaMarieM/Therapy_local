# Stage cache: what is kept, what can be resumed

Goal: every pipeline stage leaves a snapshot, so a later test starts from it instead of re-running the slow stages (Whisper per turn about 27 min on the Poco, E4B facts about 15 min). Code: `qa/stage_cache.py` (layout, resume checks, polling), `qa/workflow_run.py --cache-dir / --resume-from --from-cache`, host tests `qa/test_stage_cache.py`.

Paths are relative to `app/src/`. "Read" means read from the code on this branch (commit `b3a9208` plus the stage-cache change); nothing here was run on a device.

## 1. What `export`, `results` and `import_manifest` keep

The stages are jobs of the chain `transcribe -> translate -> roles -> window -> extract* -> commit` with `summary` queued by `window` (`main/.../jobs/JobGraph.kt:47-51`, `main/.../jobs/Pipeline.kt:296`). Diarize and per-turn transcription are both inside the `transcribe` job (`main/.../jobs/TranscribeFlow.kt:86-91`).

### Turn boundaries are lost (80 turns become 17 segments)

- The diarizer turns are stored in `diarization_turns` per recording (`TranscribeFlow.kt:197`, `MindStore.kt:317`), with the text of each turn (`setTurnText`).
- The stored transcript is `UtteranceBuilder.build(pieces)` (`TranscribeFlow.kt:154`): neighbouring turns of one speaker are joined into one segment (`main/.../data/UtteranceBuilder.kt:11-25`). That is the 80 turns -> 17 segments. The English text is joined the same way (`UtteranceBuilder.english`, `:31`, stored at `TranscribeFlow.kt:158`).
- `export` and `results` read segments, not turns (`qa/.../QaAppBackend.kt:118-142`, `:237-276`). **No QA command returns `diarization_turns`**, so per-turn text and the turn boundaries are not in any snapshot. The merged segment keeps `startMs` of its first turn and `endMs` of its last, and the speaker.
- Nothing after `transcribe` reads the turns, so the lost detail does not change roles, window, facts or summary. It does mean a per-turn WER or a per-turn timing cannot be recomputed from a snapshot: those come from `stage-log.txt` (`turn_done` lines: turn index, audio_ms, wall_ms) and from the raw research outputs.

### Per document

| Document | Keeps | Drops |
|---|---|---|
| `export` (`QaAppBackend.kt:118`) | clients; per session: `started_at`, segments (`idx`, times, `speaker`, `role`, `text`, `text_en`), current `speaker_map` and all `speaker_maps` versions, `summary` (text, sections, state), `facts` (with `segIdx`/`segTo`), `processing_language`, `summary_format` | turns, audio, windows and embeddings, job state, transcript kind (`spoken` / `english_only`), the model and prompt that wrote each translation |
| `results` (`QaAppBackend.kt:237`) | the same per session, plus `transcript_kind` and `workflow` (`:251`), `facts`, `answers` | same as above |
| `get_settings` | every setting incl. `workflow`, `whisper_beam`, `backend_override`, `gemma_variant` | build and model hashes (`status` has the build and model ids and readiness only, `QaAppBackend.kt:44-55`) |

### What `import_manifest` restores

`importManifest` (`QaApi.kt:237`) calls `importOne` (`QaApi.kt:208`) per session; with `segments` it ends in `importTranscript` (`QaAppBackend.kt:96-115`):

- segments are parsed as `QaSegment(startMs, endMs, speaker, text)` only (`QaApi.kt:228`): **`text_en` and `role` are ignored**. Stored with `appendTranscript(...)` and no `kind`, so the kind is always `spoken` (`QaAppBackend.kt:106`).
- the current `speaker_map` is stored as version 1, source `import`, confidence 1.0 (`:107`). Older versions, evidence and the roles guess's confidence are not.
- with `process: true` (the default) the only jobs queued are `translate -> window` (`JobGraph.enqueueTranscript`, `JobGraph.kt:55-58`, called at `QaAppBackend.kt:108`). **No `roles` job** is queued; the roles come only from an imported map. `summary` follows `window` (`Pipeline.kt:296`).
- with `process: false` (QA_API.md, "import_session with segments") nothing is queued; `run_job <kind> <session>` then queues one job at a time (`QaApi.kt:275-287`, `QaAppBackend.kt:179-199`; kinds `transcribe translate roles window summary localize`, `QaApi.kt:294`). `run_job` queues a single job with no dependents, so each stage is run by hand, and `window` queues its own `extract`, `commit` and `summary`.
- facts, windows, summaries are never imported: derived, rebuilt.

### Resume matrix (by reading the code)

| Snapshot | Can a run restart after it from the public QA API? | How / why not |
|---|---|---|
| after-diarize | **No** (and no snapshot exists) | Turns are not exported; `import_session` with audio always diarizes (`TranscribeFlow.kt:86-91`: an empty `diarization_turns` plans anew). Needs the proposal A below. |
| after-transcribe | **Yes** | Import with `process:false`, then `run_job translate`, `roles`, `window`; `window` queues extract, commit, summary. Segments (the merged utterances) are exactly what `translate` reads, so the result equals the original run. The imported kind is `spoken`, right for `quality-cs`; for `quality` (`english_only`) it would be wrong (display only, `Workflow.keepsSourceTranscript`) |
| after-translate | **Yes for an English client only; No for Czech** | `text_en` is dropped on import, so for Czech the translation is lost and `roles`, `window`, `summary` would read the Czech text (`Pipeline.kt:96` reads `workingSegments(..., "en")` and falls back to the source text). Proposal B. |
| after-roles | same as after-translate, plus the map is kept (`speaker_map` imported) | Proposal B |
| after-window | same; and the facts are not imported, so `commit` output cannot be reused, only re-derived | The next stage, `summary`, reads the working segments and the speaker map, not the facts (`Pipeline.kt:262-266`) so `run_job summary` works after proposal B (device test 3) |
| after-summary | nothing left to run | |

`check_resume` in `qa/stage_cache.py` encodes exactly this table and answers with the reason; `--resume-from` stops with `resume not supported: ...` before it touches the phone.

### Small change needed (proposed, NOT implemented)

- **B. Import `text_en`.** In `QaApi.importOne` (`QaApi.kt:219-228`) read `text_en` and, in `QaAppBackend.importTranscript`, call `store.putEnglish`-style storage (`putSegmentTranslation(session, idx, "en", text, model, promptVersion)`, `MindStore.kt`), with the model and prompt version taken from the export (add `text_en_model` and `text_en_prompt` to `export`, `QaAppBackend.kt:118`, or use the same marker `whisper-translate` as `TranscribeFlow.kt:158`). `translate` already skips segments that have an English text for Fast, Quality and Quality-Cs (`Pipeline.kt:159-161`, `forcesEnglish`). About 15 lines in the `qa` source set; the app itself does not change. Also pass `transcript_kind` through (`appendTranscript(..., kind)`).
- **A. Turn snapshot.** To resume after diarize or to compare per turn: add the `turns` list (`idx`, `start_ms`, `end_ms`, `speaker`, `text`, `text_en`) to `export`, and have `import_manifest` write them with `replaceTurns` / `setTurnText` for a session with a recording row. Also needs the audio chunks (not exported), so a resume would still be limited to the stages after transcribe; so A is only worth it for per-turn analysis, not for speed. Not proposed unless a test needs it.
- **C. `status` model hashes.** Add `sha256` per model file to `status.models.files` (`QaAppBackend.kt:44`), or compute them on the host from the pushed files (`--models-manifest`, already supported).

## 2. Cache layout and key

```
cache/<platform>/<run-id>/<recording>/after-<stage>/
    export.json  results.json  settings.json  stage-log.txt  captured_at_utc.txt  META.json
```

- `<platform>`: `poco`, `t4`, ... one folder per device class. T4 uses the same layout: `export.json` and `results.json` are whatever the T4 driver writes in the same shapes, `META.json` has `device: "t4"`. (The T4 scripts in `t4-scripts/` and `t4/` are not wired to this yet; only the layout and `stage_cache.write_snapshot` are shared.)
- `<run-id>`: `<workflow>-beam<N>-perturn` (the existing Poco folders are `quality-cs-beam5-perturn` and `whisper-large-v3-q8_0-cs-beam5-perturn`). Anything that changes a stage's output changes the run id: workflow, beam, a new model file, a new build; META records which.
- `<recording>`: the client id (`tomas-b`).
- `<stage>`: `transcribe translate roles window summary`. `diarize` is inside `transcribe` (its time is the `stage=diarize` line of `stage-log.txt`). The watcher that runs now also names these by job kind.
- A snapshot is never overwritten (`write_snapshot` raises). `captured_at_utc.txt` is ISO 8601 UTC.
- `stage-log.txt`: the `evt=` lines of the app log up to the capture (counts, times, backends, never text). A resumed run prefixes the cached run's log under `# carried from <dir>`.
- `META.json`: `platform, run_id, recording, stage, device, workflow, whisper_beam, backend_override, build` (application id, version, commit), `models` (id, ready, sha256 or null), `stage_done` and `backend_proven` lines (the backend per stage), `open_kinds_before/after`, `queue_idle_at_capture`, `exact` (no job of this stage was open while the files were read), `resumed_from`, `phone_state` (free notes: charging, locked, thermal, `--note`). No serial.

## 3. Using it

```bash
# full run, snapshot after each stage (one recording)
qa/workflow_run.py --serial S --device poco --pipeline quality-cs --wav-dir D --recordings tomas-b --out OUT \
    --cache-dir research/asr-llm-combo/final-workflow/cache --note "locked, charging"
# later: skip diarize + Whisper, redo translate onward
qa/workflow_run.py --serial S --device poco --pipeline quality-cs --recordings tomas-b --out OUT2 --cache-dir .../cache \
    --run-id quality-cs-beam5-perturn-resume1 --resume-from transcribe \
    --from-cache .../cache/poco/quality-cs-beam5-perturn/tomas-b/after-transcribe
```

A resume resets the QA vault (unless `--no-reset`), sets the settings from the arguments (the pipeline must equal the cached workflow), imports the cached clients and sessions with `process:false`, and runs the later jobs one by one, snapshotting after each. Give a new `--run-id` when caching a resumed run, so it is never mixed with the original.

## 4. Device tests still needed (nothing here ran on a phone)

1. **Resume after transcribe.** From the Poco `after-transcribe` of tomas-b: resume, compare `results.json` `facts`/`summary` against the original run's after-summary. Expect translated text byte-identical (greedy LLM, `TRANSLATE_TEMPERATURE`) or near it; the comparison itself is the test of whether E4B on the GPU is deterministic.
2. **`import_manifest` with `process:false` and a payload of that size.** `qa/qa-api.sh import_manifest` passes the whole file in one `content call --arg` (base64 of about 30 kB for 17 segments); confirm adb accepts it (tomas-b Quality export is the largest case).
3. **`run_job summary` after an import** with no facts present (the after-window resume, English client).
4. **`run_job roles` with an imported map** returns at once without a new map (`Pipeline.kt:239-245`, `RoleStep.decide` returns null when a map exists).
5. **Poller timing.** `wait_stage` polls `status.jobs.open_kinds` every 3 s; `roles` and `extract` can finish between two polls. Check `exact` in META of a real run; if a stage is often `exact: false`, drive stages with `run_job` also in full runs.
6. After proposal B is built: resume after translate and after roles for tomas-b and compare to the original after-summary.
