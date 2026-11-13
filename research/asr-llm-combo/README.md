# ASR x LLM combo research (2026-11-13)

Goal: the lowest WER, good extracted facts and clean transcripts on the four recorded interviews in `e2e/corpus/human/` (Czech: jana-n, tomas-b; English: daniel-r, hannah-w; synthetic, publishable, AD-20). Judged by reading, not by WER alone.

Everything here is the **raw output** of an experiment. Numbers are recomputed by `e2e/combo/report.py`; do not trust a WER copied into a note without checking the transcript next to it.

## Findings so far (updated as rounds finish; see the logbook for dates and run ids)

WER on the four recordings against the script as read (numbers folded to words, diacritics folded). CI = faster-whisper int8 / sherpa Canary on a 4-vCPU CPU; T4 = Hugging Face fp16. Both agree within about 1 point.

| ASR | Czech | English | note |
|---|---|---|---|
| Whisper large-v3 (beam 5) | 14.0-14.7% | 8.1-8.4% | best, too big for a phone (about 1.5 GB int8) |
| Whisper large-v3-turbo (greedy / beam 5 + prompt) | 16.3-17.1% | 8.6-9.2% | about 0.8 GB int8, the plausible phone candidate; Czech is its weak spot |
| Canary 1B v2 (what the app ships) | 21.9-23.5% | 15.3-15.6% | worse than Whisper large-v3 and turbo on both languages |
| Whisper medium / small | 29% / 42% | 11% / 15% | unusable for Czech |

- Decoding settings (beam, domain prompt, VAD, loudness normalisation, Canary chunk size) move WER by about 0.5-1 point at most. No setting closes the Czech gap between turbo and large-v3.
- Read, not scored: remaining Czech errors are endings and diacritics plus a dozen meaning errors per recording (garbled phrases, names, places, the self-harm sentence). English errors are mostly harmless (digits, dropped fillers) with a few meaning errors ("seventy three" heard as "having a dream").
- Whisper's translate task (Czech audio to English text) is only usable with large-v3.
- ASR is effectively deterministic across lanes (about 1 point); LLM extraction varies by lane (sampling).
- Not yet decided: pivot (Czech to English, English prompts) vs native Czech prompts, the best on-device combination, whether Czech fine-tunes or dictionary/constrained decoding help, per-turn audio cutting, slowdown sweep results. Check `reports/` and the logbook for the latest state.
- Caveat: four recordings, two per language, tuned against themselves. Gains of about 0.5 point are noise. A final candidate needs fresh held-out recordings.
- Constraint: the app ships only sherpa-onnx models and Gemma (LiteRT). AD-18 keeps Canary as the only ASR; changing that needs a new AD.

## Layout

```
research/asr-llm-combo/
├── README.md                      this file
├── transcripts/                   raw ASR output, one file per variant x recording
│   └── <platform>/<lang>/<variant>/<client>[__laneN].{txt,json}
│         platform: ci-cpu (GitHub Actions, faster-whisper int8 / sherpa Canary)  |  t4-gpu (Colab T4, HF transformers fp16)
│         lang:     cs | en          variant: e.g. whisper-large-v3, v3-beam5, turbo-prompt, e2_moravian_b5
│         .txt = the transcript text as produced; .json = segments, options, WER/CER at the time
├── facts/                         LLM output (roles, optional pivot, fact extraction)
│   └── <platform>/<lang>/<asr>__<model+tag>/<client>__laneN.{md,turns.txt,json}
│         .md = readable facts (quote check), .turns.txt = the labelled turns the model saw, .json = options and facts
├── reports/                       generated tables (report.py output), one per round
├── reading-notes/                 the qualitative judgement: what the errors and facts actually look like, per variant
├── rounds/                        the round specs that were run (CI matrices)
└── sources/                       background: training-data sources, papers
```

Logbook (chronological, decisions and results): `log/2026-11-13-asr-llm-combo.md`. Code: `e2e/combo/` (`asr.py`, `llm.py`, `report.py`, `errors.py`, `facts.py`, `archive.py`).

## Adding results

- CI round: `gh run download <id> -p 'asr-*' -p 'llm-*' -D <dir>`, then `python3 e2e/combo/archive.py --kind asr|llm --platform ci-cpu --src <dir> --round <name>`.
- T4 / other GPU: `--platform t4-gpu` (or a new platform name), same command.
- Always file the raw transcript before computing anything, and write a reading note for every variant worth keeping.

## Reading-note template (`reading-notes/<round>-<variant>.md`)

```
# <variant> (<platform>, <date>)
| recording | WER | CER |
Errors that matter: names, places, numbers, the self-harm sentence (tomas-b "dítěti ublížím"), father's age (daniel-r "seventy three")
Hallucinated or dropped sentences:
Clean enough for fact extraction? yes / no, why
Verdict:
```
