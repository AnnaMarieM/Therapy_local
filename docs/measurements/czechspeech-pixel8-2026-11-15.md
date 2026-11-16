# Pixel 8 (Tensor G3): Czech speech to text, CzechSpeech against Parakeet, Canary, Qwen3-ASR and Gemma E2B, 2026-11-15

Device: Pixel 8, Android 17, 7.7 GB RAM (1.2 to 1.9 GB free during the runs), battery 25 to 37 C. A separate test app (not Mnemothera, not in this repo) ran the models on the phone CPU only: whisper.cpp commit `5922450f` (the one `tools/whisper-android/build.sh` pins) for the Whisper family, sherpa-onnx 2.0.0 for Parakeet, Canary and Qwen3-ASR, LiteRT-LM 0.18.1 for Gemma. The speech and Gemma numbers were all measured on the phone; the PC only started the runs over adb and read the results back. The sections on the word list and on voting across systems are offline analyses on the PC and say so. Test audio: `e2e/corpus/human/jana-n/s01.m4a` (385 s, 897 reference words) and `tomas-b/s01.m4a` (410 s, 962 words). Reference text: the `s01.txt` script with the `THERAPIST:` / `CLIENT:` labels removed.

**Scoring.** Word error rate as in `research/asr-llm-combo`: lower case, diacritics and punctuation removed, word-level edit distance. "Folded" also turns numerals into digits on both sides ("patnácti" = "15"), which is about 0.3 points. Every error rate below comes with its time. "Speech time" is the speech model on the whole recording, model load excluded (about 1.3 s for CzechSpeech); speed is speech time divided by the length of the audio (below 1.0 is faster than real time). The phone temperature changes speed by up to 2x, so it is given where it was read.

## Summary

- **CzechSpeech q4_0** (Moravian AI, a Czech fine-tune of Whisper large-v3-turbo, 474 MB, whisper.cpp, CPU) matches Whisper large-v3 on both recordings and runs at about real time on this phone. Jana 11.8% (11.5% folded), Tomáš 18.6% (18.4%), against Martin's large-v3 11.6% and 17.8% on a GPU.
- With 28 s pieces, greedy decoding and no timestamps it runs between **0.66x real time on a cool phone (25.6 C) and 1.33x on a hot one (about 40 C)**; 0.80x at 33.9 C, 1.0x at about 37 C.
- The other models tried are worse for Czech at this speed: Parakeet ULTRA is 3 to 7x faster but 25.3% / 40.0%, Canary 16.2% at 1.35x, Qwen3-ASR 0.6B unusable (86% / 76%).
- Nothing tried got Jana below 11%: decoding switches, beam search, shorter or overlapping pieces, Gemma E2B correction (guided or not), a Czech word list. Details below.
- Whisper's own word probabilities do predict its mistakes (below 0.5: 3% of the words, 22% of the mistakes, 69% precision), which is usable for highlighting doubtful words.
- Licence: **CC-BY-NC-4.0, non-commercial**; Moravian AI sells a commercial licence. It transcribes Czech only (no translate task).

## Models and files

