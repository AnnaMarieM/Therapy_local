# Final workflow: plan, shared definitions and measurements (2026-11-13)

Owner of the decision: Martin Hajný (@hajnina). This plan is shared by two independent workstreams that must produce comparable numbers:
- **Workstream A, T4 (quality lab):** runs the two new pipelines and the controls on a Colab T4 and reports exact results.
- **Workstream B, app + devices:** builds the workflow into the app (QA build `com.therapylocal.app.qa`), installs it on the Poco, the Pixel 8 and the Galaxy Tab S7, and measures it there.
The ADR that describes it is AD-22 (proposal). Evidence from earlier work: `research/asr-llm-combo/README.md`, `reading-notes/`, `device/`.

## 1. The two workflows (what is decided)
Both start with **diarize** (sherpa-onnx pyannote + wespeaker, as AD-06) and work **per turn** (audio cut at the diarizer's turn boundaries; pieces over 30 s are split at the quietest point).

**Fast** (everything is Gemma 4 E2B; no Canary, no Whisper):
1. Diarize. 2. Gemma 4 E2B listens to each turn and returns, in one answer, the **Czech transcript and the English translation**. 3. Roles (E2B): which speaker is the therapist. 4. Facts (E2B) from the English text. 5. If the app language is Czech, translate the facts (and headings) back.

**Quality** (Whisper large-v3 + Gemma 4 E4B):
1. Diarize. 2. Whisper large-v3 (int8, whisper.cpp on the device) transcribes each turn **directly into English** (task translate). There is **no Czech transcript** in this workflow. 3. Roles (E4B). 4. Facts (E4B) from the English text. 5. If the app language is Czech, translate the facts back.

English recordings skip translation in both workflows (the audio is already English; the same models transcribe it).

## 2. Controls (so the two workflows can be judged)
| id | pipeline | why |
|---|---|---|
| **P-FAST** | as Fast above (E2B listens) | the decision under test |
| **P-FAST-E4B** | as Fast, E4B listens and understands | size effect |
| **P-QUALITY** | as Quality above (Whisper translate, E4B) | the decision under test |
| **P-QUALITY-E2B** | as Quality, but roles and facts by E2B | size effect |
| **P-BASE** | Whisper large-v3 transcribes Czech per turn, Gemma E4B translates the text, then roles and facts (E4B) | the earlier design, with the Czech transcript kept |
| **P-CANARY** | Canary 1B v2 transcribes per turn, Gemma E2B translates, then roles and facts (E2B) | what the app does today (plus the pivot) |
Every pipeline is run on all four recordings (Czech: jana-n, tomas-b; English: daniel-r, hannah-w).

## 3. Shared inputs (Workstream A creates them first, B reads them)
Path: `research/asr-llm-combo/final-workflow/inputs/`.
- `turns-timed/<client>.json`: the oracle turns with start/end seconds and speaker (therapist/client). Method: faster-whisper large-v3 with word timestamps, words aligned to the script with `e2e/combo/common.py` `oracle_turns` logic; spot-check at least 5 boundaries per recording by cutting the audio and listening to the transcript of the cut. State the method and its limits in a README next to it.
- `reference-en/<client>.md`: an English reference translation of the two Czech scripts, turn by turn. It is written by an LLM and is a **scoring aid only**, not ground truth; label it so.
- `checklist.md`: for each recording, 10-15 **critical items** a clinician must not lose, each with the exact script sentence, why it matters, and how to tell lost / garbled / inverted / kept. It must include: tomas-b the intrusive thought "Že jí a dítěti ublížím ... Ne že bych chtěl" (the denial must not be inverted), Barbora is pregnant in her fourth month, the electrician in Olomouc, the checking rituals (30 times a day, the stove, the door), the fire two years ago; daniel-r the father aged seventy-three, the hospital in Leeds, the guilt, the sleeping pattern; the equivalent for jana-n and hannah-w. Derive items by reading the scripts (`e2e/corpus/human/*/s01.json`).
- `oracle-facts`: the facts the same Gemma size extracts from the **perfect script** (English, via the same pipeline) are the reference for coverage; reuse `research/asr-llm-combo/facts/ci-cpu/*/script__*` and rerun if the prompt changes.

## 4. What is measured (every pipeline x recording; both workstreams)
**Time** (wall seconds, per stage and total; RTF = seconds / audio seconds): diarize, listen/transcribe/translate, roles, facts, translate-back. Device numbers are the ones that count; T4 numbers are for comparison only. Record peak memory (RSS/PSS), thermal status, backend and model quantisation.
**Quantitative quality**
- Czech transcript WER and CER against the script (numbers folded to words, diacritics folded; `common.wer`) **where a Czech transcript exists** (P-FAST, P-BASE, P-CANARY). For P-QUALITY it is **n/a** (there is no Czech text); never report a WER of English output against the Czech script.
- English translation quality (Czech recordings): WER/chrF of the English output against `reference-en`, labelled "vs LLM reference, indicative". English recordings: normal WER against the script.
- Critical-items checklist: items kept / lost / garbled / inverted, per recording and pipeline. This is the headline safety number.
- Diarization (device): number of turns, speaker-attribution accuracy and mean boundary error against `turns-timed`.
- Facts: facts per recording, coverage against the oracle facts, evidence-quote-in-text rate, lane stability (4 lanes, seeds 1-4, temperature 0.3 as before), roles right/wrong per lane, checklist recall in the facts.
**Qualitative (mandatory, written)**: for every pipeline x recording save the **raw output untouched** (every turn's transcript and/or translation, the roles answer, every fact with its quote) and read it against the script. Write a reading note per pipeline in `reading-notes/final-<pipeline>.md`: what is lost or invented, whether the self-harm item, the names, the numbers and the places survive, whether facts are attributed to the right person, whether it is clean enough to show a clinician. A pipeline that scores well but invents, drops or inverts a critical item counts as worse, and the note must say so.

## 5. Output layout (nested, raw first)
```
research/asr-llm-combo/final-workflow/
├── PLAN.md                    this file
├── inputs/                    turns-timed, reference-en, checklist (A creates)
├── t4/<pipeline>/<client>/    raw turn outputs (jsonl), facts (laneN.md/json), timing.json
├── device/<device>/<pipeline>/<client>/   same layout, from the QA build; device = poco | pixel8 | tabs7
├── RUNS.md                    run ledger (see 6)
└── RESULTS.md                 comparison tables + decision (synthesised after both finish)
```
Reading notes go to `research/asr-llm-combo/reading-notes/final-*.md`.

## 6. Run ledger (RUNS.md): every number traceable
One row per run: id, date/time, who (A or B), where (T4 session / device serial + model name / CI run URL), **git commit** of the code that ran (harness commit; for the app the build commit and the branch), models with file name + quantisation + sha256, options (prompts, seeds, beam, thresholds), start/end, status, and the path to the raw output. Earlier CI runs (round 1-3a) stay in `RUNS.md` too: 43361743399 (cancelled), 43362037847 (baseline, Canary jobs failed), 43363953758 (Canary), 43380472185 (round 2a), 43380517085 (cancelled), 43380701110 (failed, artifact cap), 43392393186 (round 2b), 43418389642 (round 3a), with their head commits (cc495cbec, 4d042187a, 31674aa81, 4afa9155a, 83a05ca2a, 13570883c, 86058fb14, b93c0c612). The final decision table in `RESULTS.md` must cite run ids for every claim.

## 7. Workstream A (T4): steps
0. Inputs (section 3). 1. Feasibility of Gemma 4 audio: which weights and runtime accept audio (the official `google/gemma-4-E2B-it` and E4B are gated; the CI uses `unsloth/gemma-4-E2B-it-GGUF`; check transformers and llama.cpp multimodal/audio support; record exactly what works). If audio cannot be run on the T4 at all, say so with evidence and stop that part; do not substitute another model. 2. P-FAST and P-FAST-E4B: per-turn pieces of at most 30 s; prompt returns the Czech transcript and the English translation; also run a translate-only prompt to see whether the one-pass format hurts; 2 lanes for the audio stage, greedy. 3. P-QUALITY and P-QUALITY-E2B: Whisper large-v3 task translate per turn, beam 5 and greedy, with and without the domain prompt (the app will use the best of these; say which). 4. Controls P-BASE and P-CANARY. 5. Stage 2 for all (roles + facts) with Gemma E2B and E4B via llama.cpp (CUDA) using the app prompts (`demo/prompts`, English), 4 lanes. 6. All measurements and reading notes (section 4), `RUNS.md` rows, `RESULTS-T4.md` with the exact tables. Files are written into the main worktree path; do not commit. The Colab session `therapy-asr` is yours alone (the earlier agent is stopped).

## 8. Workstream B (app + devices): steps
0. Branch: from `origin/feature/english-pivot` (it has the pivot jobs, `processing_language`, the QA API/harness for side-by-side runs; read AD-02, AD-06, AD-17, AD-18, AD-19, AD-21 and AGENTS.md first). Own worktree and branch `feature/quality-workflows`. 1. Feasibility gates, reported before building on them: (a) can the app's Gemma runtime (LiteRT / LiteRT-LM) accept audio for Gemma 4 E2B/E4B, and with which model file; (b) whisper.cpp for Android in the `:batch` process (NDK build, JNI wrapper, patches from `research/asr-llm-combo/device/tools/patches/`, q8_0 large-v3, CPU path; AD-21: same weights on every backend); (c) the translate task in whisper.cpp on short per-turn pieces. If (a) fails, Fast falls back to **P-CANARY** and this is stated and reported at once. 2. Implement: engine ids and `ModelSpec`s (pinned size + sha256), the per-turn pipeline with the two workflows, the translate-back step for display, and a **setting that selects Fast or Quality** (a plain functional screen/QA-API control; the visual design of onboarding belongs to the UX owner, flag it). The Czech transcript step is absent in Quality: the transcript screen must say so, not show English as if it were Czech. Keep every stage timed and logged (`diarize_done wall_ms` style logs, one structured line per stage). 3. Tests: unit tests for the model specs, the workflow selection and the stage timing; CI green. 4. Build the QA APK, install on the three devices (`com.therapylocal.app.qa` only; vault password `12345678`; never touch `com.therapylocal.app`; never uninstall either app; wireless adb notes in `/home/martin/.claude/projects/-home-martin-projects-Therapy-local/memory/reference-android-wireless-adb.md` and the build-and-deploy skill). Large models: `adb push` straight into the QA app's models directory instead of the in-app download if the download is slow; record how. 5. On every device run the four recordings through Fast and Quality (and P-CANARY as control), measure section 4 (time per stage, peak memory, thermal, diarization accuracy against `turns-timed`, facts, checklist) and save raw outputs and reading notes in the layout above. 6. Open a PR to `feature/english-pivot`'s base as stated in section 10; do not merge. Report with exact numbers.

## 9. Rules for both
- The recordings and scripts are synthetic and publishable (AD-20). Never use real client data. Only the QA build; never the real app. Run long jobs as background tasks. Do not edit a merged AD. No AI attribution anywhere (commits, PRs, files): no `Co-Authored-By`, no `Claude-Session`, no generated-with footer, no session link (AGENTS.md, CI `pr-hygiene`). GitHub account: hajnina only.
- Report honestly: what ran, what failed, what is estimated. Do not report a number you did not measure.
- Every result lands with its raw output and a `RUNS.md` row before it is summarised.

## 10. Decision and merge
When both workstreams have reported, the coordinator writes `RESULTS.md` (one table per metric, plus the decision citing run ids) and updates AD-22 (still a proposal in PR #119). **A person accepts AD-22** (Martin) before anything merges; merge order: #118 (research, corpus, AD-20, AD-21), the English-pivot branch, the AD-22 PR, then the app PR. Gates for acceptance, all measured: (1) Fast keeps every critical item or its failures are listed and accepted; (2) Quality does not invert or drop the self-harm denial; (3) Czech display of facts after translate-back keeps the meaning of the checklist items; (4) times on all three devices are known; (5) the app builds, CI is green, and all four recordings complete on all three devices for both workflows.
