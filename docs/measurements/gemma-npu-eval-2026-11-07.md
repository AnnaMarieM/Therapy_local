# Gemma 4 E2B on the SM8750 NPU: worse than CPU, or rejected for an unrelated reason? (2026-11-07)

Branch `experiment/gemma-npu-eval` (from `feature/npu-qualcomm` 821eeca). QA-only change: `ProofLabActivity` got per-case sampler, sentence-count and "real window" cases. `LlmProof`, the ladder and the catalogue are untouched. One phone: POCO F8 Pro, SM8750, Android 16, `litertlm-android` 0.18.1, QAIRT 2.50. NPU file = our own compile (`gemma-4-E2B-it_ours_SM8750.litertlm`, sha256 d020b654...); GPU and CPU = `gemma-4-E2B-it.litertlm`. All texts are made up. Raw replies: `gemma-npu-eval-data/prooflab_{npu,gpu,cpu}_{len,win}.json`; scoring scripts beside them. Every run produced its complete result file (no USB drop).

## 1. Research (before experimenting)

I found nothing that compares NPU against CPU output quality for Gemma 4, nothing on system-instruction handling on the NPU, nothing on how the NPU build is quantised against the CPU file, and nothing on the NPU sampler. Four searches, so "not found", not "does not exist". What exists:

- LiteRT-LM #3508, https://github.com/google-ai-edge/LiteRT-LM/issues/3508 : DenisovAV (opened 2026-09-06), then john-rocky, Zyach, ashish-frozo. Gemma 3 270M/1B bundles lose every prefill chunk after the first (prefill signature 128) on Qualcomm and Intel NPU. They say Gemma 4 E2B is fine on SM8750 and Lunar Lake. Maintainers (nireekshak, matthewchan-g, yichunk) acknowledged and reassigned; no root cause. Turning off the hardware cache update gave byte-identical output.
- litert-torch #1290, https://github.com/google-ai-edge/litert-torch/issues/1290 : Zyach, Galaxy S23 (SM8550): self-exported Gemma-4-E2B bundles give garbage; Google's own `gemma-4-E2B-it_NPU_Uncompiled.litertlm` is coherent at the same shape; long prompts (over about 450 tokens) still fail (#1344); the author withdrew his own mask-size explanation. Other SoC, self-exported bundles, no root cause.
- LiteRT-LM #4001, https://github.com/google-ai-edge/LiteRT-LM/issues/4001 : Gemma 4 E4B on Adreno 840 GPU scrambles copied digits past about 2,048 tokens with default FP16 activations; FLOAT32 fixes it. GPU, not NPU. It matches the character damage I see on our GPU (section 3), not proven to be the same cause.
- jdshah, https://dev.to/jdshah/what-i-learned-by-dissecting-gemma-4-e2b-itqualcommsm8750litertlm-o85 : the SM8750 file is 2.8 GB against 2.4 GB for the generic file (QNN graphs, Hexagon V79), 41.7 against 24.5 decode tok/s, int4 mentioned only in passing. Speed and size only, no quality comparison.
- Qualcomm Dragonwing guide, https://dragonwingdocs.qualcomm.com/tutorials/gemma-litert-lm-on-iq8 : Gemma 4 E2B runs on the NPU and produces text; no quality measurement.

## 2. Why does proof 2 fail? (our NPU file, 9 prompts, greedy and the app's sampler)

Instruction = `LlmProof.SYSTEM2`. `sN` = the first N made-up sentences of a pool (s3 = the shipped proof-2 text, 134 tokens). `sNu` = the same instruction inside the user message, no system instruction. Max 400 output tokens. A cell is "ok" when the reply holds at least min(N,3) objects, each with exactly 3 keys and no repeated key, and the last value of each is an exact quote (the shipped `checkFacts` rules, key names free). "app" = top-k 20, top-p 0.9, temperature 0.1, seed 1. Times are wall time of the call.

