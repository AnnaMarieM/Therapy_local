# Bonsai 2 (9B, 27B) vs Gemma 4 E2B / E4B: fact extraction (2026-11-15)

Question: can Ternary-Bonsai-2 9B or 27B replace Gemma 4 E2B / E4B for fact extraction?

## Setup (what was actually run)

- Harness: `e2e/combo/llm.py` unchanged, `--engine script` = perfect transcript (the script as read) with oracle diarization, so only the LLM (roles step + extraction) is tested, not ASR. Lane 1, temperature 0.3, seed 1, ctx 8192, part size 450 words, the app's prompts (`demo/prompts`, Czech prompts for Czech). Same settings for all four models.
- Hardware: 4-vCPU / 16 GB CPU-only cloud container (`-ngl 0`). Timings below are only comparable inside this table; they say nothing about a phone.
- Bonsai: `prism-ml/Ternary-Bonsai-2-{9B,27B}-gguf`, `*-PQ2_0.gguf` (2.4 GB / 7.2 GB), PrismML llama.cpp fork branch `prism` at `6447906`, built from source (stock llama.cpp cannot load PQ2_0). Thinking off (`enable_thinking: false`). The model card recommends temp 0.7 / top_p 0.8 / presence_penalty 1.5 for non-thinking; we kept 0.3 and no penalties to stay comparable. Not tried.
- Gemma: `unsloth/gemma-4-E{2,4}B-it-GGUF` Q4_K_M on stock llama.cpp b11960 (what `bench.py` uses).
- Scoring: the repo's `final-workflow/inputs/checklist.json` (15 hand-picked facts per recording, regex on heading + content). Reading notes below are by hand.
- The 27B ran on `tomas-b` (Czech) and `daniel-r` (English) only: about 1.4 generated tokens/s here, about 1h per recording. A first 27B attempt was killed by the kernel for lack of memory (llama-server's default 8 GB prompt cache on top of the 7 GB model); rerun with `LLAMA_ARG_CACHE_RAM=0`. That is why 27B results are in two files (`bonsai2-27b`, `bonsai2-27b-dr`).
- Single run, one lane, four recordings: a difference of one checklist item is noise.

## Result

Checklist recall (kept items / items):

| model | jana-n (cs) | tomas-b (cs) | daniel-r (en) | hannah-w (en) | all four | tomas-b + daniel-r |
|---|---|---|---|---|---|---|
| gemma4-e2b | 8/15 | 6/14 (1 inverted) | 11/15 | 11/15 | 36/59 = 61% | 17/29 = 59% |
| gemma4-e4b | 10/15 | 11/14 | 11/15 | 13/15 | 45/59 = 76% | 22/29 = 76% |
| bonsai2-9b | 9/15 | 9/14 | 11/15 | 7/15 | 36/59 = 61% | 20/29 = 69% |
| bonsai2-27b | not run | 12/14 | 15/15 | not run | not run | **27/29 = 93%** |

Facts-stage time, tomas-b + daniel-r, this CPU: e2b 496 s, e4b 1000 s, 9B 1338 s, 27B 5243 s.

## Reading notes

- **Output format.** Both Bonsai models ignore "one JSON object per line" and return a markdown-fenced JSON array (the 9B sometimes pretty-printed over many lines). The harness parser (`llm.py parse`) takes only lines starting with `{`, so it dropped 12 of 33 facts on tomas-b and 23 of 37 on jana-n for the 9B. Everything above is re-parsed with `scripts/lenient.py` (extracts every JSON object). Strict-parsed counts are in `scripts/lenient.py` output. The app's parser needs the same leniency, or the prompt needs a format example Bonsai obeys.
- **Bonsai 9B vs E4B.** Worse at Czech. On jana-n it states things that are false or inverted: "Jana se rozhodla pro rozvod v září" (Radek said it), "Jana nemá manžela", "Jana lhala Aničce, že se s tátou hádá" (she lied that they do not argue); garbled facts from a quoted sentence; nonsense headings; stray Polish characters ("úzkość"); a therapist instruction recorded as a patient fact (tomas-b fact 20). English is decent (daniel-r about equal to E4B), but it dropped work/GP/blood-test context. Recall is not better than E2B on the whole set, and it is 3x slower than E2B here.
- **Bonsai 27B.** The best extraction of the four: tomas-b has the fire two years ago, 30 checks a night, the electrician job, the pregnancy and the self-harm thought, the walks, the exposure fear, all correctly attributed; daniel-r 15/15. Still has Czech typos ("Kolejové" for "Kolegové", "Začálo", "Dloužší") and a few odd headings. Output volume is the highest (62 facts on daniel-r), so some are fine-grained duplicates.
- **E4B** misses less than the 9B but uses `session` scope for nearly everything in Czech and invents a little ("divorce will destroy her life" on jana-n). **E2B** misses the most and inverted one checklist item on tomas-b.
- Roles step: all four models assigned therapist/patient correctly on every recording.

## Verdict for the project

- Bonsai 9B does not beat E4B on quality, and it would not be smaller than E2B in practice. Not a replacement.
- Bonsai 27B is clearly the best extractor measured, but 7.2 GB for the weights and 1.4 tok/s on 4 CPU cores rule it out on a phone. It is a candidate for a server or desktop tier, or as a judge/reference for measuring the on-device models.
- Deployment: PQ2_0 and PTQ1_0 run only on the PrismML llama.cpp fork (custom CPU/CUDA/Metal kernels). The app runs Gemma through LiteRT on GPU/NPU (AD-08, AD-09, AD-19, AD-21), and there is no LiteRT path for these weights. A switch would be a new runtime and needs a superseding AD. Only `Q2_0` (2.56 GB for the 9B) loads on upstream llama.cpp b11556+; we did not test that pack.
- Not tested: real ASR transcripts, Bonsai 4B/2B (the closest in size to E2B/E4B), thinking mode, the vendor's sampling settings, MTP/speculative decoding, an actual Android device.
