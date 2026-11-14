# ASR x LLM combo test

## Speech recognition (WER vs the script as read; lowest is best)

### cs

| engine | mean WER | jana-n (min-max over lanes) | tomas-b (min-max over lanes) | RTF (CPU) |
|---|---|---|---|---|
| v3-beam10 | 13.9% | 11.5%-11.8% | 15.6%-16.7% | 1.30 |
| v3-slow8-beam5-prompt | 15.9% | 12.3%-12.5% | 19.3%-19.7% | 0.91 |
| v3-slow9 | 16.2% | 11.7%-11.9% | 20.5%-20.5% | 0.68 |
| v3-denoise | 16.2% | 12.5%-12.5% | 19.7%-20.4% | 0.87 |
| turbo-beam10 | 16.7% | 12.8%-12.9% | 19.9%-21.3% | 0.45 |
| turbo-slow9 | 17.9% | 13.0%-13.0% | 22.4%-23.1% | 0.30 |
| turbo-slow8-beam5-prompt | 18.0% | 13.8%-14.3% | 21.7%-22.0% | 0.38 |
| turbo-denoise | 18.6% | 14.2%-14.5% | 22.7%-23.0% | 0.36 |
| v3-slow8 | 18.8% | 14.4%-18.4% | 21.3%-21.3% | 0.65 |
| turbo-slow8 | 19.0% | 14.1%-14.5% | 23.3%-24.1% | 0.31 |
| v3-slow6 | 19.7% | 16.5%-16.5% | 23.0%-23.0% | 0.60 |
| v3-slow7 | 22.5% | 22.1%-23.9% | 21.2%-23.0% | 0.70 |
| turbo-slow7 | 23.0% | 20.8%-22.7% | 24.0%-24.5% | 0.35 |
| turbo-slow6 | 24.6% | 23.0%-23.0% | 26.3%-26.3% | 0.24 |
| v3-slow5 | 26.3% | 20.7%-20.7% | 31.8%-31.8% | 0.49 |
| turbo-slow5 | 30.4% | 24.0%-24.0% | 36.1%-37.7% | 0.26 |

### en

| engine | mean WER | daniel-r (min-max over lanes) | hannah-w (min-max over lanes) | RTF (CPU) |
|---|---|---|---|---|
| turbo-beam10 | 8.9% | 10.0%-10.8% | 7.0%-7.9% | 0.41 |
| v3-beam10 | 8.9% | 10.2%-10.2% | 7.1%-8.3% | 0.70 |
| v3-slow9 | 9.2% | 10.5%-10.6% | 7.4%-8.2% | 0.57 |
| v3-slow8-beam5-prompt | 9.4% | 10.7%-11.0% | 8.0%-8.0% | 0.65 |
| v3-denoise | 9.6% | 11.1%-11.1% | 8.1%-8.1% | 0.53 |
| v3-slow8 | 9.8% | 10.3%-11.4% | 8.7%-9.0% | 0.54 |
| turbo-slow9 | 10.0% | 10.9%-11.6% | 8.5%-9.0% | 0.35 |
| turbo-denoise | 10.1% | 11.9%-12.8% | 7.8%-8.0% | 0.31 |
| turbo-slow8-beam5-prompt | 10.8% | 11.5%-11.7% | 9.6%-10.3% | 0.36 |
| v3-slow7 | 10.9% | 12.6%-12.6% | 9.1%-9.2% | 0.49 |
| turbo-slow8 | 11.4% | 12.5%-12.5% | 10.2%-10.5% | 0.26 |
| turbo-slow7 | 11.5% | 12.7%-13.8% | 9.8%-9.8% | 0.37 |
| v3-slow6 | 13.1% | 14.7%-16.6% | 10.5%-10.5% | 0.43 |
| turbo-slow6 | 13.6% | 15.8%-15.8% | 11.4%-11.4% | 0.19 |
| v3-slow5 | 24.1% | 24.0%-24.0% | 24.0%-24.7% | 0.27 |
| turbo-slow5 | 25.7% | 24.6%-24.9% | 26.7%-26.7% | 0.25 |

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