| case | prompt tokens | NPU greedy | NPU app | GPU greedy | GPU app | CPU greedy | CPU app |
|---|---|---|---|---|---|---|---|
| s1 | 97 | ok (1 obj, 1.8s) | ok (1 obj, 1.8s) | ok (1 obj, 2.6s) | ok (1 obj, 2.6s) | ok (1 obj, 5.6s) | ok (1 obj, 5.2s) |
| s2 | 115 | FAIL 1 object(s), need 2 (1 obj, 1.6s) | FAIL 1 object(s), need 2 (1 obj, 1.4s) | ok (2 obj, 4.0s) | ok (2 obj, 3.7s) | ok (2 obj, 8.9s) | ok (2 obj, 8.8s) |
| s3 | 134 | FAIL 1 object(s), need 3 (1 obj, 1.8s) | FAIL 1 object(s), need 3 (1 obj, 1.8s) | ok (3 obj, 6.0s) | ok (3 obj, 5.6s) | ok (3 obj, 12.4s) | ok (3 obj, 12.4s) |
| s4 | 151 | FAIL 1 object(s), need 3 (1 obj, 2.7s) | FAIL 1 object(s), need 3 (1 obj, 2.6s) | ok (4 obj, 8.8s) | ok (4 obj, 8.6s) | FAIL keys per object 4/4/4 (3 obj, 17.1s) | ok (4 obj, 14.4s) |
| s6 | 184 | ok (3 obj, 4.9s) | ok (3 obj, 4.8s) | ok (4 obj, 7.9s) | ok (5 obj, 9.3s) | FAIL duplicate key (1 obj, 10.1s) | FAIL duplicate key (1 obj, 9.7s) |
| s10 | 280 | ok (5 obj, 7.1s) | ok (5 obj, 6.9s) | ok (4 obj, 9.4s) | ok (3 obj, 9.3s) | FAIL duplicate key (1 obj, 16.6s) | FAIL duplicate key (1 obj, 16.3s) |
| s16 | 427 | FAIL no complete object (0 obj, 7.1s) | FAIL no complete object (0 obj, 6.9s) | FAIL no complete object (0 obj, 9.5s) | FAIL no complete object (0 obj, 9.4s) | ok (4 obj, 23.0s) | ok (5 obj, 21.0s) |
| s3u | 130 | FAIL 2 object(s), need 3 (2 obj, 2.6s) | FAIL 2 object(s), need 3 (2 obj, 2.6s) | FAIL 2 object(s), need 3 (2 obj, 3.5s) | FAIL 2 object(s), need 3 (2 obj, 3.6s) | ok (3 obj, 14.7s) | ok (3 obj, 13.5s) |
| s10u | 276 | ok (3 obj, 7.0s) | ok (3 obj, 6.8s) | FAIL 1 object(s), need 3 (1 obj, 7.5s) | FAIL 1 object(s), need 3 (1 obj, 7.4s) | FAIL last value not a quote (obj 4) (4 obj, 21.1s) | ok (4 obj, 21.9s) |

What this shows:

- **Not chunking.** The NPU passes at 97 tokens (s1), fails at 115 (s2, still under one 128 chunk), fails at 134 to 151 (s3, s4), then passes at 184 and 280 tokens (s6, s10), which cross one and two chunk boundaries. The reference proof-1 prompt (156 tokens) also passes. A lost-chunk fault would not pass at 280.
- **Not sampling, as far as can be seen.** On the NPU the app's sampler and greedy gave byte-identical replies in all 9 cases. On the GPU they were identical in 2 of 9, on the CPU in 4 of 9. That suggests the NPU ignores or never leaves the argmax at temperature 0.1 (not proven; I did not test a high temperature). It also means greedy proofs are not a handicap on the NPU: the app's own sampler gives the same answers there.
- **The failing shape is the NPU's own, but not the NPU's alone.** At s2 to s4 the NPU writes ONE object (Czech key names, extra keys, several facts merged) instead of one object per sentence. The CPU, which skips the proofs, produces the same merged object with repeated keys at s6 and s10 (greedy and app sampler), and a 4-key object at s4 greedy; the GPU does it at s10u and s16. Under the shipped `checkFacts` rules the CPU would fail 3 of the 9 greedy prompts (s4, s6, s10) and the NPU 5 of 9 (s2, s3, s4, s16, s3u). The merged-object failure is the one the proof was written to catch (the Adreno 650 case), but on a 3-sentence prompt every backend of this model produces it for some N.
- Putting the instruction into the user message (s3u) does not help the NPU (2 objects) and does not help the GPU; it helps the CPU. So system-instruction handling on the NPU is not the cause either.

Conclusion for step 2: the proof-2 failure is a property of this model on this short prompt, in which the NPU is somewhat more fragile than CPU or GPU (fails at and around the shipped size 3, passes at some other sizes). It is not a chunk-boundary effect and not a sampler effect. The shipped 3-sentence size is close to a coin toss per backend, so it cannot tell "the NPU computes garbage" from "this prompt is brittle".

## 3. Real work: five made-up Czech windows (the app's own extraction prompts)

