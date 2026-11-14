# quality-cs, tomas-b, Poco (2510DPC44G), per-stage snapshots

Run of 2026-11-13/14, workflow `quality-cs` (settings.json): diarize, Whisper large-v3 q8_0 on CPU transcribing each turn in Czech (beam 5), Gemma E4B translates to English, roles, facts, summary. Snapshots `after-<job kind>/` were taken by a watcher polling the QA API (export, results, settings, filtered stage log). Times in captured_at_utc.txt are UTC.

- App build: local QA build of branch worktree-frolicking-foraging-aurora, commit f7d9deb (quality-cs added), installed with `install -r`. Whisper file ggml-large-v3-q8_0.bin, sha256 24bc434f372355688ab9a623077a63e5361a1c41f4d8d648977e39f9b060f09e.
- Diarize ran before the watcher started (about 2 min, 80 turns); no snapshot for it.
- Phone state changed during the run: locked and headless until about 23:03 UTC, then unlocked, out of its case, MI Ultimate mode. The queue stalled from about 23:23 to 23:44 UTC (vault locked, `:batch` gone) until unlocked through the QA API.
- Measured stage wall times (logcat): transcribe 1839.7 s, translate 412.8 s, facts commit 585.6 s, summary 65.5 s. Roles and extract times are only known from snapshot times (roles done 23:52:53, extract done 00:02:33): estimates.
- Gemma E4B backend: GPU failed at the translate stage (`GPU=failed(EngineDied,17287ms)`, 23:44:50 UTC), so translate and everything after ran on CPU (`CPU=proven`), and the GPU was demoted for the rest of the run ("failed on real prompts earlier in this run"). `translate_done model=gemma-4-e4b`.
- Not a clean timing comparison with the earlier Fast and Quality runs (different phone state, CPU Gemma).
- Segments: 17 stored utterances; the 80 diarizer turns are not exported (see ../../../README.md).
- Reuse: after-transcribe/export.json is the cached Czech transcript for import_manifest (resume after transcribe only; see cache/README.md).
