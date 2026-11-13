# ASR x LLM combo test

## Speech recognition (WER vs the script as read; lowest is best)

### cs

| engine | mean WER | jana-n (min-max over lanes) | tomas-b (min-max over lanes) | RTF (CPU) |
|---|---|---|---|---|
| whisper-large-v3 | 14.6% | 11.7%-11.7% | 17.6%-17.6% | 0.95 |

### en

| engine | mean WER | daniel-r (min-max over lanes) | hannah-w (min-max over lanes) | RTF (CPU) |
|---|---|---|---|---|
| whisper-large-v3 | 8.3% | 9.9%-9.9% | 6.8%-6.8% | 0.50 |

## Extraction (facts), per ASR engine x model

- **coverage**: share of the facts the same model found on the perfect script (all lanes pooled) that this combo also found. A guide to what ASR errors cost, not a score of the model.
- **quote ok**: the evidence quote really is in the transcript the model saw (low = it makes things up).
- **stable**: how much the four lanes agree with each other (low = flaky).

### cs

| ASR | LLM | mean WER | facts/recording | coverage | quote ok | stable | lanes |
|---|---|---|---|---|---|---|---|
| script | gemma4-e4b+roles+pivot | oracle | 43.9 | 94.0% | 99.1% | 92.1% | 4 |
| script | gemma4-e2b+roles+pivot+robust | oracle | 41.8 | 93.2% | 95.1% | 91.0% | 4 |
| script | gemma4-e4b+roles+pivot+robust | oracle | 46.0 | 92.8% | 99.2% | 90.5% | 4 |
| script | gemma4-e2b+roles+pivot | oracle | 38.8 | 89.1% | 95.1% | 85.9% | 4 |
| script | gemma4-e4b+roles | oracle | 36.1 | 88.9% | 99.0% | 85.3% | 4 |
| script | gemma4-e4b+roles+robust | oracle | 37.8 | 86.8% | 99.3% | 82.5% | 4 |
| script | gemma4-e2b+roles+robust | oracle | 31.2 | 78.6% | 95.6% | 72.9% | 4 |
| whisper-large-v3 | gemma4-e2b+roles+pivot+robust | 14.6% | 42.5 | 75.0% | 97.4% | 90.3% | 4 |
| script | gemma4-e2b+roles | oracle | 30.0 | 71.4% | 97.4% | 62.2% | 4 |
| whisper-large-v3 | gemma4-e2b+roles+pivot | 14.6% | 37.0 | 64.9% | 96.9% | 82.7% | 4 |
| whisper-large-v3 | gemma4-e2b+roles | 14.6% | 32.5 | 51.7% | 90.4% | 62.1% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+part250 | 14.6% | 50.8 | nan% | 99.3% | 82.3% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+part800 | 14.6% | 27.8 | nan% | 97.3% | 73.3% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+pivot+robust | 14.6% | 42.4 | 73.4% | 100.0% | 85.9% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+pivot | 14.6% | 42.8 | 73.3% | 99.7% | 89.3% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+robust | 14.6% | 38.8 | 69.6% | 99.4% | 84.3% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+temp0 | 14.6% | 38.2 | nan% | 100.0% | 91.9% | 4 |
| whisper-large-v3 | gemma4-e4b+roles | 14.6% | 36.5 | 67.5% | 100.0% | 78.9% | 4 |
| whisper-large-v3 | gemma4-e2b+roles+robust | 14.6% | 35.4 | 51.5% | 94.4% | 66.3% | 4 |

### en

| ASR | LLM | mean WER | facts/recording | coverage | quote ok | stable | lanes |
|---|---|---|---|---|---|---|---|
| script | gemma4-e2b+roles+robust | oracle | 49.6 | 95.0% | 96.8% | 93.4% | 4 |
| script | gemma4-e2b+roles | oracle | 49.0 | 94.4% | 97.2% | 92.6% | 4 |
| script | gemma4-e2b+roles+pivot | oracle | 50.1 | 94.1% | 95.8% | 92.3% | 4 |
| script | gemma4-e4b+roles | oracle | 48.6 | 92.4% | 99.8% | 90.0% | 4 |
| script | gemma4-e4b+roles+robust | oracle | 48.2 | 91.4% | 100.0% | 88.6% | 4 |
| script | gemma4-e2b+roles+pivot+robust | oracle | 50.4 | 91.3% | 95.5% | 88.5% | 4 |
| script | gemma4-e4b+roles+pivot+robust | oracle | 48.0 | 91.0% | 100.0% | 88.3% | 4 |
| script | gemma4-e4b+roles+pivot | oracle | 48.0 | 89.4% | 99.5% | 85.9% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+part250 | 8.3% | 60.4 | nan% | 99.4% | 91.0% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+part800 | 8.3% | 35.5 | nan% | 99.0% | 88.0% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+robust | 8.3% | 49.8 | 84.0% | 99.8% | 91.5% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+pivot+robust | 8.3% | 49.0 | 82.8% | 99.8% | 88.8% | 4 |
| whisper-large-v3 | gemma4-e2b+roles+robust | 8.3% | 49.6 | 82.7% | 94.9% | 90.8% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+pivot | 8.3% | 49.1 | 81.0% | 100.0% | 89.2% | 4 |
| whisper-large-v3 | gemma4-e2b+roles | 8.3% | 46.8 | 80.7% | 91.7% | 90.4% | 4 |
| whisper-large-v3 | gemma4-e2b+roles+pivot | 8.3% | 46.4 | 79.5% | 90.3% | 89.8% | 4 |
| whisper-large-v3 | gemma4-e2b+roles+pivot+robust | 8.3% | 47.8 | 75.5% | 94.8% | 87.3% | 4 |
| whisper-large-v3 | gemma4-e4b+roles+temp0 | 8.3% | 48.2 | nan% | 100.0% | 93.1% | 4 |
| whisper-large-v3 | gemma4-e4b+roles | 8.3% | 49.1 | 79.0% | 100.0% | 89.4% | 4 |
