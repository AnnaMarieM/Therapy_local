# Round 2b: English pivot vs native Czech prompts (CI, large-v3 transcript, oracle diarization + roles step, 4 lanes)

Source: `reports/r2b-llm-settings.md`, facts under `facts/ci-cpu/cs/whisper-large-v3__gemma4-*`.

## Numbers (Czech, large-v3 transcript; coverage is vs the same model on the perfect script)
| model + variant | facts/rec | coverage | quote ok | lane stability |
|---|---|---|---|---|
| E4B native | 36.5 | 67.5% | 100% | 78.9% |
| E4B native + robust prompt | 38.8 | 69.6% | 99.4% | 84.3% |
| E4B pivot | 42.8 | 73.3% | 99.7% | 89.3% |
| E4B pivot + robust | 42.4 | 73.4% | 100% | 85.9% |
| E2B native | 32.5 | 51.7% | 90.4% | 62.1% |
| E2B pivot + robust | 42.5 | 75.0% | 97.4% | 90.3% |
| E4B native, part 250 / 800 words | 50.8 / 27.8 | n/a | 99.3 / 97.3% | 82.3 / 73.3% |
| E4B native, temperature 0 | 38.2 | n/a | 100% | 91.9% |

English "+pivot" rows are the same pipeline as native (pivot only applies to Czech): their spread (79-83% coverage for E4B) is the lane/sampling noise, about 2-4 points. Differences smaller than that are not evidence.

## Reading (tomas-b, the self-harm item: script "dítěti ublížím", large-v3 heard "dítě tě ublíží")
- E4B native Czech, 4 lanes: keeps the item but copies the garble: "má myšlenku, že jí a dítě ublíží" / "že by dítě jí a mu ublížilo". The evidence quote is the garbled ASR text; a reader cannot tell who harms whom.
- E4B pivot, 4 lanes: "Intrusive thoughts about harm: Tomas has thoughts that might harm his partner and the baby." The translation repaired the sentence to the right meaning in every lane (also with the robust prompt).
- E2B pivot + robust: wrong direction in all 4 lanes ("she and the baby will harm him"), and 3 of 4 lanes cite a quote that is not in the text. E2B is not safe for this item either way.
- ASR errors survive both ways: "Olomouc" stays "Malomolec" in the pivot facts ("Works as an electrician in Malomolec"); native Czech has "Malomolce".
- Facts from a pivot run are in English for a Czech session. Evidence quotes are checked against the translated text, not the Czech audio transcript, so "quote ok" is weaker evidence there.

## Verdict (so far, one transcript per recording, 4 lanes)
- Pivot beats native Czech prompts for E4B on facts per recording, coverage and stability (+6 coverage points, +10 stability), and for E2B by a large margin (+23 coverage). Part of the gain is the model reading English better; part is that translation smooths ASR errors.
- Cost: an extra translation pass (12 lines per call), English-language facts for Czech sessions, and an AD-17-style round trip if the UI needs Czech.
- Open: Jana (only Tomáš read in detail), the effect with a better ASR transcript, and whether translating back to Czech preserves the repaired meaning.
