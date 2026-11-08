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

## Sittings: realistic multi-voice sessions (Edge TTS), for looking at what the app produces

`e2e/sittings/` holds therapist + patient conversations with a different neural voice per person, so diarization has distinct speakers to find. It is a viewing tool, not a pass/fail gate: the report shows the transcript with speakers, the guessed roles, and the memories (facts) stored per session and per client. The Piper corpus above is untouched.

```
sittings/scripts/*.txt   the sittings (dialogue, voices, effects, planted facts as [[f1|Heading|spoken sentence]]); committed
sittings/build.py        scripts -> sessions/<id>.json + clients.json (committed, deterministic)
sittings/render.py       Edge TTS per turn -> 16 kHz mono PCM16, seeded gaps, overlap, gain, reverb, noise -> audio/<id>.wav + <id>.truth.json (turn timings, expected roles)
sittings/run_device.py   push to the QA build, start the harness, wait for out/DONE, pull results.json and the key logcat lines
sittings/report.py       results.json + truth -> report.md / report.json
sittings/test_report.py  the report against an oracle results.json (no device)
```

Edge TTS is an online service (Microsoft): the scripts are synthetic, no patient data. Audio, the per-turn cache and results are not committed.

```
e2e/.venv/bin/pip install -r e2e/requirements.txt
e2e/.venv/bin/python e2e/sittings/build.py            # only after editing a script
e2e/.venv/bin/python e2e/sittings/render.py           # all sittings; --only petr-k_s01,tom-b_s01; prints duration and level per speaker, flags empty turns
python3 e2e/sittings/test_report.py                   # report code check, no device

# QA build installed, vault unlocked (password 12345678), screen on. Never the real app: the runner refuses anything but com.therapylocal.app.qa.
e2e/.venv/bin/python e2e/sittings/run_device.py --out e2e/sittings/results/run1          # --only ids, --no-reset
python3 e2e/sittings/report.py --results e2e/sittings/results/run1/results.json --title "PR 1 run"
```

The app creates a client with one speech language, so `run_device.py` runs the Czech and the English sittings as two jobs and merges them. Sittings: `petr-k_s01..03` (Czech 1:1, patient first and talking most, Czech with English words), `eva-s_s01..03` (rapid one-word answers with back-channels and interruptions, quiet and far with reverb and noise, one voice dictating a note, a known limitation of AD-07: the diarizer is always asked for exactly two speakers, so an invented second speaker is expected there; every other sitting expects exactly two), `david-m_s01..02` (English 1:1, one 20+ minute follow-up), `tom-b_s01` (English, two similar male voices). `petr-k`, `eva-s` and `david-m` have several sittings, so repeats and mentions across sessions show in the per-client section. `results.json` needs the PR 1 `DevResults` (segment speakers, `speakerMap`); from an older build the report still works and says the speaker parts are empty.
