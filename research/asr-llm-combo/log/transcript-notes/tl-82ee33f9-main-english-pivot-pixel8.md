# 82ee33f9 main session (Therapy_local, English pivot, Pixel 8 device run, PR #104 APK cleanup)
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local/82ee33f9-13b4-4236-988f-6090542871ad.jsonl (1358 lines, mtime 02:50)
Time range: 2026-11-12 22:22 to 2026-11-13 01:42. Filtered view: 73 lines kept of 269 condensed records (keyword filter; the middle of the session is partly skipped in the filter, see INDEX).

## Design decision (English pivot)
- [DECISION] User (22:22:21): "we are doing all processing in the language that we are switching the client to ... we will do all the logic in English ... convert it to English. We will do all the processing, all the summaries, and only then ... show translated. Do this as a separate feature branch ... we ain't gonna be merging it ... we need to test side by side". [L24]
- [DECISION] User (22:24:55): plan first, Opus maps and plans, Sonnet subagent codes; draft an architecture decision on this PR, "not approved yet, but drafted". Stated theory: "when we use the local models and we use them in English, they're way smarter ... E4B in Czech is going to be dumber than E2B in English. And translation is easy." [L294, L295, L296]
- [CLAIMED] Agent (22:26:35): AD-17 draft written at `docs/AD/AD-17.md` on `feature/english-pivot`, listed as Proposed; Canary still transcribes in client language. [L365]
- [CLAIMED] Embedding model constant `embeddinggemma-2-740m` in `config/AppConfig.kt:59` (grep output 22:24:23). [MEASURED grep line L235]

## CI / APK publishing
- [DECISION] User (22:58:42): "please make sure each PR publishes its own APK - and when its closed its deleted so we dont have stale APKs". [L414]
- [CLAIMED] Agent (23:07:03): opened PR #104; PR #104 published its own release `pr-104` with `Therapy-pr104-qa.apk` and `.sha256`, marked prerelease; deletion on close cannot run until merged. [L508]
- [MEASURED] Later (01:41:37): `MERGED 04e2f49 Deleted branch ci/pr-apk-release (was 09e9c38). pr-104`; a local branch delete failed because the branch was used by worktree `/home/martin/therapy-prs/wt-prapk`.
- [MEASURED] (01:41:56): `run: pull_request_target completed failure pr-104`. (01:42:05): prune step ran `scripts/prune-pr-apks.sh` -> `No such file or directory`.
- [CLAIMED] Agent (01:42:36): PR #104 merged into main; the cleanup workflow ran the script from the PR base commit (main before #104 added it); opened fix as PR #114.
- [DECISION] User (01:41:09): "merge apk release" and (01:36:27) "is the branch on gh?".

