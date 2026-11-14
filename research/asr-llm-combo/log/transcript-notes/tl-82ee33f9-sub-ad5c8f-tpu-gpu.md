# 82ee33f9 subagent agent-ad5c8f6ef558a6c10 - "why isn't it running on TPU/GPU?" investigation (read-only)
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local/82ee33f9-13b4-4236-988f-6090542871ad/subagents/agent-ad5c8f6ef558a6c10.jsonl (113 lines, mtime 01:16)
Time range: 2026-11-13 00:14:03 to 00:16:57. Filtered view: 6 lines kept of 52.

- [DECISION] Owner question (00:14:03): "why the fuck ain't it running on the TPU/GPU?" Investigation only, read-only.
- [CLAIMED] Setup in the task prompt: Pixel 8, Tensor G3, serial 44030DLJH000DD (second phone attached). QA build `com.therapylocal.app.qa` from PR #103 (branch feature/english-pivot; Canary and engine code same as main). Phone processing a background queue; screen on; app not in front; on battery-optimization allowlist.
- [CLAIMED] Prompt: speech recogniser Canary-1B-v2 int8 (sherpa-onnx, `engine/runner/CanaryRunner.kt`, `models/ModelSpec.kt` titles it "Canary 1B v2 (int8, CPU)", THREADS = 4) runs with `backend=CPU`. Finished turns log `rtf=2,66` (a 0.98 s ...), per the owner's observation.
- [MEASURED] Repo doc line (tool_result 00:14:17), `docs/measurements/pixel8-2026-11-07.md:21`: `| embed_gpu_1 | GPU forced | GPU | 8037 | 114 | 0.99967 / 0.99965 / 0.99972 | 353 MB |` (embedding model benchmark, Pixel 8, 2026-11-07, GPU forced).
- [MEASURED] `top` output (tool_result 00:14:35): `Mem: 7754764K total, 7529248K used, 225516K free`; `900%cpu 106%user 368%nice 200%sys 165%idle 3%iow`; `Tasks: 799 total`.
- [MEASURED] Process list (00:14:23): `com.therapylocal.app.qa` (pid 32679) and `com.therapylocal.app.qa:batch` (pid 1656) with `cpuset:/foreground` and `pid_1656` cgroup.
- [CLAIMED] Conclusion (00:16:57): no single cause. Canary runs on CPU because the build never offers it anything else (`asr/SpeechRecogniser.kt:82`). It is slow because the whole CPU is throttled and the batch process is deliberately run at low priority. Running test not touched; read-only checks only.
