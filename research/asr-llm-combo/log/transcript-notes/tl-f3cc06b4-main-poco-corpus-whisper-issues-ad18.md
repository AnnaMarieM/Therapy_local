# f3cc06b4 main session (Therapy_local) - human-voice corpus ingest on the POCO, WER vs Whisper, GPU embedder timing, issues #107-#113, AD-18
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local/f3cc06b4-7740-4c6f-a8e3-e6cbeac0b21d.jsonl (746 lines, mtime 03:20)
Time range: 2026-11-12 23:52 to 2026-11-13 02:20. Filtered view: 72 lines kept of 179 condensed records. Phone = POCO (Xiaomi 2510DPC44G, serial 4a985db2) per the session; the Pixel 8 is not used here.

## Corpus and ingest
- [CLAIMED] User prompt (23:52:00): four client sessions with real human voice; Daniel R., Jana N., Tomas B. from the synthetic clients plus new English client Hannah W.; ~90-110 turns each; read verbatim by user and partner with "about five minor misreads in total"; committed in `e2e/corpus/human/`, PR #105 on main. Remaining: scorer wiring, ground-truth facts per session, adding Hannah to clients.json. [L10]
- [DECISION] User (23:53:18): "use the poco only". [L61]
- [MEASURED] Recording durations (tool_result 00:25:35): `wav/daniel-r.wav 556.416`, `wav/hannah-w.wav 568.6613125`, `wav/jana-n.wav 384.5333125`, `wav/tomas-b.wav 411.4986875` (seconds). [L196]
- [MEASURED] Ingest progress (00:25:35): `116 calls, audio 501.17s, wall 212.608s` (Transcribed segments so far, mostly Hannah). [L196]
- [MEASURED] QA API status (00:49:49): `models":{"ready":true,"setup_state":"ready","done_bytes":7244931137,...`; build commit "unknown". [L247]
- [MEASURED] Job counts (01:49 per agent): `38 jobs total, 19 done, 18 pending, 0 failed, 0 blocked`; running `commit`. [L250]
- [MEASURED] Job counts (01:55:19): `"jobs":{"total":38,"pending":12,"blocked":0,"failed":0,"done":25,"running":"commit","open_kinds":["cleanup","commit","extract","summary"]}`. [L279]
- [CLAIMED] Agent (00:55:06): ETA 02:10-02:30 is laptop time; phone log timestamps are one hour behind (phone on UTC). [L266]
- [CLAIMED] Agent (01:12:21): ingest finished: QA build installed on the POCO; all four sessions through the full pipeline; 38 of 38 jobs done, 0 failed, 0 crashes; transcript quality is the problem, mainly Tomas. [L329]
- [CLAIMED] Agent: Gemma steps (window, extract, summary, roles) run on GPU; NPU blocked. Each Gemma load ~15-23 s to open on GPU. [L301] Log text: `summary="NPU=blocked;GPU=proven(...)"`; saved record says `IllegalStateException: llm_output_proof2_f...` (cut).

## Embedder timing (POCO, EmbedProbeActivity, EmbeddingGemma 740M)
- [CLAIMED] (01:18:28) GPU: load 6.2 s, per call median 36 ms, first call 39 ms, GPU ran. CPU: load 1.3 s, per call 75 ms, first call 111 ms. NPU (from ingest log, not re-run): load 197-236 s, per call 12-18 ms. Test texts 40-60 characters, 4 sentences x 9 repeats. [L363]
- [CLAIMED] GPU and CPU cosine similarities differ by 0.001-0.003. [L363]
- [CLAIMED] Limits noted by agent: real documents ~3,000 characters; NPU row not exact. [L363]

## WER results (English and Czech, Canary in app vs Whisper small)
- [CLAIMED] Agent's table (01:12:21 report; numbers not visible in filtered view except as noted): per session WER reported by the QA agent; the agent's English numbers were then corrected. [L329]
- [CLAIMED] Agent's `wer.py` (01:21:55 output): `daniel-r ref 1423 hyp 1398 approx err 19.1% sub 227 del 30 ins 15`. [L389]
- [CLAIMED] Re-score after stripping leaked special tokens (01:22:08): Daniel agent-WER 26% -> 19.1% (sub 227, del 30, ins 15); Hannah 20% -> 19.7% (sub 228, del 5, ins 13). Method is an ad hoc diff, "not a standard WER tool". [L392]
- [CLAIMED] Harmless differences ~2-3 points: spelling/numbers ("burned"/"burnt", "eleven"/"11"); script misreads about five lines (<0.5%). Real misrecognitions dominate, substitutions. [L392]
- [MEASURED] Whisper small result tool output (01:32:16): `small daniel-r WER 16.9% rtf 0.33`. Later (01:43:09 output): `small daniel-r WER 16.9% rtf 0.33`, `small hannah-w WER 14.7% rtf 0.29`, `small jana-n WER 35.1% rtf 0.63`; (01:46:53): `small tomas-b WER 43.7% rtf 0.68`. Whisper is `faster-whisper` on the laptop CPU (8 cores, no GPU).
- [MEASURED] Canary (app) WERs as stated by the agent in the comparison tables: Daniel 19.1%, Hannah 19.7%, Jana (cs) 23.5%, Tomas (cs) 36.2%. [L521, L605, L654]
- [CLAIMED] Whisper `small` ~0.33x real time on a 9-minute file (01:32:13 claim: "a 9-minute file takes about 3 minutes").
- [CLAIMED] Conclusion of agent (01:38:58, 01:43:37): on Czech Canary is better (Jana 23.5 vs 35.1; Tomas 36.2 vs 43.7); on English Whisper small is better by 2-5 points (16.9 vs 19.1; 14.7 vs 19.7).
- [DECISION] User (01:26:24): "dont large v3 only turbo". Run restarted with `small` and `large-v3-turbo` only. [L432, L455]
- [CLAIMED] Whisper crash (01:28:57): version mismatch in PyAV audio loader; fixed by loading WAV directly (01:29:36).
- [QUESTION] `large-v3-turbo` never produced a result: turbo weights in cache only 743 MB (may still be downloading) (01:46:58). Stopped at user request (01:47:33).
- [DECISION] User (01:47:33): "ok lets not i need reboot". Whisper stopped; results saved in session scratchpad `out/whisper_cmp.json` (session-scoped, may not survive reboot).

