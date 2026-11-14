# Shared inputs (Workstream A, 2026-11-13)

All four recordings are synthetic and publishable (AD-20).

## turns-timed/<client>.json: oracle turns with start/end seconds

Method (T4, Colab session `therapy-asr`, scripts in `final-workflow/tools/`):
1. faster-whisper 1.2.1, large-v3, CTranslate2 `int8_float16`, beam 5, word timestamps, no VAD, language forced to the recording's; raw words in `words/<client>.json`.
2. The heard words are aligned to the script words with `difflib.SequenceMatcher` on folded keys (the same logic as `e2e/combo/common.py` `oracle_turns`, but keeping the script **turn index** instead of only the speaker). Only exactly matched words set `start` (first exact word) and `end` (last exact word); substituted words only fill `heard`.
3. `cut_start` / `cut_end` are what the per-turn pipelines cut: the word edge moved into the gap by at most 0.35 s, never more than half of the gap (so neighbouring pieces never overlap).
4. `who` is the script speaker (therapist / client): this is **oracle diarization**. No diarizer ran on the T4 (diarization timing and accuracy are device-side, Workstream B).
5. Pieces over 30 s are split at the quietest 100 ms of their middle half (implemented in `tools/g4.py` `split_piece`). The longest piece in the four recordings is 14.5 s, so no split was ever needed.

Share of script words that matched exactly (they set the boundaries): jana-n 92.1%, tomas-b 84.6%, daniel-r 91.0%, hannah-w 93.3%. No turn is interpolated (`n_interpolated` is 0 everywhere).

Spot check (`spotcheck.json`): 7 turns per recording (first, last and five evenly spread, 28 in all) were cut with `cut_start`/`cut_end`, transcribed again with large-v3 and compared with the script turn. 9 of 28 are word-for-word correct; the others differ by ordinary ASR errors (for example "Jak spíte?" heard as "Jak zpítá?"). In 17 of 28 both the first and the last word match. In the remaining 11 the first or last word differs because of an ASR error inside the word, not because speech of a neighbouring turn leaked in or the turn was cut off: in none of the 28 pieces does a word of the previous or the next turn appear. **Limit:** this is checked by re-transcribing the cut, not by a human listening to it; a boundary can be 0.1-0.3 s off (the oracle boundaries come from Whisper's word timestamps, which are good to roughly that). Use them as an oracle for diarization error with that tolerance.

## reference-en/<client>.md: English reference translation of the two Czech scripts

Turn for turn (92 + 92 turns), written by an LLM. **A scoring aid only, not ground truth**: WER/chrF of a translated output against it is "vs LLM reference, indicative". English recordings need no reference (the script is the reference).

## checklist.md / checklist.json: critical items

14-15 per recording, derived by reading the scripts, including T10 (tomas-b: "Že jí a dítěti ublížím ... Ne že bych chtěl", the denial must not be inverted). `checklist.json` has the Czech and English regex patterns used for the first-pass scoring; `checklist.md` is the readable table.

## oracle facts

The facts the same Gemma size extracts from the perfect script are `research/asr-llm-combo/facts/ci-cpu/{cs,en}/script__gemma4-e{2,4}b+roles[+pivot]/` (4 lanes, round 2b prompts; for the Czech recordings `+pivot` = the script translated to English by the model first). The prompts in `demo/prompts` did not change since then (git log of `demo/prompts`: last change 9479f18 on 2026-11-12, before the oracle runs of 2026-11-13). Coverage in `RESULTS-T4.md` uses those; the T4 run reruns the oracle once too ("P-ORACLE-T4") to separate the llama.cpp build / GPU effect from the pipeline effect.
