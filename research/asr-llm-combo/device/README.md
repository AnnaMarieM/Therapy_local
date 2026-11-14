# Whisper large-v3-turbo on the three Android test devices (2026-11-13)

One 30 s chunk per run (first 30 s of `e2e/corpus/human/tomas-b/s01.m4a`, Czech; and of `daniel-r/s01.m4a`, English), 16 kHz mono wav (`raw/cs30.wav`, `raw/en30.wav`), whisper.cpp master (ggml 0.29) and whisper.cpp + llama.cpp b11869 ggml 0.27 (for the Hexagon NPU), standalone binaries in `/data/local/tmp/asr-bench/` (removed afterwards). No app was touched.
Weights: `ggml-large-v3-turbo-q5_0.bin` (574 MB) on every backend; `q8_0` (874 MB) only where q5_0 cannot run (Hexagon NPU supports Q4_0/Q8_0/MXFP4/K-quants, not Q5_0), and then also on CPU for a like-for-like comparison.

WER: `tools/wer.py` uses `e2e/combo/common.py` `wer()`. The 30 s cut has no reference of its own, so the reference is the script prefix whose length (within hyp length +-12 words) gives the lowest WER. Treat it as a sanity check (is the backend broken?), not an absolute number. CS 0.15-0.20, EN 0.058 on every working backend; the same text everywhere = no silent accuracy break.

## Devices
| | SoC | RAM (free at start) | Android | threads used |
|---|---|---|---|---|
| Tab S7 SM-T970 (192.168.1.7) | Snapdragon 865 (SM8250), Adreno 650 | 5.8 GB (2.2 GB) | 13 | 4 |
| Poco 2510DPC44G (192.168.1.15) | Snapdragon 8 Elite (SM8750), Adreno 830, Hexagon v79 | 11.4 GB (2.0 GB) | 16 | 6 |
| Pixel 8 (192.168.1.11) | Tensor G3, Mali-G715 | 7.7 GB (0.35 GB) | 17 | 4 |

## Results, CS 30 s chunk, beam 5 unless noted (total wall time = load + mel + encode + decode)
| device | backend | quant | s / 30 s | RTF | peak RSS | WER | note |
|---|---|---|---|---|---|---|---|
| Poco | **Hexagon NPU (ggml-hexagon, HTP v79)** | q8_0 | **16.5** (greedy 11.3) | 0.55 | 0.23 GB | 0.189 (greedy 0.176) | encode 3.1 s, decode 10.9 ms/step |
| Poco | NPU | q5_0 | 52.7 | 1.76 | - | text same | q5_0 matmul not supported on HTP, falls to CPU; no gain |
| Poco | CPU (i8mm build) | q8_0 | 20.4 | 0.68 | 1.10 GB | 0.189 | encode 13.0 s, decode 2.6 ms/step |
| Poco | CPU (dotprod build) | q5_0 | 33.7 (greedy 18.8) | 1.12 | 0.82 GB | 0.203 | |
| Poco | GPU OpenCL (Adreno 830) | q5_0 | 54.4 (greedy 20.3) | 1.8 | 0.23 GB | 0.203 | encode 4.6 s, decode 39 ms/step |
| Poco | GPU OpenCL | q8_0 | 77.4 | 2.6 | 0.26 GB | 0.149 | |
| Poco | GPU Vulkan | q5_0 | fails | | | | driver: `createComputePipeline ErrorUnknown` (mul_mat_vec_q5_0) with and without int-dot |
| Pixel | CPU | q5_0 | 61.5 (greedy 46.1) | 2.0 | 0.82 GB | 0.203 | |
| Pixel | CPU | q8_0 | 35.2 | 1.17 | 1.11 GB | 0.189 | |
| Pixel | GPU Vulkan (Mali-G715) | q5_0 | 88.5 (greedy 77.6) | 2.9 | 1.53 GB | 0.203 | encode 66 s |
| Pixel | GPU Vulkan | q8_0 | 91.2 | 3.0 | 1.86 GB | 0.203 | |
| Tab | CPU | q5_0 | 59.7 / 120.2 (two runs) | 2-4 | 0.81 GB | 0.203 | noisy: device was shared (load avg 2-4 before my runs), one run thermal status 2 |
| Tab | CPU | q8_0 | 72.2 | 2.4 | 1.11 GB | 0.189 | |
| Tab | GPU Vulkan (Adreno 650) | q5_0 | segfault at model load | | | | also with `-nfa` |
| Tab | GPU OpenCL (Adreno 650, OpenCL 2.0) | q5_0 | fails | | | | kernel compile error: `dot_acc_sat_4x8packed_ss_int` undeclared |
| Tab | NPU | | not possible | | | | Hexagon v66; ggml-hexagon needs v73+ |

