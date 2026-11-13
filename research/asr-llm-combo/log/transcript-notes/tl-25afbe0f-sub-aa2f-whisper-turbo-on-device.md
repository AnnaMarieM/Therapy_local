# 25afbe0f subagent agent-aa2f8cdc33b216e2b - whisper large-v3-turbo on-device feasibility (Pixel 8, Tab S7, POCO), NPU -> GPU -> CPU
Source: .../25afbe0f-94ca-432d-8e51-25f9dc228dcc/subagents/agent-aa2f8cdc33b216e2b.jsonl (680 lines, mtime 15:55)
Time range: 2026-11-13 12:55 to 13:09. Filtered view: 14 lines kept of 321 (sampled).

- [DECISION] Task (12:55:49): does whisper large-v3-turbo fit and how fast on the tablet, POCO and Pixel; NPU first, then GPU, then CPU; one 30 s chunk at beam 5 first. Do NOT touch the real app `com.therapylocal.app`. Scratch in the scratchpad `device/`.
- [MEASURED] Pixel 8 (12:55:59): `ro.product.model=Pixel 8`, `ro.soc.model=Tensor G3`, Android 17, `MemTotal: 7754764 kB`, `MemAvailable: 350632 kB` (about 350 MB free at that moment).
- [MEASURED] Storage (12:59): Pixel 8 19 GB free storage (agent text). Other device lines: `/dev/block/dm-62 477G 426G 51G 90%` (POCO), `dm-78 110G 91G 19G 83%` (Pixel).
- [MEASURED] whisper.cpp load (13:04-13:05): `whisper_model_load: type = 5 (large v3)`, `adding 1609 extra tokens`, `CPU total size = 573.40 MB`, `model size = 573.40 MB`, `whisper_backend_init_gpu: no GPU found` (CPU build). Model used: `ggml-large-v3-turbo-q5_0.bin` (q5_0). Prior model file in another run: q8_0 variant also downloaded.
- [MEASURED] CPU timing line (13:05:16): `whisper_print_timings: batchd time = 12896.44 ms / 956 runs (13.49 ms per run)`.
- [MEASURED] WER on one chunk (13:05:27): `tab_cpu_cs_b5_t4.txt hyp_words 73 ref_prefix_words 74 WER 0.203` (tablet CPU, Czech, beam 5, 30 s chunk; very small sample).
- [BUG] Build errors on device-side builds: `ld.lld: error: unable to find library -lgcc` (13:01:26); `fatal error: 'spirv/unified1/spirv.hpp' file not found` in ggml-vulkan (13:05:30).
- [MEASURED] OpenCL backend (13:08:37): `ggml_opencl: selected platform: 'QUALCOMM Snapdragon(TM)'`, `device: 'QUALCOMM Adreno(TM) (OpenCL 2.0 Adreno(TM) 650)'` (device-dependent; Pixel?); Vulkan (13:09:51): `ggml_vulkan: Found 1 Vulkan devices: 0 = Adreno (TM) 830 ... uma: 1 | fp16: 1`.
- [CLAIMED] Agent (12:59:15): not built or measured yet at that point; downloads for NDK, ffmpeg, q5_0 and q8_0 models in progress.
- [QUESTION] Final results of the on-device turbo benchmark not in the sampled view. The later on-device results are referred to in other sessions (e.g. commit 08f96d8 "on-device Whisper turbo benchmark with reproduction patches" in git log of the system context).
