# Long-term memory pipeline (debug harness)

Turns session transcripts into a per-client profile of facts, on the phone, with Gemma 4 E2B (LiteRT-LM) and EmbeddingGemma.

## Flow (per session)
1. Cut the transcript into token-sized chunks (default 1000 tokens, 50 overlap; tokens are estimated at 1.4 per word). Embed every chunk, store it in `memory.db` (SQLCipher-encrypted).
2. Gemma extracts facts from each chunk: JSON objects with `category`, `subject`, `fact`, `evidence`. The parser salvages field by field (the model sometimes runs all objects together on one line). A fact is kept only if its quote occurs in the chunk. Up to 3 attempts, the last one with a constrained schema.
3. Each candidate fact is embedded; its 10 nearest known facts are fetched (no cut-off, no thresholds) and Gemma answers `{"duplicate": bool, "of": id}` (greedy, schema-constrained). One fact at a time, so facts committed earlier in the same session are visible.
4. `false` commits a new fact (append-only), `true` records the repeat in `fact_support`.

## Run it
- Build debug, `adb install -r`. Put `job.json`, `prompts/*.txt` (from `demo/prompts/`) and the transcripts under `/sdcard/Android/data/com.therapylocal.app/files/memory/` (`in/` for transcripts). This external folder is the developer root and holds only these inputs; the harness imports each transcript into the vault the first time it is used.
- Unlock the app first (the vault key is needed for the databases and every patient file), then start from the Memory tab, or `adb shell am start -n com.therapylocal.app/.MainActivity --ez memory_lab true`. It runs in a foreground service, so it continues in the background.
- Diagnostics are written only in this debug-harness mode (`job.json` run), to `files/memory/out/` in the developer root: `bench.jsonl` (prefill/decode per call), `decisions.jsonl` (every review with the 10 neighbours shown, contains fact text), `raw/` (raw model replies), `DONE`, and `ERROR` (stack trace of a crashed run). Use synthetic transcripts only. The normal app writes none of these.

## Where the data lives (app mode)
Internal storage `files/memory/` (private to the app, encrypted with the vault key, see [SECURITY.md](SECURITY.md)): `in/<session>.md` transcripts, `cache/<session>/<n>.json` per-chunk extraction cache, `queue.json` the job queue (resumed after the next unlock). Deleting a session or client removes its transcripts and caches too. The facts themselves are in `memory.db`.

## Mock data
`demo/profiles/` (two client narratives), `demo/sessions/` (10 sessions each, about 40 min, plus `.facts.md` ground truth), `demo/small/` (1,500-word excerpts for quick runs). All invented.

## Findings so far (Snapdragon 8 Elite, GPU; NPU is not available, the Qualcomm dispatch library is not in the app)
- Prefill about 1,200-2,000 tok/s, decode about 37 tok/s unconstrained, about 17 tok/s constrained.
- Schema-constrained decoding of the large extraction schema looped on whitespace; small schemas (the review answer) work.
- The embedder rejects more than 1,024 tokens; over-long chunks are embedded in halves and averaged.
- A review call takes about 6 s wall although prefill is 0.3 s: fixed overhead still to be located.
- Unrelated facts score 0.75-0.89 cosine (all start with the client's name), so cosine thresholds are unsafe; the LLM decides.
- Not yet measured: accuracy of duplicate decisions over a full session, and runs on label-free (Whisper-style) transcripts.
