# 25afbe0f subagent agent-a1c6f1f75dbd51d2d - catalogue of Czech (and English) ASR training data sources
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local--claude-worktrees-frolicking-foraging-aurora/25afbe0f-94ca-432d-8e51-25f9dc228dcc/subagents/agent-a1c6f1f75dbd51d2d.jsonl (141 lines, mtime 12:46)
Time range: 2026-11-13 11:43 to 11:46. Filtered view: 12 lines kept of 81.

- [DECISION] Task (11:43:41): read-only research, no training, no big downloads; catalogue Czech ASR training sources, then English; target a small on-device ASR under ~300 MB int8, sherpa-onnx on Android; conversational therapy speech matters most. Output to the scratchpad `gpu/data-sources.md`.
- [MEASURED] `df` (tool_result 11:43:45): `/dev/nvme0n1p2 937G 862G 29G 97% /`; `tmpfs 16G 1.8G 14G 12% /tmp`. Also `ls: cannot access '/home/martin/.cache/huggingface/datasets': No such file or directory`.
- [MEASURED] Local search (11:43:45): candidate ASR-related paths found in ~/Downloads and ~/projects (e.g. `martins-whisper-latest-release-v2.zip`, `2026-11-05 10-49-49.whisper-large-v3-turbo-int8-ov.txt`).
- [CLAIMED] Dataset page (name not captured in the view): Czech `cs000` shard 167.604 hours, license cc-by-3.0, manual captions (tool_result 11:44:16).
- [CLAIMED] Sources table from a dataset card (11:44:18): audiobooks 24,535 clips, ~33 h, 13 speakers; audiobooks_new 28,131 clips, ~39 h, 8+ speakers.
- [CLAIMED] Dataset card (11:44:18): train 1,131 h (682,254 segments), dev 10.14 h (5,094), test 20.63 h (11,379); listed license cc-by-2.0 while summary says CC-BY.
- [CLAIMED] Another dataset (11:44:38): about 19.04 h across 156 sessions; CC BY-SA 3.0; single eval-only `test` split.
- [CLAIMED] Web (11:45:09): parakeet-tdt-0.6b-v3 Czech WER on FLEURS 11.01% (greedy transducer, no external LM, punctuation/case removed before scoring); no Common Voice WER for Czech reported.
- [CLAIMED] Agent (11:46:38): catalogue written to `/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/gpu/data-sources.md`. Did not find a parliament database, ParCzech, VoxPopuli or Common Voice locally.