`cs/extract.system.txt` and `cs/extract.user.txt` (the prompts the app uses for a Czech client) around five made-up session windows of 820 to 940 words (2,344 to 2,519 prompt tokens; the files are `gemma-npu-eval-data/win1..5.txt`), top-k 20, top-p 0.9, temperature 0.1, seed 1, max output 1,600 tokens, context 4,096 on all three, one call per cell, unconstrained (attempt 1 of `Pipeline.extractFacts`; attempts 2 and 3 were not run). Each window has 16 to 19 facts the client states (87 in total, defined in `facts.py`), plus therapist remarks and small talk that should be skipped. The windows are about 15 to 20 percent shorter than the "about 1000 words" asked for. Engine load: NPU 10.7 s, GPU 13.2 s, CPU 7.9 s.

How the columns are scored (`score.py`, `an_win.py`): complete JSON = the whole reply is a sequence of objects with no stray text and no cut-off. Facts found = a regex on heading, content and evidence of any object (diacritics ignored), spot-checked against the replies I read in full (NPU 1 to 5, CPU 1 to 5, GPU 1 to 5; counts can be off by one or two). Exact-quote = evidence occurs in the window after dropping diacritics and case, punctuation kept. "Kept by app" = evidence passes `FactParser.evidenceHolds` (its own normalisation: lowercase, every character outside a-z0-9 becomes a space, so letters with diacritics count as separators), and "facts via kept" counts only facts found in such objects, i.e. what attempt 1 would store. Claim matches evidence is only a word-overlap heuristic (stems of 5 letters shared between content and evidence), not a judgement of meaning. Unsupported numbers: none in any reply (checked automatically); invented people or places: none found by reading.

| backend | win | prompt tok | s per call | out tok | complete JSON | objects (distinct) | exact 3 keys | facts found / stated | exact-quote evidence | quote from therapist | claim shares words with evidence | unsupported numbers |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NPU | 1 | 2508 | 28.3 | 1521 | no (cut at 1600 or malformed) | 18 (18) | 18/18 | 13/17 | 13/18 | 0 | 17/18 | 0 |
| NPU | 2 | 2372 | 28.8 | 1600 | no (cut at 1600 or malformed) | 20 (9) | 9/9 | 6/16 | 8/9 | 0 | 9/9 | 0 |
| NPU | 3 | 2452 | 20.3 | 1099 | yes | 16 (16) | 16/16 | 14/16 | 14/16 | 0 | 15/16 | 0 |
| NPU | 4 | 2519 | 16.5 | 871 | yes | 19 (19) | 0/19 | 13/19 | 0/19 | 0 | 0/19 | 0 |
| NPU | 5 | 2344 | 17.6 | 947 | yes | 13 (13) | 13/13 | 10/19 | 9/13 | 0 | 11/13 | 0 |
| GPU | 1 | 2508 | 31.7 | 1232 | yes | 20 (20) | 20/20 | 16/17 | 4/20 | 0 | 20/20 | 0 |
| GPU | 2 | 2372 | 38.7 | 1600 | no (cut at 1600 or malformed) | 23 (22) | 22/22 | 9/16 | 6/22 | 0 | 22/22 | 0 |
| GPU | 3 | 2452 | 27.9 | 1100 | yes | 15 (15) | 15/15 | 12/16 | 3/15 | 0 | 15/15 | 0 |
| GPU | 4 | 2519 | 43.0 | 1577 | no (cut at 1600 or malformed) | 20 (20) | 20/20 | 14/19 | 6/20 | 1 | 20/20 | 0 |
| GPU | 5 | 2344 | 34.6 | 1270 | yes | 17 (17) | 17/17 | 13/19 | 5/17 | 0 | 17/17 | 0 |
| CPU | 1 | 2508 | 97.5 | 1333 | yes | 16 (16) | 16/16 | 16/17 | 15/16 | 0 | 16/16 | 0 |
| CPU | 2 | 2372 | 95.3 | 1386 | yes | 19 (19) | 19/19 | 14/16 | 16/19 | 0 | 18/19 | 0 |
| CPU | 3 | 2452 | 81.7 | 1176 | yes | 16 (16) | 16/16 | 14/16 | 16/16 | 0 | 16/16 | 0 |
| CPU | 4 | 2519 | 103.8 | 1577 | no (cut at 1600 or malformed) | 17 (17) | 17/17 | 14/19 | 13/17 | 0 | 17/17 | 0 |
| CPU | 5 | 2344 | 105.7 | 1600 | no (cut at 1600 or malformed) | 21 (12) | 12/12 | 9/19 | 11/12 | 1 | 12/12 | 0 |

| backend | mean s per call | complete JSON | facts found | exact 3 keys | exact-quote evidence | word overlap claim/evidence |
|---|---|---|---|---|---|---|
| NPU | 22.3 | 3/5 | 56/87 | 56/75 | 44/75 | 52/75 |
| GPU | 35.2 | 3/5 | 64/87 | 94/94 | 24/94 | 94/94 |
| CPU | 96.8 | 3/5 | 67/87 | 80/80 | 71/80 | 79/80 |