| Model | Files | Runtime |
|---|---|---|
| CzechSpeech q4_0 | `ggml-czechspeech-q4_0.bin`, 473,992,235 bytes (HF `moravianai/CzechSpeech`) | whisper.cpp, own build with i8mm (Tensor G3 has it) |
| Parakeet TDT 0.6B ULTRA int8 | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8`, 670 MB | sherpa-onnx |
| Canary 1B v2 int8 | `fussraider/canary-1b-v2-sherpa-onnx-int8`, 1.4 GB | sherpa-onnx |
| Qwen3-ASR 0.6B int8 | `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`, 1.0 GB | sherpa-onnx |
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes | LiteRT-LM, CPU |

CzechSpeech must be decoded **without timestamps** (it was trained without them; the card says so). The whisper.cpp wrapper used `no_timestamps`, `no_context`, greedy, 4 threads, and the card's shortened encoder window (`audio_ctx` = ceil(samples/320)+32, rounded up to 16). Martin's saved T4 run of the same model (`transcripts/t4-gpu/cs/e2_moravian_b1`) scores 16.7% on Jana and 30.7% on Tomáš with this scorer, so that run was probably decoded with another setup; the model is not the cause.

## All runs on the phone

Defaults: 28 s pieces cut at the quietest point, greedy, 4 threads. Raw records are in `czechspeech-pixel8-2026-11-15-data/` (`runs-before-reinstall.jsonl`, `runs.jsonl`).

| Start | Recording | Model | Setting | Speech time | Speed | Error | Folded |
|---|---|---|---|---|---|---|---|
| 11-15 16:06 | Jana | CzechSpeech | default (hot phone, ~40 C) | 512 s | 1.33x | 11.8% | 11.5% |
| 11-15 20:32 | Jana | CzechSpeech | default (~37 C) | 383 s | 1.00x | 11.8% | 11.5% |
| 11-15 22:44 | Jana | CzechSpeech | default (**25.6 C**) | **255 s** | **0.66x** | 11.8% | 11.5% |
| 11-15 23:24 | Jana | CzechSpeech | default (33.9 C) | 306 s | 0.80x | 11.8% | 11.5% |
| 11-15 20:06 | Tomáš | CzechSpeech | default (31 to 33 C) | 313 s | 0.76x | 18.6% | 18.4% |
| 11-15 16:16 | Jana | Parakeet ULTRA | default | 74 s | 0.19x | 25.3% | 25.0% |
| 11-15 16:19 | Tomáš | Parakeet ULTRA | default | 82 s | 0.20x | 40.0% | 39.7% |
| 11-15 21:22 | Jana | Canary 1B v2 | default | 521 s | 1.35x | 16.2% | 15.7% |
| 11-15 20:40 | Jana | Qwen3-ASR 0.6B | default | 427 s | 1.11x | 86.4% | 86.2% |
| 11-15 20:48 | Tomáš | Qwen3-ASR 0.6B | default | 466 s | 1.13x | 75.7% | 75.4% |

**Piece length** (CzechSpeech, Jana). Shorter pieces are faster (the shortened encoder window only pays on short audio) but lose context, the same pattern as Martin's per-turn numbers (large-v3 19.2% against 11.6% on Jana).

| Pieces | Speech time | Speed | Error | Folded |
|---|---|---|---|---|
| 28 s | 255 to 383 s | 0.66 to 1.00x | 11.8% | 11.5% |
| 16 s | 385 s | 1.00x | 13.7% | 13.4% |
| 12 s | 318 s | 0.83x | 14.9% | 14.6% |
| 8 s | 323 s | 0.84x | 18.6% | 18.3% |
| 12 s with 2 s overlap, repeated words merged away | 369 s | 0.96x | 19.8% | 19.4% |

The overlap was a naive version (each piece starts 2 s before the quiet point, so mid-word; the merge is by text). It made things worse: substitutions about +20, insertions about +20. A version that also starts every piece at a quiet point and merges by timestamps was not tried.

**Things that changed nothing** (CzechSpeech, Jana, 28 s pieces; speed in the same range, battery 25.6 to 36 C):

| Switch | Speech time | Speed | Error | Folded |
|---|---|---|---|---|
| No random temperature retries | 310 s | 0.81x | 11.8% | 11.5% |
| Suppress non-speech tokens | 308 s | 0.80x | 11.8% | 11.5% |
| Loudness normalisation | 375 s | 0.98x | 11.8% | 11.5% |
| 0.5 s silence around each piece | 365 s | 0.95x | 12.0% | 11.7% |
| Skip pauses longer than 0.7 s | 384 s | 1.00x | 12.4% | 12.0% |
| Beam search 5 | 588 s | 1.53x | 13.3% | 12.9% |
| 5 threads instead of 4 (first 60 s of Tomáš) | 96 s for 60 s | 1.60x | n/a | n/a |
| Threads pinned to the fast cores (first 60 s of Tomáš) | 143 s for 60 s | 2.39x | n/a | n/a |

The same text came out with and without the retries and the non-speech suppression, so the mistakes are not a decoding setting. A names prompt ("Nováková,") made Whisper drop the first sentence (14.7%); CzechSpeech was trained without prompts.

## What the mistakes are

CzechSpeech, 28 s pieces, Jana and Tomáš together (1,859 reference words, 209 mistakes counted):

| Kind | Share | Examples (reference -> heard) |
|---|---|---|
| Ending or spelling variant of the right word | 30% | přijala -> přijal, aspoň -> alespoň, prázdno -> prázdnost |
| Similar-sounding wrong word | 19% | si -> se (4 times), tuší -> zdusí |
| Several words garbled together | 18% | jsme byli na dovolené -> zneběřené, co děláte -> cedláte |
| Completely different word | 13% | povídejte -> vyjde |
| Extra word | 8% | |
| Little word dropped | 6% | mostly "a" |
| Content word dropped | 4% | |
| Name misheard | 1% | Nováková -> diváková (the first sentence of a recording is the weakest) |

Only 2 of 63 ending mistakes are the feminine/masculine past-tense mix-up (přijala -> přijal), so a grammar-gender fix would gain almost nothing.

## Word probabilities

whisper.cpp gives every token a probability. Jana, run at 23:24 (11.8%, 306 s, 0.80x, 33.9 C): 887 heard words, 82 wrong, plus 23 reference words never heard.

| Flag words below | Words flagged | Wrong words caught | Flagged words that were wrong |
|---|---|---|---|
| 0.3 | 0.9% | 9% | 88% |
| 0.5 | 2.9% | 22% | 69% |
| 0.7 | 8.3% | 40% | 45% |
| 0.9 | 17.9% | 61% | 31% |
| 0.95 | 23.7% | 74% | 29% |

So the wrong words cluster among the doubtful ones. If every caught word could be fixed the error would fall from 11.5% to 9.7% (0.5), 8.0% (0.7), 6.1% (0.9): a ceiling, not a prediction.

## Attempts to use that to correct the text

| Attempt (CzechSpeech + Gemma 4 E2B, CPU) | Result | Time |
|---|---|---|
| Gemma rewrites whole pieces, with a names list (only "Nováková," arrived, because adb split the list at the first space; it also went to Whisper as a prompt, which dropped the first sentence) | 14.7% before and after, 2 words changed, both wrong | speech 570 s (1.48x) + Gemma 417 s |
| Gemma gets pieces with the doubtful words (p < 0.9) marked `[[ ]]`, changes outside the marks rejected | 135 doubtful words, 0 changed, 9 of 16 pieces rejected; 11.8% -> 11.8% | speech 365 s + Gemma 271 s = 636 s = 1.65x |
| One doubtful word at a time with its sentence (first 120 s of Jana) | 35 doubtful words, 0 changed | speech 93 s (0.78x) + Gemma 98 s (2.8 s per word) |

E2B answers with the text or word it was given, even for garbles like *zneběřené*. Gemma E4B (3.7 GB) was not tried.

**Czech word list** (offline on the PC, `hermitdave/FrequencyWords` cs_full, 1.7 M words from subtitles, no model): of the 87 wrong words (counted on the surface forms with diacritics; 82 in the folded count above) 17 (20%) are not Czech words (*zneběřené*, *čedláte*, *knelíkem*, *eviento*), and only 3 of 800 right words are flagged (the names Radek, Matějovi, Olomouci). Replacing each non-word by the most frequent word within edit distance 1 to 2 gives 11.5% -> 11.0 to 11.3%. The right word is among the 20 spelling neighbours for 67% of the one-to-one wrong words but is the most frequent one for only 8%: ranking is the problem. Re-scoring candidates with Whisper's own decoder would be beam search in disguise (beam 5 was worse).

**Voting across systems** (offline on the PC, our phone transcript plus Martin's saved transcripts of the same recordings; ROVER-style word voting). Martin's best single system on Jana is 10.8% (large-v3 with a word list, GPU), never below 10%. The best of about 600 combinations reached 8.8% on Jana but that is over-fitted; a combination chosen on the other recording gave 10.0% on Jana and 15.1% on Tomáš; the top 3 to 5 systems chosen in advance gave 9.9 to 10.0% on Jana and 14.9 to 16.1% on Tomáš. CzechSpeech at several piece lengths voted together gave 12.0% (the same model repeats its mistakes). CzechSpeech + Canary + Whisper turbo (Martin's CPU transcripts) gave 9.9% on Jana, at about 2.5 to 3x the compute of one model: not real time.

## Live transcription (first test, not a good result)

A live mode was built in the test app: microphone, voice activity detection, a phrase is cut after a 0.6 s pause and transcribed at once. Played into it in real time (Jana, short phrases, 65 phrases of 2 to 13 s, median 4.6 s): **38.0% error**, model time 349 s for 385 s (0.91x), text appeared a median 8.8 s and at worst 36.6 s after the pause, 127 inserted words from repetition loops ("Vysvětlil vám Radek proč?" four times in a row). Short phrases are the problem (see piece length above), and the loops also fill the queue. Fixes (a cap of 9 tokens per second, a repeat remover, presets with a minimum phrase length of 2, 8 and 14 s) were built, but the Balanced preset then hung for 25 minutes in the next test and the work was stopped; the cause is unknown. Raw record: `live-test-first-run.jsonl`.

## What is established and what is not

Established: the CzechSpeech numbers above on these two recordings, the speed range with the phone temperature, the word-probability table, the failure of Gemma E2B at correcting, the neutral decoding switches.

Not established: anything about spontaneous conversation (both recordings are read scripts, two speakers, about 6.5 minutes each, one phone, one run per setting); CzechSpeech on English; Gemma E4B; a language model or a Czech spell checker with context as a corrector; the live mode with the fixes; sustained speed over a 50 minute session (heat).

## What this means for AD-22 (observations, not a decision)

The Quality workflow of the proposed AD-22 uses Whisper large-v3 q8_0 (1.66 GB, 99 s per 30 s chunk on this phone in Martin's measurement, about 3.3x). CzechSpeech q4_0 gave the same Czech accuracy on these two recordings in 474 MB at 0.66 to 1.5x. The catch is the licence (non-commercial without a commercial licence) and that it only transcribes Czech, so the English text would need a separate translation step (the P-BASE design, a text translator after the Czech transcript). Nothing here tests the translation.

## Files

`czechspeech-pixel8-2026-11-15-data/runs-before-reinstall.jsonl` (3 runs, before the test app was reinstalled), `runs.jsonl` (24 runs), `live-test-first-run.jsonl` (1 run). One JSON object per run: `audioSec`, `loadMs`, `asrMs` (speech time), `rtf`, `options` (every setting), `czech` (the text), `czechRaw` and `werCsRaw` (before a Gemma step), `werCs`, `error`, and for the runs of 23:24 and later `wordProbs` (probability and hex bytes of every token, `--` between pieces). The `werCs` field is the app's own scoring without numeral folding.
