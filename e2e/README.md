# E2E speech tests

Synthetic sessions (Piper TTS) -> the app's pipeline -> scored against planted ground truth, Czech and English.

```
corpus/clients/*.txt   ground-truth sheet per client: planted facts per session, repeats (must become mentions), recall queries, negatives
corpus/build.py        sheets -> corpus/sessions/<client>/sNN.json (dialogue + expected facts) + corpus/clients.json   (committed, deterministic)
tts/render.py          sessions -> audio/<client>/sNN.wav, 16 kHz mono, cached by content hash                        (wavs not committed)
host/stt_host.py       same Whisper int8 models as the app via sherpa-onnx on the CI box -> WER/CER only (PR tier)
score/scorer.py        results.json -> report.json/.md per language, thresholds in thresholds-*.json
variants/              prompt variants for A/B runs (`run_device.py --prompts e2e/variants/lang-aware`)
```

10 clients (5 cs: jana-n lucie-s marie-d petr-h tomas-b; 5 en: daniel-r emma-k marcus-t raj-p sofia-l) x 10 sessions, about 2 min of speech each.

## Local use

```
python3 -m venv e2e/.venv && e2e/.venv/bin/pip install -r e2e/requirements.txt
e2e/.venv/bin/python e2e/tts/fetch_voices.py
e2e/.venv/bin/python e2e/tts/render.py                       # all 100, or --only jana-n/s01,jana-n/s02
e2e/.venv/bin/python e2e/host/stt_host.py --size base --language auto --out e2e/results/h.json
python3 e2e/score/scorer.py --results e2e/results/h.json --no-fail
python3 e2e/score/test_score.py                              # scorer tests (an oracle run must score 100%)
```

Edit a sheet, run `python3 e2e/corpus/build.py`, commit the regenerated `corpus/sessions`.

## What is measured

- stt: WER and CER of the stored transcript against the spoken script (diacritics folded).
- fact_recall_pct: planted new facts found among the session's stored facts. extra_facts_per_session: stored facts that match nothing planted.
- repeat_dedupe_pct: repeated statements stored as a mention of the original fact; repeat_duplicated_pct: stored as a second fact.
- query_*: for each recall query, rank of the first planted answer among the app's closest facts / windows (as the search screen gives them).
- negative_top_fact_score: mean best score of questions nothing was said about (should be well below real hits).
- cross_client_leaks: results from another client. Must be 0.

Matching is lexical (token overlap with the planted sentence), so it needs no model and gives the same answer everywhere.