Facts the app's grounding filter would keep, attempt 1 only (from `FactParser.evidenceHolds`, windows 1 to 5):

| backend | objects kept / distinct | facts stated that are in a kept object |
|---|---|---|
| NPU | 42 / 75 | 33 / 87 (38 %) |
| GPU | 39 / 94 | 32 / 87 (37 %) |
| CPU | 70 / 80 | 56 / 87 (64 %) |

What the replies show (read in full):

- **NPU.** Mostly well formed, correct names, numbers and dates, evidence mostly exact. Faults: window 2 collapses into a loop (the same "Opatrnost v práci" object eleven times until the 1,600-token cap; 9 distinct objects, 6 of 16 facts); window 4 has headings and content but no `evidence` key in any of 19 objects, so attempt 1 yields nothing the filter can keep; window 1 ends with a stray brace. Word-level damage in content ("Přímo a rodiče", "sestrą"-type slips, "Eva se bojí stáže, protože se těší..."), and some distortions ("Petra má dva životopisy"). 16 to 29 s per call.
- **GPU.** The facts are mostly there (64 of 87 by regex), but the quoted text is damaged in nearly every object: dropped commas, mangled words ("Chodkím na dlouhé procházky podky podél řeky", "Přla jsem př Boří mě vytáze dveří"). Only 24 of 94 evidences are exact, 39 of 94 survive the app's filter. This looks like the FP16 corruption described in #4001, and it is the kind of output a GPU proof built on short prompts did not catch. 28 to 43 s per call.
- **CPU.** Best: 71 of 80 evidences exact, 70 of 80 kept, 56 of 87 facts kept. Faults of its own: window 5 loops (12 distinct of 21; the same object eight more times) and puts therapist words in evidence once; windows 4 and 5 hit the cap; a few headings are odd. 82 to 106 s per call.
- Every backend writes evidence longer than the 15 words the prompt allows, and every backend repeats objects at times.

## 4. Conclusion

What the evidence supports:

1. The proof is not rejecting the NPU for an unrelated reason such as chunking or the sampler. Both are ruled out on this file and phone (section 2). But the proof also is not a measure of real-work quality: a 3-sentence, 134-token prompt is a coin toss for this model on every backend. The CPU fails the same checker on 3 of 9 prompts and is exempt only because it is the fallback.
2. On real-length windows the NPU is NOT at the level of the CPU in this sample, and also not clearly better than the GPU. Compared with the CPU, it keeps fewer grounded facts (33 against 56 of 87 through the app's own filter), has less exact evidence (44 of 75 against 71 of 80), and in one of five windows lost the `evidence` key altogether and in one it looped. Compared with the GPU the NPU is on a similar level in facts kept (33 against 32) with much better quotes (the GPU damages the text), and is about 1.6 times faster than the GPU and 4.3 times faster than the CPU (mean 22 s, 35 s, 97 s per window).
3. So the NPU is faster but worse than the CPU on this work, with the failure mode (missing key, loop) one the existing retry (attempts 2 and 3) may or may not rescue; I did not test that. The GPU passes the proofs yet produces the least trustworthy quotes, which a proof of the same kind cannot see.

What it does not support or I did not verify:

- n = 5 windows, one call each, deterministic seed, one phone, one NPU file (the prebuilt file was not rerun in step 3 or step 2; the earlier test is in AD-06). Differences of one or two facts are inside the scoring error of the regex method; the NPU against GPU facts comparison in particular is a tie. The size of the gap to the CPU (33 against 56) is large enough to be real for this sample, not necessarily in general.
- Windows are 820 to 940 words, prompts under 2,520 tokens. Nothing was run near the 4,096 NPU context limit.
- "Evidence matches its claim" is a word-overlap heuristic only; I judged meaning by reading, not by a scored rubric. Invented facts were looked for by reading and by number check, not by a full semantic audit.
- The NPU not leaving greedy decoding is an inference from identical outputs, not a measurement of the sampler. I did not test other temperatures.
- Attempts 2 and 3 of the app's extraction (second plain try, constrained schema) were not run, nor were the engine process, the guard and the real pipeline: ProofLab runs the engine in the main process without `NpuBundleCheck`.
- The cause of the GPU damage (FP16, #4001) and of the NPU weakness (quantisation of the NPU build against the CPU file) is not established; I found no source that describes the NPU build's weight precision.
- Czech prompts only; the earlier MemProbe test used the English prompt on the same transcript and produced 5 objects with exact quotes in 8 s, so prompt language may matter on the NPU (not tested here).
