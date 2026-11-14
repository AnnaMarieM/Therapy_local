# 25afbe0f subagent agent-a27b4fc4ce2d53a5e - GPU ASR experiments on a Colab T4 (baselines, CzechSpeech, parallel lanes)
Source: .../25afbe0f-94ca-432d-8e51-25f9dc228dcc/subagents/agent-a27b4fc4ce2d53a5e.jsonl (664 lines, mtime 16:27)
Time range: 2026-11-13 11:40 to 12:25. Filtered view: 20 lines kept of 314 (sampled).

- [DECISION] Task (11:40:05): GPU ASR experiments on a Colab T4; lowest WER on the four human-voice recordings (cs: jana-n, tomas-b; en: daniel-r, hannah-w), measured with the existing harness so results are comparable with the CI rounds. Do not commit or push; no GitHub/adb/phone.
- [MEASURED] Colab GPU (11:40:30): `GPU 0: Tesla T4`; torch 2.14.1+cu130.
- [CLAIMED] Setup of the baseline (12:04:12): HF transformers fp16, sequential long-form `generate`, language forced, timestamps on, fallback thresholds on, no VAD; NOT the pipeline's chunked mode.
- [MEASURED] Baseline result lines (11:54:30 and 12:04:04), format `variant recording WER CER seconds`:
  - e1_lv3_b1 (large-v3 greedy): daniel-r WER 0.0973 CER 0.064, 76.7 s for 556.4 s audio; hannah-w 0.0752 / 0.0481, 70.8 s for 568.7 s; jana-n 0.1159 / 0.0506, 150.9 s for 384.5 s; tomas-b 0.184 / 0.0842, 95.7 s for 411.5 s.
  - e1_lv3_b5 (large-v3 beam 5): jana-n WER 0.1159 CER 0.0476, 154.1 s; tomas-b WER 0.1778 CER 0.0844, 164.3 s; daniel-r WER 0.09 CER 0.0608, 126.5 s; hannah-w WER 0.0714 CER 0.0453, 115.6 s.
- [CLAIMED] Scoring note: these WERs use the harness's own normalisation (e2e/combo/common.py); they are NOT the same numbers as the app-side WERs reported in other sessions (e.g. Canary 19.1% on Daniel in f3cc06b4). Not reconciled in this transcript.
- [CLAIMED] Agent (11:54:36): e1.py alive; 5 of 16 runs done; `e1.log` empty because `colab exec` returns output only at end.
- [DECISION] Coordinator message (12:08:00): user wants parallelism; run remaining experiments as concurrent processes (3 at most; Colab has 2 CPU cores); (a) other Czech fine-tunes BuzzASR/czech and mikr cv13; (b) atempo slowdown sweep 0.5-0.9 on turbo then large-v3 beam 5; (c) Hunspell word-list constrained-decoding prep.
- [DECISION] Coordinator message (12:21:11): the word list for constrained decoding must NOT contain anything taken from the four recordings' scripts; rebuild from generic Hunspell cs_CZ plus generic Czech given names and towns; rerun e4 and label the old run "script-leaked, not valid". Optional separate labelled variant "names given by the therapist" only.
- [MEASURED] Town/name list fetch (12:22:42): `towns 5347 names failed <HTTPError 429: 'Aggressively rate-limiting to 1 req / min - this rule was created during active wdqs outage'>` (Wikidata query rate limit).
- [CLAIMED] Agent (12:04:12): CzechSpeech model card lists `ufal/parczech4speech-unsegmented` (ParCzech, parliament speech) among its training datasets (also espnet/yodas-granary, google/fleurs).
- [CLAIMED] Agent: tomas-b and jana-n greedy vs beam differences small (jana equal; tomas 0.184 vs 0.178).