English chunk (daniel-r, beam 5, WER 0.058 everywhere): Poco NPU q8_0 7.9 s, Poco OpenCL q5_0 18.1 s, Poco CPU q5_0 19.6 s, Pixel Vulkan q5_0 77.3 s, Tab CPU q5_0 88.6 s.

Thermal: `dumpsys thermalservice` status 0 after every run except one Tab run (status 2). Runs are 15-120 s; the screen was not kept on and nothing was killed (no OOM). Longer runs (full recording) not done.
Memory: all fit. Tensor G3 had only 350 MB free but the CPU q5_0 run (peak 0.82 GB, mmap) ran fine; Vulkan on Mali needed 1.5-1.9 GB.

## NPU path, what it took
- Qualcomm AI Hub `Whisper-Large-V3-Turbo` is float-only, published for 8 Elite / 8 Gen 3 / 8 Gen 1 etc., and runs through QNN/QAIRT plus Qualcomm's own app code (not a plain ONNX). Not tried: no QNN runtime or driver code, a few hours was not enough. Tab (865) is not in the list.
- Worked instead: llama.cpp b11869 Android release already on the Poco has `libggml-hexagon.so` + `libggml-htp-v79.so` (ggml 0.27). I built whisper.cpp against that ggml (headers from the llama.cpp b11869 source, `common-ggml.cpp` F8 lines removed) and linked to the device's own libggml*.so. Two small patches were needed (see below). `-dev 1` selects HTP0; unsigned PD and FastRPC worked from the adb shell, no root.
- Patches: (1) KV cache must live in a CPU buffer (`WHISPER_KV_CPU=1`, one line in `whisper_kv_cache_init`), otherwise ggml aborts ("buffer HTP0 cannot run the operation CPY"). (2) OpenCL prebuilt crashes in whisper (null backend_ctx in `buffer_type_get_alignment`); I added `ggml_cl_init(dev)` in `ggml_backend_opencl_device_get_buffer_type` for my own OpenCL builds.
- Not attempted: Pixel Tensor TPU (no third-party Whisper-turbo path found; NNAPI is deprecated on Android 17), onnxruntime NNAPI/XNNPACK, sherpa-onnx (does not do beam search for whisper as far as known; not checked here).

## Layout
`raw/<device>_<backend>_<lang>_<beam>.txt` = transcript (stdout), `.err` = whisper timings + `time -v` (RSS), `.thermal` = thermal status after run. `tools/` = build script (host clang + NDK r27c sysroot on the aarch64 laptop, no NDK compiler needed), run scripts, wer.py. Scratch sources/binaries are in the session scratchpad only.

