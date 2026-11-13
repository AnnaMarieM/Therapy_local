# Logbook: ASR x LLM combo test on the human-voice recordings

**Date:** 2026-11-13
**Branch:** `worktree-frolicking-foraging-aurora` (own worktree; other agents work in the main folder)
**Goal:** lowest WER, good facts, clean transcripts, for Czech (Jana, Tomáš) and English (Daniel, Hannah). Judged by reading, not by KPIs alone.
**Corpus:** `e2e/corpus/human/` (synthetic, scripts written by Claude, publishable: AD-20).
**Harness:** `e2e/combo/` (asr.py, llm.py, report.py), workflow `.github/workflows/bench-combo.yml`. Four lanes per cell.

## Round 0: baseline matrix
- Run 43361743399: cancelled after 2.5 min. Every Whisper job failed, `faster-whisper` crashed on an `av` version mismatch when decoding the m4a (`open() got an unexpected keyword argument 'metadata_errors'`). Fix: pass the audio as a numpy array, `pip install -U av`.
- Run 43362037847: baseline. 5 ASR engines (whisper small/medium/large-v3/large-v3-turbo, canary-1b-v2) + Whisper `translate` task for Czech (small/medium/large-v3; turbo has no translate training) x 3 LLMs (gemma4-e2b, gemma4-e4b, qwen3.5-4b) x 4 lanes. Status: running.

## Notes / open questions
- Roles are unknown in the LLM stage (no diarization): all lines are "Speaker 1".
- Whisper here is faster-whisper int8, not the app's sherpa-onnx: compare engines with each other, not with the device.
