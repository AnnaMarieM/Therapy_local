# 25afbe0f main session (worktree frolicking-foraging-aurora) - ASR x LLM combo bench in GitHub Actions, final workflow plan, AD-20/21/22, PR #118/#119
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local--claude-worktrees-frolicking-foraging-aurora/25afbe0f-94ca-432d-8e51-25f9dc228dcc.jsonl (3774 lines, mtime 18:04)
Time range: 2026-11-13 03:46 to 16:41 (plus compaction summary at 07:38:59). Filtered view: about 250 lines kept of 779 condensed records. Large portions of the session are only partly captured (the middle 06:44-15:00 and the later parts are sampled). Treat numbers below as what the filtered view shows.

## Scope and instructions
- [DECISION] User (03:46:28): "pls therers multiple agent on this folder - make sure to do your own worktree". Agent works in `/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora`, branch `worktree-frolicking-foraging-aurora`.
- [DECISION] User (03:48:30): "your goal is to test in the cloud - running e2b with various combinations of whisper small large turbo whatever + other models + e2b + the recordings we recorded with my gf ... run four times in parallel - use parallel lanes to rule out flakiness ... to see which combo works the best. ... best one for the two czech ones and the two english ones".
- [DECISION] User (03:53:10): "dont judge it just by the kpis - make sure to read the full transcripts produced facts etc to do a full qualitative overview and testing various other settings optimizing it to the maximum".
- [DECISION] User (03:55:44): "monitor this logbook it and make sure to optimize and tweak. goal is to bring the WER as low as possible + to make the facts extracted good, transcript clean". Logbook at `log/2026-11-13-asr-llm-combo.md`.
- [DECISION] User (04:17:14): "once you have a clear winner create a adr proposal for it commit to gh crrate pr and install it to all eevices og3r adb. theres three. and then have them each process the four clients."
- [DECISION] User (04:18:33): "first make sure to test ecerzthing on the ci and to find out whicu is bettee whether english logic + translations or native prompts in that language".
- [DECISION] User (15:24:35): two subagents: one on a T4 (Colab) for exact results; one to build the app, ship to phones and tablet and test; plan first; ADR for "the final workflow"; measure time, quality, WER, and fact extraction (quantitative and qualitative).
- [DECISION] User (16:22:46 area): "arright my man. droppin all phones but the poco". Scope cut to the POCO only; plan section 11 records it.
- [DECISION] User (16:24:07): "you're supposed to run it on the NPU as much as you can. If not NPU, then GPU. Yes? Deliver fast." Agent (16:24:22): backend per stage NPU -> GPU -> CPU, keeping whichever is measurably faster with same output; exceptions: Whisper large-v3 CPU wins.
- [DECISION] User (16:25:21): "let's run the whisper on the CPU, it's the default ... run Gemma on GPU ... run it on background, so it doesn't open up on my phone."
- [DECISION] User (16:24:... / 16:23:44): "do not test all the sessions ... do little bit tests ... then we can do a full test once it's done."
- [DECISION] User (16:23:44): "Okay, so what do you plan to take so long with? ... You build the whole thing in a fucking hour on a hackathon". Agent (16:23:55): measurement is the slow part (audio runs are wall-clock; 4 recordings of 6-9 min through three pipelines).

## CI ASR x LLM combo (GitHub Actions, workflow "Bench combo")
- [MEASURED] Baseline run 43362037847 (tool output 06:15:34): "185 jobs succeeded and 28 failed, and all the failures are the known Canary ones" (agent 06:15:37).
- [BUG] Canary jobs fail: `TypeError: OfflineRecognizer.from_nemo_canary() got an unexpected keyword argument 'use_pnc'` (04:07:02 tool output, lane for jana). The first baseline run's Canary jobs all failed at start for this reason (agent 04:12:24).
- [BUG] Whisper jobs died on `av.open(input_file, mode="r", metadata_errors="ignore")` -> `TypeError: open() got an unexpected keyword argument` (`faster-whisper`/PyAV version mismatch; 03:52:42 log; run 43361743399 cancelled).
- [BUG] Round 2b first run: all 44 jobs reusing old large-v3 transcripts failed; artifact lookup capped at 100 artifacts (found 2 of 88+). Fixed by running its own large-v3 ASR (1 lane) (08:22:54).
- [MEASURED] Round 1 cs table header (06:18:03): `whisper-large-v3 | mean WER 14.9% | jana-n 11.8%-11.9% | tomas-b 17.7...`. (table cut in view).
- [MEASURED] Round 2a (08:25:26, ASR settings): Czech WER / English WER: `large-v3, beam 5: 14.0% / 8.4%`; `turbo, default: 16.8% / 9.2%`; `turbo, beam 5 + prompt: 16.3% / 8.9%`. Agent: "ASR settings barely matter: beam 5 and the domain prompt move WER by about 0.5 to 1 point. Whisper large-v3 and turbo still beat every Canary variant by a wide margin."
- [CLAIMED] Round 1 (06:44:01): "Whisper large-v3 and turbo are clearly better than Canary, on both languages. AD-18 only tested Whisper small." "Turbo is the on-device candidate."
- [CLAIMED] Model sizes (09:45:25, from parameter counts, not measured): Whisper small 244M ~250 MB int8; medium 769M ~770 MB int8; turbo 809M ~810 MB int8; large-v3 1.55B ~1.5 GB int8 / 3 GB fp16.
- [MEASURED] Error reading (11:22:49, word-level errors): Czech Tomas large-v3 beam5 WER 16.5% (endings and diacritics: "plamenech"->"plamenach", "nikdo"->"nekdo", "Barbora"->"Barbara"); English Daniel error examples: "[gp] -> [general practitioner]", "[burned] -> [burnt]".
- [CLAIMED] Large-v3 beats turbo on reading for Czech; not on English WER.
- [CLAIMED] Web search (11:25:07): moravianai/CzechSpeech (large-v3-turbo based) model card: 1.8% WER parliament audio, 7.3% FLEURS, 6.5% n... (cut); BuzzASR/czech (large-v3 fine-tune). No independent conversational-Czech benchmark found.