## Device run (Pixel 8, serial 44030DLJH000DD)
- [DECISION] User (23:54:25): "there have been new transcripts commited to the repo - i'd like you dispatch an agent to go install the version of the app you cooked up - and run the four sessions thru it and write up a detailed report"; (23:54:33) "use pixel 8 over ADB".
- [CLAIMED] Agent (23:55:56): Sonnet agent sent to the Pixel 8; four human-voice sessions as test set; each has recording + script as answer key; jana-n and tomas-b in Czech.
- [DECISION] User (23:57:02): "if possible have the subagent use the app backgrounded so my gf can keep using it". (23:57:52) "have the subagent provide ETA based on the current progress every 10 minutes".
- [CLAIMED] Agent (23:58:48): test blocked at 00:58 because speech models were missing on the Pixel 8 (Canary and diarizer folders empty); copying models over adb from the other phone `4a985db2` (Xiaomi 2510DPC44G), about 1.4 GB.
- [MEASURED] (23:58:31) model files on `4a985db2`: `decoder.int8.onnx` 557,033,012 bytes; another file 857,603,131 bytes.
- [CLAIMED] Agent (00:00:15): all five model files' checksums match the source phone; app reports models ready.
- [MEASURED] Session length on the device: `wav` sizes in the test: daniel-r_s01 556 s, hannah-w_s01 569 s, jana-n_s01 385 s, tomas-b_s01 411 s (00:49:47 tool output).
- [MEASURED] Earlier (00:07:35): `adb devices`: `44030DLJH000DD device usb:6-1 product:shiba model:Pixel_8`; `4a985db2 device usb:8-1 product:annibale_eea model:2510DPC44G`.
- [DECISION] User (00:07:28) "check"; agent (00:07:44): no wireless debugging used by the test agent; wireless debugging on (`adb_wifi_enabled = 1`) already before.
- [DECISION] User (00:08:23): "oh pls so turn it off - and check whether anyone has connected over the wifi in the last 1h to it. have a separate subagent do that". Agent (00:09:58): off; setting reads 0; phone still on USB; "I can't tell for sure, but there's no sign anyone did".
- [CLAIMED] Agent (00:11:14): run A 0 of 16 jobs done at 01:11; first transcription running about 10 min; Canary on CPU; diarizes ~10 min session.
- [DECISION] User (00:13:19): "why the fuck aint it running the TPU/GPU?". Agent (00:13:43): Canary on CPU, finished turns at RTF about 2.7 (1-s turn took 2.6 s; 7-s turn still going after 30+ s).
- [CLAIMED] Agent (00:17:12): CPU is the only choice for the speech model on every phone; phone hot; background work at low priority. Gemma on Tensor NPU is only Tensor G5/G6 (Pixel 10) per LiteRT-LM docs (agent's claim, 00:22:00). Pixel 8 Tensor G3 has no published NPU package (claim).
- [DECISION] User (00:22:54): "have the agent check detailed debug logs to see the progress". Agent (00:24:06, from debug logs at 01:23): jana-n speaker separation done (~10 min); transcription turn 88 of 96, 295 of 385 s of audio, about 1.8x real time; all 4 sessions transcribed around 02:39 (agent estimate, excl. Gemma).
- [CLAIMED] Agent (00:24:21): on CPU Gemma generates about 6 tokens/s; a typical 400-token call takes about 70 s (from old Gemma logs on the phone).
- [MEASURED] Queue (00:49:28): `jobs {"total": 21, "pending": 16, "blocked": 0, "failed": 0, "done": 4, "running": "transcribe", "open_kinds": ["cleanup","commit","extract","roles","summary","transcribe","translate","window"]}`.
- [MEASURED] Job log (00:49:36): `job_start job=1 kind=transcribe ... job=2 kind=translate attempt=1` timestamps 00:24:44 and 00:40:16 (phone clock).
- [MEASURED] Timing table from app log at 01:49 (agent, 00:50:14): jana-n (385 s audio): speaker separation + transcription 23.8 min (about 3.7x). Run A estimate: finish around 05:30, full report about 10:00 (agent claim).
- [DECISION] User (00:57:54): "so its cca 4x slower than realtime to transcribe? why?". Agent (00:58:12): about 3.5-3.7x; diarization 608 s for 385 s (jana-n) and 523 s for 410 s (tomas-b) = 1.3-1.6x; Canary turn-by-turn about 1.8x; total 3.5-3.7x.
- [QUESTION] Agent (00:58:21) open decisions: should run B reuse run A transcripts (saves about 2 hours); should a Sonnet agent open a separate PR so background processing stops running at low priority.
- [DECISION] User (00:58:59): "bro fix the priority". Agent (00:59:17) dispatched a Sonnet agent in its own PR (see tl-82ee33f9-sub-a44bc7-engine-priority.md).
- [DECISION] User (01:01:46): "please whne the PR passes CI, have the agent merge it automatically. and for now manually set the priority for the process".
- [CLAIMED] Agent (01:02:26): could not set priority by hand: `renice` from adb shell `Operation not permitted`; `run-as` refused (QA build not debuggable); no root (user build, no `su`).
- [CLAIMED] Agent (01:24:06): jana-n translation took 15.5 min; tomas-b translating for ~10 min at 02:24 (local); the agent's "all 4 transcribed" ETA moves 10 min later each update (excludes Gemma steps).
- [DECISION] User (01:31:17): "ok the pixel seems to suck rn". Agent (01:31:27): phone hot (CPU clocks cut to 40-45%), short on memory, Gemma's GPU check fails, no NPU path for Tensor G3; suggests the other phone `4a985db2` (has NPU build of Gemma).
- [DECISION] User (01:31:33): "lets drop it for now". Agent (01:32:20): stopped the test; wiped the QA app's test data with `reset` (cleared job queue); force-stopped app; nothing exported; no results.
- [CLAIMED] Thermal logging exists in `e2e/sittings/run_device.py` (writes `thermal.csv`, `thermal-summary.txt`; `--no-thermal` disables) (tool_result 01:31:44).