## Versions, so the result can be reproduced
- whisper.cpp: master tarball of 2026-11-13, commit `5922450fbce1ed9d1648babd0d5ac4520055b436` (WHISPER_VERSION 1.9.5, ggml 0.29). Taken as a tarball from codeload (no repository metadata); the hash is the tar's pax comment.
- Hexagon NPU: llama.cpp Android arm64 release **b11869** (commit `d327bf1f0780f87b8c01b367b61853b0c291d7c4`, ggml 0.27, `GGML_HEXAGON` backend marked "experimental", HTP skeleton `libggml-htp-v79.so`). It was already on the Poco in `/data/local/tmp/llama-b11869/` (release asset, exact file name not recorded). sha256 of the libs used are in `tools/lib-sha256.txt`. The whisper.cpp for that run is the commit above with its `ggml/` replaced by llama.cpp b11869's `ggml/` (byte-identical, checked with `diff -rq`) plus patches 02 and 03.
- Patches (unified diffs, verified to apply with `patch -p1` to a pristine tarball): `tools/patches/01-whisper-master-opencl-init-backend-ctx.diff` (OpenCL crash fix), `02-whisper-kv-cache-cpu-buffer-env.diff` (`WHISPER_KV_CPU=1` keeps the KV cache in a CPU buffer, needed for the NPU), `03-whisper-common-ggml-drop-f8e4m3.diff` (build fix for the older ggml). `tools/build-commands.sh` has the exact cmake invocations and the toolchain layout; `tools/build.sh` is the cross-compile wrapper (host clang + NDK r27c sysroot).
- Models (not in the repo, hashes in `tools/model-sha256.txt`):
  - `ggml-large-v3-turbo-q5_0.bin`, 574041195 bytes, sha256 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`, https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin
  - `ggml-large-v3-turbo-q8_0.bin`, 874188075 bytes, sha256 `317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1`, https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin
- Audio excerpts are in `raw/cs30.wav`, `raw/en30.wav` (small, kept).

## How to rerun one chunk on a device
1. Build as in `tools/build-commands.sh` (the CPU build needs only the NDK sysroot and clang; no GPU libs). Find the serial with `adb devices -l`.
2. `adb -s <serial> shell mkdir -p /data/local/tmp/asr-bench`, then `adb -s <serial> push <whisper-cli> <model.bin> raw/cs30.wav /data/local/tmp/asr-bench/` (adb push ran at about 4 MB/s here, so a model takes 2-4 min; check `df /data` first).
3. CPU: `adb -s <serial> shell "cd /data/local/tmp/asr-bench && /system/bin/time -v ./whisper-cli -m ggml-large-v3-turbo-q5_0.bin -f cs30.wav -l cs -nt -t 4 -bs 5 -bo 5 -ng"` (`-t 6` on the Poco; `-bs 1 -bo 1` for greedy). Wall time and peak RSS come from `time -v`; `whisper_print_timings` gives load, encode and decode.
4. NPU (Poco only): put the device's `libggml*.so` (llama.cpp b11869 release `lib/`) plus the `hx2` whisper-cli and libwhisper.so in one dir, then `LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. WHISPER_KV_CPU=1 ./whisper-cli -m ../ggml-large-v3-turbo-q8_0.bin -f ../cs30.wav -l cs -nt -t 6 -bs 5 -dev 1` (`-dev 1` is HTP0; device 0 is OpenCL, which crashes unpatched).
5. Thermal: `adb -s <serial> shell dumpsys thermalservice | grep 'Thermal Status'`. Score: `python3 -I tools/wer.py cs <transcript.txt>` (`en` for English). `tools/run.sh`, `run2.sh` and `batch*.sh` are the wrappers used (they hard-code the scratchpad path and serials).
6. Clean up: `adb -s <serial> shell rm -rf /data/local/tmp/asr-bench`. Do not touch the app packages.

## Round 2: Whisper large-v3 (not turbo), q8_0 (2026-11-13, afternoon)
Model: **`ggml-large-v3-q8_0.bin` does not exist upstream** (ggerganov/whisper.cpp has only `ggml-large-v3.bin` f16 and `ggml-large-v3-q5_0.bin`). I quantised it myself: downloaded `ggml-large-v3.bin` (3095033483 bytes, sha256 `64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2`, equals the upstream LFS hash) and ran the host-built `whisper-quantize ggml-large-v3.bin ggml-large-v3-q8_0.bin q8_0` (whisper.cpp commit above, native aarch64 clang build, 5 s). Result 1656538283 bytes, sha256 `24bc434f372355688ab9a623077a63e5361a1c41f4d8d648977e39f9b060f09e` (also in `tools/model-sha256.txt`).
Same binaries and patches as round 1 (+ patch 04 for the encoder-on-NPU test). Chunks: `raw/cs30.wav` (first 30 s), `raw/cs_harm30.wav` (195-225 s of tomas-b, contains "Že jí a dítěti ublížím" at about 205 s, located with a host turbo run), `raw/en30.wav`. Files are `raw/v3_*`.
WER for `cs_harm30` uses `tools/wer2.py` (reference window searched in the script, start words 480-600), still only a sanity number.

| device | backend | beam 5, CS s / 30 s | RTF | greedy CS s | EN s | harm chunk s | load s | enc / dec s (beam 5 CS) | peak RSS | WER CS b5 / greedy | WER EN | WER harm |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Poco | CPU q8_0 (t6) | 21.4 | 0.71 | 19.9 | 20.5 | 25.4 | 1.9 | 7.4 / 11.3 | 2.58 GB | 0.095 / 0.135 | 0.043 | 0.197 |
| Poco | Hexagon NPU q8_0 | 48.7 | 1.62 | 36.6 | 23.9 | 46.8 | 1.5 | 2.0 / 42.9 | 0.96 GB | 0.095 / 0.162 | 0.043 | 0.197 |
| Poco | NPU encoder + CPU decoder | **failed** | | | | | | | | | | |
| Pixel 8 | CPU q8_0 (t4) | 98.8 | 3.3 | 90.8 | 95.5 | 125.5 | 6.1 | 15.1 / 72.6 | 2.55 GB | 0.095 / 0.135 | 0.043 | 0.197 |
| Tab S7 | CPU q8_0 (t4) | 114.3 | 3.8 | 71.4 | 72.5 | 132.9 | 4.5 | 34.3 / 68.7 | 2.58 GB | 0.095 / 0.135 | 0.043 | 0.197 |

(encode/decode seconds are whisper's own timers, so they do not add up exactly to the total; the Pixel dipped to thermal status 1 in later runs, so its numbers are slightly pessimistic; the Tab was shared and noisy as before. Nothing was OOM-killed; free RAM on the Pixel is small but 2.6 GB RSS ran, because the model is mmapped.)

Turbo q8_0 vs large-v3 q8_0, CS beam 5 (s per 30 s chunk): Poco CPU 20.4 vs 21.4 (same binary; the two models share the same encoder, yet the turbo run measured encode 13.0 s against 7.4 s for large-v3, so the turbo Poco CPU run was evidently disturbed or throttled and the near-equal totals are not a fair comparison: on a clean run the turbo CPU total should be about 7.4 s encode + 2.5 s decode + load, roughly 12 s; not re-measured); Poco NPU 16.5 vs 48.7; Pixel CPU 35.2 vs 98.8; Tab CPU 72.2 vs 114.3 (noisy). Peak RSS 1.1 vs 2.6 GB on CPU, 0.23 vs 0.96 GB on the NPU. WER of the chunk: turbo q8_0 0.189, large-v3 q8_0 0.095 (Czech); English 0.058 vs 0.043. The 32-layer large-v3 decoder is what costs time at beam 5 (decode 43 s on the NPU, 11 s on the Poco CPU, 73 s on the Pixel).
All devices and the NPU give identical transcripts at beam 5 (byte-identical text on Poco CPU, Pixel, Tab; the NPU text has the same WER).

Self-harm sentence (`raw/v3_*_harm_*.txt`, same on every device): `... Jaká? Že jí a dítě tě ublížím. Ublížíte jak? Ne, že bych chtěl. To je hrozné, nikdy bych to neudal. Ale ta myšlenka přijde, když krájím chleba na dostajím úschodu a pak se bojím, že to bude samo. Takže je to myšlenka, která vás těsí.` So large-v3 q8_0 keeps the meaning ("... dítě ... ublížím", then the therapist's "Ublížíte jak?"), but "dítěti" became "dítě tě", the earlier turbo q5_0 host run read "dítě tě ublíží" (future 3rd person, meaning blurred) at the same spot.

NPU encoder + CPU decoder: tried with patch 04 (`WHISPER_DECODER_CPU=1`: decoder weights in a CPU buffer, decode scheduler CPU-only). It aborts at the first graph with `pre-allocated tensor (leaf_14) in a buffer (HTP0) that cannot run the operation (NONE)`; some tensor still lives in the HTP buffer and I did not find it. **Not measured.** An estimate from the separate runs (NPU encode 2.0 s + CPU decode 11.3 s + load) would be about 15 s per chunk, but that is an estimate, not a result.
