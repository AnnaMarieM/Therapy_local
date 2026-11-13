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

## Notes / open questions
- Roles are unknown in the LLM stage (no diarization): all lines are "Speaker 1".
- Whisper here is faster-whisper int8, not the app's sherpa-onnx: compare engines with each other, not with the device.