## Issues filed (AnnaMarieM/Therapy_local)
- [DECISION] User (01:19:19): "lets file issues. propose what"; (01:35:29) "open all".
- [CLAIMED] Seven proposed issues; filed as #107-#113 (01:37:08):
  - #107 Special tokens leak into transcripts: Daniel 102 `<|startofcontext|>` tokens; Tomas 46; one segment 53 in a row; Daniel segment 8 is 1,560 characters of them (tool output 01:36:08: `daniel-r segments 35 token_hits 103 longest_chars 1560`).
  - #108 Diarization merges turns: Daniel 106 script turns -> 35 segments; Tomas 92 turns -> 17 segments with a 2,786-character segment; Hannah and Jana fine. Daniel segment 1 runs 54 s and holds both speakers. Breaks the two-speaker assumption of AD-07.
  - #109 Upgrade fails on existing QA vault: `no such table: main.clients in "DROP TABLE IF EXISTS sessions"`; blocks reset and all commands; only data wipe fixes it.
  - #110 Real-voice corpus: English ASR about 19% WER (tracking).
  - #111 Wrong transcripts become wrong facts and names (tracking). Example: Daniel's sister fact "does not care for her father" where the script says all care falls on Daniel.
  - #112 Role assignment falls back with confidence 0 on Czech.
  - #113 NPU: embedder open takes 3-4 minutes; LLM NPU blocked.
- [CLAIMED] Issue #63 "Backend acceleration (NPU/GPU) status" already exists and overlaps #113 (01:34:32).
- [CLAIMED] Issue text: Tomas's segment 2 has tokens; Canary prompt tokens end up in stored segment text; sessions with short clean segments do not leak.

## Decisions and ADR
- [DECISION] User (01:42:55): "canary was picked for its good wer on czech reremember that as adr".
- [CLAIMED] Agent (01:43:21): wrote `docs/AD/AD-18.md` "Canary stays the speech recogniser because of its Czech; Whisper is not added back"; index line in `docs/AD/README.md`. States AD-02 said Canary chosen "for its Czech" with no measurements; AD-18 reaffirms and "confirms" AD-02 rather than superseding.
- [BUG] Agent set AD-18 Status **Accepted** with "2026-11-13 02:45 CET" and the user's name as decider, "because you told me to record it". AGENTS.md says an agent drafts and a person decides; the agent asked to change to Proposed if only a draft was meant. [L638]
- [CLAIMED] Missing from AD-18 at that time: Tomas's Whisper result and large-v3-turbo; record points to #110 for the final table.
- [DECISION] User (01:50:25 - 01:50:12): "commit and push" then "do them". Agent (01:50:25): `docs/MEMORY.md` D14 line added after D7 pointing to AD-18; committed and pushed to `feature/english-pivot` as `1687332`; comment on #110 with four-session Whisper table. [L679, L696, L712]
- [DECISION] User (02:20:16): "it works now" (not further specified in view).

## Earlier (23:52-00:24)
- [CLAIMED] Agent (23:52:16): spawned subagent to build QA APK, install on phone `4a985db2`, ingest four recordings, evaluate. PR #105 still open so corpus not on feature/english-pivot; pulls from `corpus/human-voice-sessions` without switching branches. [L55]
- [DECISION] User (23:57:34): "if possible have the agent run the tests with the app backgrounded and provide an eta every ten minutes". Also (00:23:10) "status?"; (00:24:03) "yes".
- [DECISION] User (00:25:20): pasted monitor output and asked for progress: "how much is transcribed - get logs like this".
- [DECISION] User (00:55:17): "so 10 - 30 more minute?"; (00:56:00 answer) "is it usin the GPU with the fast prefill?" - agent: Gemma steps run on GPU.
