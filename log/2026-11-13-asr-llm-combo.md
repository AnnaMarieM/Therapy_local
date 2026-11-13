# Logbook: ASR x LLM combo test on the human-voice recordings

**Date:** 2026-11-13
**Branch:** `worktree-frolicking-foraging-aurora` (own worktree; other agents work in the main folder)
**Goal:** lowest WER, good facts, clean transcripts, for Czech (Jana, Tomáš) and English (Daniel, Hannah). Judged by reading, not by KPIs alone.
**Corpus:** `e2e/corpus/human/` (synthetic, scripts written by Claude, publishable: AD-20).
**Harness:** `e2e/combo/` (asr.py, llm.py, report.py), workflow `.github/workflows/bench-combo.yml`. Four lanes per cell.

## Round 0: baseline matrix
- Run 43361743399: cancelled after 2.5 min. Every Whisper job failed, `faster-whisper` crashed on an `av` version mismatch when decoding the m4a (`open() got an unexpected keyword argument 'metadata_errors'`). Fix: pass the audio as a numpy array, `pip install -U av`.
- Run 43362037847: baseline. 5 ASR engines (whisper small/medium/large-v3/large-v3-turbo, canary-1b-v2) + Whisper `translate` task for Czech (small/medium/large-v3; turbo has no translate training) x 3 LLMs (gemma4-e2b, gemma4-e4b, qwen3.5-4b) x 4 lanes. Status: running.

- Run 43362037847, ASR stage: every Whisper cell ran, all 16 Canary cells failed (`from_nemo_canary() got an unexpected keyword argument 'use_pnc'`: the pip sherpa-onnx 2.0.1 API has no `use_pnc`, the app's Kotlin API does). Fixed and checked locally on a 20 s clip of Jana (Canary opens with "Kdy je paně Lapová Petra?" for "Dobrý den, paní Nováková": the first seconds are weak).

## Round 1b: Canary only (run 43363953758)
- Workflow now reads `e2e/combo/rounds/current.json` (`plan.py`), so each round is a spec edit + push. Options pass through to `asr.py` / `llm.py` (see their docstrings), an llm item can reuse an earlier run's transcripts (`asr_run`).
- Spec: canary-1b-v2 x 3 LLMs x 4 lanes (28 jobs). Results are merged with run 43362037847 locally.

## Round 1 results (baseline, 4 lanes, WER with numbers folded to words)
| ASR (faster-whisper int8 / sherpa Canary) | Czech WER | English WER | RTF on a 4-vCPU CPU |
|---|---|---|---|
| whisper-large-v3 | 14.7% | 8.5% | 0.87 / 0.52 |
| whisper-large-v3-turbo | 16.8% | 9.2% | 0.33 / 0.29 |
| canary-1b-v2 (the app's engine) | 23.5% | 15.6% | 0.51 / 0.39 |
| whisper-medium | 29.3% | 10.8% | |
| whisper-small | 42.3% | 15.0% | |
- Lanes agree to within about 1 point: ASR is effectively deterministic, so ASR tuning rounds run 2 lanes. LLM lanes differ (sampling).
- This contradicts the reading of AD-18 that Whisper is worse on Czech: that test used Whisper **small**. large-v3 and turbo are far better than Canary on both languages (Czech 15-17% vs 23.5%).
- **Read, not scored.** Czech large-v3 errors are mostly endings and diacritics, but clinically important ones remain: Tomáš "dítěti ublížím" became "dite te ublizi", "Olomouci" became "malomolce", the wife's name "Barbora" became "Barbara"/"Probora", "třicetkrát za večer" became "30x".
- English large-v3: the errors that matter are few ("seventy three" heard as "having a dream", "year 4" as "earful", "younger" as "angry"); the rest are digits and dropped fillers.
- Whisper translate (Czech audio to English text): large-v3 is faithful with small slips ("sporák" became "switch"); medium and small are unusable ("a stomach ache", "the head of the control", "1 pm"). Large-v3 is far too big for a phone, so this is a ceiling, not a candidate.
- Facts, Gemma E4B on the perfect Czech script: 29 facts for Jana, mostly right; slips: an empty fact ("told the headmistress"), the mother called "Matka Jana", a first-person quote kept as is.
- Facts, E4B on large-v3 Czech (Tomáš): ASR errors turn into wrong facts ("Probora je těhotná", "Pracuje jako elektrikář v Malomolce", "Zdošel terapii"); 6 of 41 evidence quotes are not in the text; **round 1 had no roles, so the therapist's homework instructions became "facts about the patient" (#39-41)**. The self-harm item survived but garbled ("Že jí a dítě tě ublíží").
- Gemma E2B invents evidence quotes far more often than E4B (quote ok 56-86% vs 74-99%).
- Fix to the test: round 2 adds the app's roles step (oracle diarization by word alignment to the script, then the roles prompt decides who is the therapist, then Therapist:/Patient: lines).

## Round 2a: ASR settings (run 43380472185; 2 lanes, deterministic)
turbo and large-v3 with beam 5 / domain prompt / both / condition_on_previous_text / VAD off / loudnorm, Canary with chunk 10 / 30 s and loudnorm.

## Round 2b: LLM settings, with roles (large-v3 transcripts from run 43362037847 + the perfect script)
native vs pivot (English logic) x robust prompt, E4B and E2B, part size 250/450/800, temperature 0.

## Notes / open questions
- Roles are unknown in the LLM stage (no diarization): all lines are "Speaker 1".
- Whisper here is faster-whisper int8, not the app's sherpa-onnx: compare engines with each other, not with the device.
