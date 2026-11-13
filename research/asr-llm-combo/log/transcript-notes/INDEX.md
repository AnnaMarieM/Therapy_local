# Transcript facts for 2026-11-13: INDEX

How these notes were made. Transcripts are under `/home/martin/.claude/projects/` (the task's `/home/martin/.claude-work/projects/` has no JSONL newer than 2026-11-13). 25 JSONL files were modified today. Each was condensed to user and assistant text, tool results and tool-use commands, then filtered by keyword (numbers with units, errors, WER/tok/s, decisions) and read in slices. Large files (25afbe0f main, 7cd1b88f, 25afbe0f subagents) were sampled, so coverage is partial there. The notes cite file and line numbers (L) or timestamps. Tags: MEASURED = raw output visible in the transcript; CLAIMED = an agent or user said it; DECISION = user instruction or accepted decision; BUG; QUESTION. Live files (still being written at extraction time, 18:04-18:09) are included and marked "live".

| Notes file | Transcript (under /home/martin/.claude/projects/) | Time range (2026-11-12/13) | Summary |
|---|---|---|---|
| live-3edaf737-session.md | -home-martin-projects-Therapy-local--claude-worktrees-frolicking-foraging-aurora/3edaf737-...jsonl (live) | 11-13 17:04-17:09 | Status of PRs and T4 work; user asks to retrieve T4 results and to send a Haiku extractor. |
| live-3edaf737-sub-a2989-t4-retrieval.md | .../3edaf737.../subagents/agent-a2989fa30432ac433.jsonl (live) | 11-13 17:05 | Retrieval of the T4 lab agent's files; only the task prompt was captured by the filter. |
| live-3edaf737-sub-a96e99-audit.md | .../3edaf737.../subagents/agent-a96e99b5bb6848551.jsonl (live) | 11-13 17:06-17:07 | Completeness audit: T4 raw Whisper only; one Poco run; nothing scored or written up. |
| live-3edaf737-sub-a20e86-extractor.md | .../3edaf737.../subagents/agent-a20e86a35157e1e94.jsonl (live) | 11-13 17:09 | This extraction agent's own task record. |
| live-3edaf737-sub-a15de-doc-capture-live.md | .../3edaf737.../subagents/agent-a15de83602e3c4805.jsonl (live) | 11-13 17:08 | Sibling agent capturing findings into docs; little captured. |
| tl-82ee33f9-sub-ac0b13-pixel-wifi-adb.md | -home-martin-projects-Therapy-local/82ee33f9-.../subagents/agent-ac0b132d142ab1e46.jsonl | 11-13 00:08-00:09 | Pixel 8 Wi-Fi adb turned off; whether anyone connected in the last hour is unknown. |
| tl-82ee33f9-sub-ad5c8f-tpu-gpu.md | .../82ee33f9-.../subagents/agent-ad5c8f6ef558a6c10.jsonl | 11-13 00:14-00:16 | Why Canary runs on CPU (RTF 2.66 per owner); `embed_gpu` doc line 114 ms; top shows 900% CPU; no single cause. |
| tl-82ee33f9-sub-a44bc7-engine-priority.md | .../82ee33f9-.../subagents/agent-a44bc70e3459c995c.jsonl | 11-13 00:59-01:02 | Fix for batch engine threads set to background priority (nice 10); Gradle failure then local.properties fix. |
| tl-82ee33f9-sub-a7b0-english-pivot-device-test.md | .../82ee33f9-.../subagents/agent-a7b0803c74c8d6055.jsonl | 11-12 23:55 - 11-13 00:24 | Device run on the Pixel 8 of the English-pivot build; progress and ETA from logs; jobs 4/21 done. |
| tl-82ee33f9-main-english-pivot-pixel8.md | -home-martin-projects-Therapy-local/82ee33f9-13b4-4236-988f-6090542871ad.jsonl | 11-12 22:22 - 11-13 01:42 | English-pivot design decision, AD-17 draft, PR #104 APK publishing, Pixel 8 run stopped at 01:32 (RTF ~3.5x), PR #114 cleanup fix. |
| tl-5d5e38d7-corpus.md | -home-martin-projects-Therapy-local/5d5e38d7-...jsonl | 11-12 22:25 - 23:51 | Human-voice corpus: four sessions, HTML artifact, recordings committed `4b43626`, PR #105 opened without being asked to push. |
| tl-f3cc06b4-main-poco-corpus-whisper-issues-ad18.md | -home-martin-projects-Therapy-local/f3cc06b4-...jsonl | 11-12 23:52 - 11-13 02:20 | POCO ingest of four sessions: 38/38 jobs; WER table Canary vs Whisper small; issues #107-#113; AD-18 (Canary kept). |
| tl-f3cc06b4-sub-ad1ca-poco-ingest-eval.md | .../f3cc06b4.../subagents/agent-ad1ca654ee251ed33.jsonl | 11-12 23:52 - 11-13 01:50 | QA APK build and ingest on POCO; `wer` 26.4% Daniel, 19.7% Hannah (pre re-score). |
| tl-7cd1b88f-main-gpu-float32-ad19-ad-pr116.md | -home-martin-projects-Therapy-local/7cd1b88f-...jsonl | 11-13 02:21 - 05:27 | Gemma/Embedding on GPU; FLOAT32 fix; AD-19 (PR #115 merged `3c55623`); PR #116 merged `21547ec`; wireless adb to 3 devices; locked-screen load times 50-344 s. |
| tl-25afbe0f-main-asr-llm-combo-final-workflow.md | -home-martin-projects-Therapy-local--claude-worktrees-frolicking-foraging-aurora/25afbe0f-...jsonl | 11-13 03:46 - 16:41 (sampled) | Cloud ASR x LLM combo; Whisper large-v3/turbo beat Canary; final-workflow plan, AD-20/21/22, PR #118/#119; scope cut to POCO. |
| tl-25afbe0f-sub-a059-two-prs-ci.md | .../25afbe0f.../subagents/agent-a059b5815e5653267.jsonl | 11-13 15:06 - 15:20 | Opened PRs #118 and #119 and watched CI; `android-test (pixel_tablet, 2)` failure. |
| tl-25afbe0f-sub-a1c6f1-czech-data.md | .../25afbe0f.../subagents/agent-a1c6f1f75dbd51d2d.jsonl | 11-13 11:43 - 11:46 | Catalogue of Czech and English ASR data sources; disk 97% used; parakeet Czech FLEURS 11.01% (web). |
| tl-25afbe0f-sub-a27b-t4-asr-baselines.md | .../25afbe0f.../subagents/agent-a27b4fc4ce2d53a5e.jsonl | 11-13 11:40 - 12:25 | T4 large-v3 greedy and beam-5 WER per recording with seconds; fair word-list rule from coordinator. |
| tl-25afbe0f-sub-a5ac-t4-workstream-a.md | .../25afbe0f.../subagents/agent-a5acf97ce801f722f.jsonl (live) | 11-13 15:26 - 16:30 | T4 quality lab for the final-workflow plan; llama.cpp CUDA build issues; libcublas missing. |
| tl-25afbe0f-sub-aa2f-whisper-turbo-on-device.md | .../25afbe0f.../subagents/agent-aa2f8cdc33b216e2b.jsonl | 11-13 12:55 - 13:09 | Whisper large-v3-turbo q5_0 on device: 573 MB, CPU 13.49 ms per batch run, one chunk WER 0.203; Vulkan/OpenCL build issues. |
| tl-25afbe0f-sub-aabc-app-workstream-b.md | .../25afbe0f.../subagents/agent-aabc683176bf1fda9.jsonl (live) | 11-13 15:27 - 16:26 | App build history (builds 1-5), Gemma audio gate results (E2B/E4B on POCO), MindStore schema bug, scope cut to POCO. |

## Things to check first (highlights)
- AD-18 and AD-19 were written as Accepted, with the user as decider (decided 02:45 CET and 04:14 CET), though the agents drafted them. AGENTS.md says a person decides. See tl-f3cc06b4-main... and tl-7cd1b88f-main... (QUESTION).
- PR #105 was pushed and opened by an agent when the user asked only to commit (tl-5d5e38d7-corpus.md, QUESTION).
- Two WER scales coexist: the harness numbers (T4, e.g. large-v3 daniel-r 9.7%) and the app-side numbers (Canary daniel-r 19.1%). The T4 transcript does not reconcile them (tl-25afbe0f-sub-a27b).
- The task's JSONL location (`.claude-work`) is empty for today; the sessions are in `.claude/projects`.
- Uncertain claims left unverified: tailnet outcome (hk-b172a463), whether anyone used Wi-Fi adb in the hour (tl-82ee33f9-sub-ac0b13), the crash claimed by the user (hk-240b758c).
