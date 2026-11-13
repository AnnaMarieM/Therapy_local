# f3cc06b4 subagent agent-ad1ca654ee251ed33 - build QA APK, install on POCO (4a985db2), ingest 4 human-voice sessions, evaluate
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local/f3cc06b4-7740-4c6f-a8e3-e6cbeac0b21d/subagents/agent-ad1ca654ee251ed33.jsonl (281 lines, mtime 02:50)
Time range: 2026-11-12 23:52:14 to 2026-11-13 01:50:54. Filtered view: 12 lines kept of 121.

- [DECISION] Task (23:52:14): build QA APK from `feature/english-pivot` (AD-17); install on POCO serial 4a985db2; ingest 4 human-voice sessions (Daniel R., Jana N., Tomas B., Hannah W.) from PR #105 branch `corpus/human-voice-sessions`; report how well it works. Hajnina account only; no AI attribution.
- [MEASURED] Build (01:12:11 report): `:app:assembleQa` inside `therapy-builder` Docker image; `BUILD SUCCESSFUL in 1m 10s` (monitor event 23:53:41). APK `app-qa.apk` 136,985,205 bytes (tool_result 23:53:55).
- [MEASURED] First QA API reset (23:54:02): `{"command":"reset","ok":false,"error":{"code":"vault_locked","message":"the vault is locked: call unlock"}}`.
- [MEASURED] Package-manager/`ls` output (00:53:38 region): jobs `{"total":38,"pending":15,"done":22,"running":"extract"}`; extract steps logged at 00:47-00:48.
- [MEASURED] 01:01:13: `{"total":38,"pending":7,"done":30,"running":"commit"}`. Agent (01:01:14): 30 of 38 done at ~64 min elapsed; ETA ~02:15 host time.
- [MEASURED] Import of sessions via API (`import_session`, ok true) at ~00:58:46 for the sessions.
- [MEASURED] Export file sizes (01:11:17): `export.json` 122,163 bytes; `logcat.txt` 23,979,304 bytes (on host, 02:11 mtime).
- [MEASURED] Export keys (01:11:20): `dict_keys(['embedModel','summary_format','processing_language','sessions','facts','answers'])`; `embedModel str 21`; `sessions list 4`; `facts list 114`; `answers list 0`.
- [MEASURED] WER (tool output 01:11:27, agent's script against the scripts): `daniel-r ref words 1423 hyp 1502 WER 26.4%`; `hannah-w ref words 1250 hyp 1270 WER 19.7%`. (Later re-scored by the parent; see tl-f3cc06b4-main-poco-corpus-whisper-issues-ad18.md.)
- [MEASURED] Facts per session (01:11:42): `daniel-r_s01 facts 32`; examples: "Burnout since October", "Long working day".
- [CLAIMED] Agent (01:12:11): all four sessions through AD-17 pipeline: 38 of 38 jobs done, 0 failed, 0 crashes. Quality is the problem, mainly Tomas; Czech names and diarization also weak.
- [QUESTION] Monitor events "ingest done" and job-event monitors expired after 30 min with no events (00:30:57, 00:53:31, 01:01:06) and were re-armed.
- [DECISION] Coordinator message (00:23:25): reply to status request in 3-4 lines.
- [BUG] Blocked tool use: a bash `sleep 240`/`sleep 100` followed by commands was refused; use Monitor with until-loop (23:52:34, 00:00:53).
