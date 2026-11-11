# Gemma 4 E4B on the SM8750 NPU: what exists and what stops us (2026-11-11)

Question: can the POCO F8 Pro (SM8750, Hexagon V79) run Gemma 4 E4B on the NPU? Result: **no build exists, and we could not make one on the machine at hand.** Nothing was installed on the phone; the phone was not used.

## 1. Is there a build already?

- `litert-community/gemma-4-E4B-it-litert-lm` holds only `gemma-4-E4B-it.litertlm`, `-gpu`, `-web` (.litertlm and .task). Its E2B sibling also has `_NPU_Uncompiled` and per-SoC files (`Qualcomm_QCS8275`, `Google_Tensor_G5/G6`, `intel_LNL/PTL`); **E4B has none of them**, and no SM8750 file for either size.
- Community: `dfkozlov/gemma-4-E4B-it-litert-lm` (Hugging Face) has `gemma-4-E4B-it_Qualcomm_SM8650.litertlm` (4.5 GB), unofficial and experimental. It is compiled for **SM8650 (Hexagon V75)**, so it does not load on the SM8750 (V79); a compiled NPU context is per SoC. Its card is the only recipe found:
  - weights `google/gemma-4-E4B-it`, exported with litert-torch's `export_hf` NPU pipeline, **patched** (logit softcap kept, plain dynamic-update-slice KV update, mask graphs with only token and time-step inputs, unused last-layer KV inputs removed from prefill);
  - 4-bit per-channel weights (post-training, min-max), 8-bit per-layer-embedding projections, 16-bit activations and KV cache, static ranges calibrated on 12 text prompts and 4 spoken clips; context 4096, prefill 128;
  - compiled with `litert_lm_npu_compiler` and QAIRT 2.50.0; audio encoder, vision, metadata and tokenizer copied from the official E4B file;
  - the patches are **not published**. An int8-activation variant gave unusable output; only 16-bit activations work.
  - Measured by the author on a Xiaomi 14 Ultra: about 12 tok/s decode against 10.7 on the CPU, 0.3 s to first token. That is a small decode gain for a file about 1.7 times the size of the E2B SM8750 one, on a phone older than ours.
- [nmrenyi/mamai#58](https://github.com/nmrenyi/mamai/issues/58): someone else is waiting for the same SM8750 E4B file; it says none is published and gives no recipe.
- LiteRT-LM #774: the generic E4B file on `Backend.NPU()` fails with `TF_LITE_AUX not found in the model`. The generic file cannot simply be pointed at the NPU.

## 2. Can we build it ourselves here?

The E2B build (`scripts/gemma-aot.sh`, branch `ci/gemma-aot-sm8750`, litert-torch 0.10.0, QAIRT 2.50.0.260828) does **not** export a model: its input is Google's `gemma-4-E2B-it_NPU_Uncompiled.litertlm` and it only runs `litert_lm_npu_compiler` on it. **For E4B that input file does not exist**, so the E4B route needs the full export from the Hugging Face weights, which is exactly the patched part above.

Blockers found:

1. **This machine is aarch64.** Tried 2026-11-11 in `python:3.12-slim` (arm64), `pip install litert-torch==0.10.0`: `No matching distribution found for litert-converter==0.5.*` (no wheel for this platform). Python 3.14 (the host's) fails the same way, since the wheels stop at 3.12. The Qualcomm SDK (`ai-edge-litert-sdk-qualcomm`, which downloads QAIRT's x86_64 host libraries) is x86_64 too. The host has no x86_64 binfmt registered, so `--platform linux/amd64` images do not start; the repo's builder only gets x86_64 tools through `qemu-user-static` launchers per tool (AD-04 / `docker/Dockerfile`). Exporting a 16 GB bf16 model and compiling for the HTP under user-mode emulation is not realistic.
2. **The export itself is unproven for Gemma 4 off the shelf.** litert-torch #1290: self-exported Gemma-4-E2B bundles give garbage on SM8550 while Google's own is coherent; long prompts fail (#1344). The E4B author needed four patches and a calibrated quantisation to get working output.
3. **The size.** E2B's compile already ran out of memory on the phone (AD-09); the E4B community file is 4.5 GB (2.26 GB NPU context). We have no measurement of whether E4B's context fits this phone's HTP with the app resident.
4. **The app's own proofs.** Even Google's prebuilt E2B for SM8750 fails proof 2 (`too_few_objects`, `docs/measurements/gemma-npu-eval-2026-11-07.md`). A larger model through the same NPU graph style is not known to pass; the proofs must not be weakened.

## 3. What would work, if we want to pursue it

- An **x86_64 Linux machine with at least 32 GB RAM and about 100 GB disk** (or a CI runner of that size): run the patched `export_hf` NPU pipeline for `google/gemma-4-E4B-it`, then `litert_lm_npu_compiler --backend=qualcomm --soc_model=SM8750` with QAIRT 2.50.0.260828, as `scripts/gemma-aot.sh` does. The patches have to be re-created from the four items above (or requested from the author of the SM8650 build).
- Or wait for Google to publish `gemma-4-E4B-it_Qualcomm_SM8750.litertlm` (mamai#58 tracks the same wish).
- Before spending the effort: decide whether E4B on the NPU is worth it. E4B already runs on the GPU on this phone through the normal ladder (AD-08 branch), at about 19 tok/s decode.

## 4. Not done

No model was exported or compiled, nothing was pushed to the phone, no probe was run. The numbers for the SM8650 build are the author's, not ours.
