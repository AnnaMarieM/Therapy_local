# ASR x LLM combo test

## Speech recognition (WER vs the script as read; lowest is best)

### cs

| engine | mean WER | jana-n (min-max over lanes) | tomas-b (min-max over lanes) | RTF (CPU) |
|---|---|---|---|---|
| v3-beam5 | 14.0% | 11.3%-11.3% | 16.5%-16.9% | 1.05 |
| v3-beam5-prompt | 14.2% | 11.3%-11.9% | 16.5%-17.3% | 1.16 |
| v3-prompt | 14.5% | 11.6%-11.6% | 17.4%-17.6% | 0.75 |
| turbo-beam5-prompt | 16.3% | 12.4%-13.2% | 18.5%-21.1% | 0.44 |
| turbo-novad | 16.4% | 12.5%-12.9% | 20.2%-20.2% | 0.34 |
| whisper-large-v3-turbo | 16.8% | 12.4%-13.5% | 20.6%-21.0% | 0.33 |
| turbo-prompt | 16.8% | 13.4%-13.4% | 20.0%-20.5% | 0.43 |
| turbo-beam5 | 16.9% | 11.9%-13.0% | 21.3%-21.3% | 0.48 |
| turbo-prevtext | 17.2% | 13.7%-13.7% | 20.7%-20.7% | 0.37 |
| turbo-loudnorm | 17.6% | 13.3%-14.3% | 21.5%-21.5% | 0.42 |
| canary-chunk30 | 21.9% | 14.6%-14.6% | 29.2%-29.2% | 0.56 |
| canary-loudnorm | 22.2% | 16.1%-16.1% | 28.4%-28.4% | 0.38 |
| canary-chunk10 | 24.0% | 18.7%-18.7% | 29.2%-29.2% | 0.43 |

### en

| engine | mean WER | daniel-r (min-max over lanes) | hannah-w (min-max over lanes) | RTF (CPU) |
|---|---|---|---|---|
| v3-prompt | 8.4% | 9.8%-9.8% | 6.5%-7.4% | 0.46 |
| v3-beam5 | 8.4% | 9.4%-9.4% | 7.4%-7.5% | 0.66 |
| turbo-prevtext | 8.6% | 10.1%-10.1% | 6.9%-7.4% | 0.32 |
| v3-beam5-prompt | 8.8% | 10.1%-10.3% | 7.4%-7.4% | 0.76 |
| turbo-beam5 | 8.8% | 9.9%-10.1% | 7.5%-7.9% | 0.35 |
| turbo-beam5-prompt | 8.9% | 10.2%-10.2% | 7.5%-7.5% | 0.41 |
| turbo-prompt | 8.9% | 10.1%-10.8% | 7.3%-7.5% | 0.32 |
| turbo-loudnorm | 9.0% | 10.2%-11.1% | 7.4%-7.4% | 0.37 |
| turbo-novad | 9.2% | 10.1%-10.2% | 8.1%-8.6% | 0.35 |
| whisper-large-v3-turbo | 9.2% | 10.6%-10.7% | 7.4%-8.3% | 0.29 |
| canary-chunk30 | 15.3% | 17.0%-17.0% | 13.5%-13.5% | 0.43 |
| canary-loudnorm | 16.7% | 17.8%-17.8% | 15.6%-15.6% | 0.46 |
| canary-chunk10 | 18.2% | 18.7%-18.7% | 17.6%-17.6% | 0.36 |

## Extraction (facts), per ASR engine x model

- **coverage**: share of the facts the same model found on the perfect script (all lanes pooled) that this combo also found. A guide to what ASR errors cost, not a score of the model.
- **quote ok**: the evidence quote really is in the transcript the model saw (low = it makes things up).
- **stable**: how much the four lanes agree with each other (low = flaky).

### cs

| ASR | LLM | mean WER | facts/recording | coverage | quote ok | stable | lanes |
|---|---|---|---|---|---|---|---|

### en

| ASR | LLM | mean WER | facts/recording | coverage | quote ok | stable | lanes |
|---|---|---|---|---|---|---|---|