## Final workflow (plan, ADRs)
- [MEASURED] Plan committed at `research/asr-llm-combo/final-workflow/PLAN.md` (branch worktree-frolicking-foraging-aurora, PR #118). (15:27:13)
- [CLAIMED] Fast pipeline: diarize; Gemma 4 E2B listens to each turn and gives Czech transcript + English translation in one pass; E2B decides roles; E2B extracts facts; facts translated back for Czech display. Quality pipeline: diarize; Whisper large-v3 translates each turn straight to English; E4B decides roles and extracts facts; facts translated back. (16:39:40; user 15:21:11 description.)
- [CLAIMED] Inputs done by T4 agent (15:59:31): `turns-timed` covers 4 recordings with 92, 92, 106 and 108 turns; `reference-en` for Jana and Tomas; `checklist.md` with 14-15 items per recording; 28-turn spot check found no leaked words.
- [DECISION] AD-20 and AD-21 accepted by the user (per status text 16:21:56 and 17:04 in the other session). AD-22 is a proposal in PR #119 (branch `docs/ad-22-quality-tiers`).
- [CLAIMED] PR #118: `mergeStateStatus` CLEAN, mergeable MERGEABLE; every check passes except `release-main` skipped by design; the tablet `android-test (pixel_tablet, 2)` failure was a flake (agent's rerun passed) (15:51:37).

## Gate results (Gemma audio in the app; reported by app agent via agent-message)
- [CLAIMED, from agent message] Gate (a): Gemma audio works mechanically: litertlm-android 0.18.1 accepts audio via `EngineConfig(audioBackend = CPU)` plus `Content.AudioBytes(wav)`; no new model; clips up to 30 s. Test on the POCO, tomas-b 198-213 s, greedy, one run each:
  - [MEASURED] E2B CPU: load 3.8 s, 8.9 s for 15 s audio (RTF 0.60).
  - [MEASURED] E2B GPU: load 14.8 s, 4.9 s (RTF 0.33).
  - [MEASURED] E4B CPU: load 12.7 s, 22.3 s (RTF 1.49).
  - [CLAIMED] Czech quality on the self-harm clip is bad: script says "Ze ji a dítěti ublížím. Ublížíte jak? Ne, že bych chtěl." E2B garbled (Cyrillic "потом"); E4B "doesn't want to hurt her" (turned around). Czech-only prompt not enough; domain prompt helps E4B only.
- [CLAIMED] App agent build history (15:59:16): build 1 proved Gemma audio; build 2 fixed the QA `ask` binder-thread bug; build 3 added Whisper and QA flags; build 4 full Fast/Quality code, 1015 unit tests green; build 5 fixed a pre-existing DB bug: schema bump 8 -> 9 made `MindStore.recreate` drop tables parent-first, so the vault never opened again (`no such table: main.clients`). Commit 9f10c5b in git log (17:06:32 of other session) "MindStore: a schema upgrade drops children before parents".
- [CLAIMED] Build 4 APK sha `15a01b98...` installed on POCO, Pixel 8 and tablet (unusable until build 5).
- [CLAIMED] Whisper large-v3 CPU wins over NPU in the app's measurements (16:24:22 agent).
- [CLAIMED] Tomas Fast sanity run started at 17:25 (agent message 16:25:44) on POCO; no result in filtered view.
- [BUG] App agent: `MemoryService` is `android:exported="false"`, so the shell cannot start the queue service; `qa-api.sh` must `am start` the QA activity first, which conflicts with "background only". Agent proposed a QA-only manifest overlay (16:26:10).
- [DECISION] User (16:26:27): "You can unlock the vault using the API." Agent told app agent to unlock via QA API with the standard password.
- [CLAIMED] T4 agent interim (16:27:28), Tomas critical items: Whisper translate (Quality) is weaker than transcribe-then-translate (P-BASE); a harm statement item compared.

## Other
- [DECISION] User (15:42:06 etc.) "Another Claude session sent a message" lines are relays from the app subagent (aabc683176bf1fda9).
- [CLAIMED] Diarization runs through the app's `DiarizeRunner` (sherpa-onnx) via the same backend ladder as Canary; AD-06 says CPU through the ladder; NNAPI only today; "probably not usefully" (16:41:22). [DECISION] User (16:41:15): "can we diarize on the NPU?"
- [MEASURED] Compaction summary (07:38:59) states aim: test ASR x LLM combos on four human-voice recordings with four lanes; find best combo separately for Czech (Jana, Tomas) and English (Daniel, Hannah).
- [QUESTION] Canary 1B v2 results in CI: after the `use_pnc` fix, not in filtered view.
