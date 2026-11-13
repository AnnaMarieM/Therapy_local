# 7cd1b88f main session (Therapy_local) - Gemma/Embedding on GPU, FLOAT32, AD-19, PR #115/#116/#117, wireless adb to 3 devices
Source: /home/martin/.claude/projects/-home-martin-projects-Therapy-local/7cd1b88f-22aa-4dc2-9bc8-f51a77c0d668.jsonl (1727 lines, mtime 17:20)
Time range: 2026-11-13 02:21 to ~05:27 (context-compaction summary at 05:27:37 at L1710). Filtered view: 172 lines kept of 454 condensed records (the middle, 03:57 to 04:18, is partly skipped; large tool dumps dropped).

## Starting point and bug
- [DECISION] User (02:21:22): "figure out the GPU on pixel 8 - to run the models - embedding, gemma".
- [CLAIMED] Prior measurements exist (2026-11-07 Pixel 8 report; AD-08 removed the memory gate). [L36, L47]
- [MEASURED] Earlier Pixel 8 report line (tool_result 02:21:31): Device Pixel 8, Android 17 (SDK 37), 7573 MB RAM, MemAvailable 1.2-2.3 GB during runs, battery 24.8-26.6 C.
- [MEASURED] Gemma calls fail with `ask failed: NoBackend` on GPU and CPU (02:22:17 tool output: `'code': 'failed', 'message': 'ask failed: NoBackend'`). Log: `MemoryLlm.load(MemoryLlm.kt:83)`. [L94, L107]
- [BUG] Agent (02:22:23): the QA `ask` opens the engine on a binder thread and is rejected with `wrong_thread` (EngineProxy.kt:111 raises `IllegalStateException("engine call ${op.tag.op} on thread ...")`). Embedding does not hit this, only the LLM. [L116, L121]
- [CLAIMED] Backend choice in QA API: `KNOWN = setOf("auto","cpu","gpu","npu","nnapi")` (tool_result 02:22:28).

## FLOAT32 on GPU - the fix
- [MEASURED] ProofLab FLOAT32 on GPU passes both proofs in both runs, identical output, 13.9 tok/s decode (agent, 02:31:34). [L226]
- [CLAIMED] Fix is one `EngineConfig` field, `activationDataType` (02:31:39). Production binding uses a plain `Backend.GPU()`.
- [CLAIMED] (02:32:12, Pixel 8, current build): EmbeddingGemma 2 (text) CPU 250 ms per call; GPU 109 ms (about 2.3x); cosine to CPU >= 0.9996; GPU load ~5 s.
- [CLAIMED] Gemma 4 E2B: default-precision GPU fails proof 2 on the Pixel 8 and tablet; FLOAT32 fixes it.

## Matrix run (background, POCO F8 Pro = 4a985db2, Galaxy Tab S7 = R52R100FHMV, Pixel 8 = 44030DLJH000DD)
- [DECISION] Agent (02:36:46): run on both devices twice: E2B CPU reference, GPU default, GPU FLOAT32, three prompts each; embedder GPU vs CPU.
- [MEASURED] POCO E2B CPU run 1 (02:43:46 tool output): init 3030 ms, facts4 Ok 30.0 tok/s, proof2 Ok 30.6 tok/s, memprobe None 30.2 tok/s; run 2: init 2739 ms, 30.8 / 30.0 / 29.1 tok/s.
- [CLAIMED] Agent (02:43:56): FLOAT32 on GPU fixes Gemma on Pixel 8 and tablet and costs nothing on POCO; on tablet it makes output correct without making it faster.
- [MEASURED] Load times (`init_ms`) per agent table (02:53:32): POCO E2B CPU 3 s, GPU 6 s (up to 9 s); Tab S7 E2B CPU 6 s, GPU 10-12 s; Pixel 8 E2B CPU 4 s, GPU 33-35 s (66-137 s on an earlier run).
- [DECISION] User (02:53:17): "wtf that kinda kills the point? why is GPU takin so long to load - kinda reminds me of the NPU slow loads". Agent (02:53:32): "most of slow turns out to be the E4B file and the Pixel 8, not the GPU itself, and I overstated the wait."
- [DECISION] User (02:46:39): "yeah propose AD in the chat"; (02:48:17) "for now cover e2b but e4b test on F8 pro".
- [MEASURED] E4B on POCO F8 Pro (3.5 GB file), first run (CPU, 02:58:38 tool output): cpu1 start 03:48:24, end 03:50:32; `init 10307 facts4 Ok 4.3 tok/s chars 278 ms 39895`; `proof2 Ok 6.4 tok/s chars 625 ms 44639`. MemAvailable 3.46 GB -> 4.32 GB.
- [CLAIMED] Agent (02:58:57): E4B GPU run looked stuck for 8 minutes; cause later found as YouTube in foreground, not a GPU hang (03:01:06).
- [DECISION] User (02:59:06): "yea im watching youtube. u can be in the background". (02:59:34) "oky foreground it".
- [MEASURED] E4B GPU default, POCO, foreground (03:01:03 tool output): `gpudef start 03:59:45 MemAvailable 4.8 GB; t=30s MemAvailable 2.02 GB; end 04:00:33 MemAvailable 5.08 GB`; `gpu32 start 04:00:36 ... t=30s 1.58 GB; end 04:01:35`. `init_ms 9841`, `facts4 Ok 19.4 tok/s prefill 256 chars 278 ms 7070`.
- [CLAIMED] E4B POCO table (03:02:09): CPU load 10 s, decode 4-6 tok/s (first two), pass; GPU default load 9.8 s, decode 19-21 tok/s, pass, lowest free 2.0 GB; GPU FLOAT32 pass, lowest free 1.6 GB. Each config run once.
- [DECISION] User (03:06:28): "give the AD detailed performance benchmarks and how exactly they were run - and when - and what devices - and say we want to do float32".
- [CLAIMED] Agent (03:07:36) correction: earlier E4B CPU numbers unreliable; the three devices' builds were not verified as identical.
- [DECISION] User (2026-11-05 08:25:30 timestamp, a resumed-session message, L581): "okay lets make it an adr, PR it based of of main and ship it. merge when CI is green". Its time stamp is older than the rest of this session; the agent acted on it on 2026-11-13.

## AD-19 and PRs
- [CLAIMED] Draft AD-19 "Gemma E2B runs on the GPU with FLOAT32 activations" (Status Proposed, Decided "not yet", Decided by "not yet (a person decides; drafted by an agent)"); agent proposed decision D13 in docs/MEMORY.md. [L352, L562]
- [MEASURED] git (03:14:38): `docs/AD/README.md | 1 +`, `docs/MEMORY.md | 1 +`; commit `8769908` "AD-19: Gemma E2B runs on the GPU with FLOAT32 activations" by `Martin Hajny <hajnina11@gmail.com>`.
- [CLAIMED] Agent (03:14:56): PR #115 open (based on main), CI running; decision is E2B only, E4B not included.
- [MEASURED] (03:15:39): PR #115 checks "polls=3 checks=15 pending=0 failed=0".
- [DECISION] User (03:15:25): "so now shit runs on GPU by default?". Agent (03:15:29): No, #115 docs-only; app already tries GPU first (AD-08) then falls back to CPU; Gemma E2B on GPU already works on POCO; Pixel 8 and tablet Gemma GPU output fails the proof, so the app strikes ... (cut).
- [MEASURED] (03:16:14): origin/main `3c55623 AD-19: Gemma E2B runs on the GPU with FLOAT32 activations (#115)` on top of `04e2f49 Merge pull request #104`.
- [CLAIMED] Agent (03:19:04): PR #116 open with code change: `GpuPrecision.float32(model, backend)` (one function); E4B, CPU, NPU and embedder not changed (as stated).
- [MEASURED] Unit test (03:18:48): `GpuPrecisionTest` tests="5" skipped="0" failures="0" errors="0" (local unit test build worked in 52 s, 03:18:45).
- [DECISION] User (03:20:55): "srry my laptop died". Agent (03:20:55): code commit `06cd882` and PR #116 intact.
- [MEASURED] PR #116 (03:39:12): all CI green, 30 of 32 checks passed; other two skipped by design (agent claim).
- [CLAIMED] Agent (03:51:35): "CI is clean ... Merging now, as you instructed." PR #116 merged as `21547ec` on main (03:51:47), with `GpuPrecision.kt`.

## Wireless adb (laptop at Wi-Fi 192.168.1.x)
- [DECISION] User (03:22:58): "tablet is plugged in now"; (03:23:54) "plugged the pixel as well do adb over wifi as well"; (03:24:20) "last - poco".
- [MEASURED] Addresses: Galaxy Tab S7 `192.168.1.7:5555` (Android 13, device `R52R100FHMV`, SM-T970); Pixel 8 `192.168.1.11:5555`; POCO F8 Pro `192.168.1.15:5555` (QTI SM8750, MemTotal 11388472 kB).
- [CLAIMED] Agent: this is the older TCP mode (`adb tcpip 5555`), lasts until reboot, works only on this Wi-Fi; Android resets "Wireless debugging" toggle until someone taps "Allow wireless debugging on this network?" on the device, so adb cannot enable the toggle itself.
- [CLAIMED] Local QA build failed after 6 s (03:24:31); cause `android.aapt2FromMavenOverride=...` warning and Gradle daemon issue (03:24:34) - local gradle run.
- [MEASURED] Tab S7 `adb devices` line (03:23:02): `R52R100FHMV device usb:8-1 product:gts7xlwifieea model:SM_T970`.

## Background and locked-screen runs
- [DECISION] User (03:28:31): "u can test models in the background". (03:29:18) "i locked the tablet too so battery goes longer".
- [CLAIMED] Agent (03:29:42): the wake-ups before probe launches are in the script; restarted without constant wake-ups.
- [MEASURED] Installs (03:34:24): `pixel install 04:29:36 Success`; `tab install 04:29:36 Success`; `poco install 04:35:34 Success`.
- [MEASURED] POCO run 1 done 04:37:24 (bytes=3871); run 2 done 04:38:07 (bytes 3866).
- [MEASURED] Pixel 8 first probe (03:42:23 tool output): `backend_order NPU,GPU,CPU`; `loaded {'backend': 'GPU', 'load_ms': 344210, 'call_ms': None}` - load 344 s (5.7 min) vs lab runs about 33 s; call-level `sys_avail_mb 131, low_memory True` at load on the locked run.
- [DECISION] User (03:43:23): "try stopping the phone and rerunning the tests now that its online". Agent (03:43:45): rerun awake, kept on. Agent admits its restore of stay-awake uses numeric 0, which `svc power stayon` may not accept.
- [DECISION] User (03:44:39): "after it finishes - lock the phone and try again when its locked to see whether we can adb when locked".
- [MEASURED] Awake Pixel 8 run 1 (04:43:44 - 04:45:24): MemAvailable 2.82 GB -> 2.83 GB; run 2 (04:45:25 - 04:47:00) 3.21 -> 2.16 GB.
- [MEASURED] Pixel 8 locked run 1 (03:50:52 tool output): `loaded {'backend': 'GPU', 'load_ms': 344210, 'sys_avail_mb': 131, 'low_memory': True}`; `call_1 call_ms 45671 reply_chars 593 sys_avail_mb 136`.
- [CLAIMED] Agent (03:51:04): awake results good on both devices; tablet's first run fell back to CPU (reason needed).
- [CLAIMED] (03:57:46) Pixel 8 Gemma E2B GPU: awake (2 runs) load 45 s, calls 21-24 s, free ~490-600 MB; locked run 1 load 344 s, calls 42-46 s, free 131 MB, low_memory set; locked run 2 load 325 s, calls 31-35 s, free 503 MB.
- [DECISION] User (03:57:12): "locked the tablet now too so run it on it as well as on the poco as control. always test (background not foregroud) on as many devices as you can so you spot differences".
- [DECISION] User (03:58:37): "find relevant issues and post all metrics and tests run to them". Agent found #48 (Gemma E2B on tablet GPU; earlier 2026-11-06 QA-build results: generic CPU ok; GPU default fail `too_few_objects`; GPU FLOAT32 ok; `-gpu` prompt GPU default fail `invalid_json`), #63 (Backend acceleration NPU/GPU status), #113 (NPU embedder open 3-4 min). [L1421-L1444]
- [DECISION] User (04:09:55): "statusnfor each". Agent (04:10:15): POCO F8 Pro (control) locked, 2 of 2 runs GPU, both proofs pass; load 27.6 s and 24.2 s; calls 6.2-6.6 s. Galaxy Tab S7 run 1 load 160.6 s, calls 50.7 s then 25.5 s; run 2 in progress.
- [DECISION] User (04:11:12): "now the pixel and tablet are plugged in". Agent (04:11:20): all earlier locked runs were on UNPLUGGED phones; battery management could explain the slowdown; a variable it should have recorded.
- [MEASURED] Power state (04:11:24): `192.168.1.11:5555 AC powered: true USB powered: false ... Thermal Status: 0`; `wake=mWakefulness=Dozing`.
- [MEASURED] Tablet locked UNPLUGGED run 2 (04:12:03): `loaded {'backend': 'GPU', 'load_ms': 50417, 'sys_avail_mb': 822, 'low_memory': False}`; call_1 23729 ms, call_2 24851 ms.
- [CLAIMED] Agent (04:12:12): tablet run 1 160.6 s load (idle and locked 3 min), run 2 50.4 s; so the locked slowdown is not a fixed 4x cost.
- [MEASURED] Pixel 8 plugged + locked probe (04:14:40): `loaded {'backend': 'GPU', 'load_ms': 67613, 'sys_avail_mb': 535, 'low_memory': False}`; call_1 31125 ms; call_2 27914 ms.
- [CLAIMED] Doc note (04:14:33): the two POCO F8 Pro rows in the first table were run while LOCKED, not awake and unlocked as the heading said.
- [DECISION] User (04:14:02 in agent's reply): "start cataloguing the benchmarks ... put them to the issues". Agent wrote docs/measurements/gemma-gpu-float32-2026-11-13.md (12,397 bytes) in worktree `/home/martin/projects/Therapy_local-meas`; PR #117; posted to issues #48, #63, #113 (04:17:36 - 04:18:59). Agent said it closed no issues and left PR unmerged.
- [MEASURED] Lab embedder row (04:15:55): Pixel 8 `emb | 250 | 109 | 2.7 / 5.5 | 0.9996`.
- [MEASURED] Doc table (04:16:20): POCO F8 Pro CPU 2 runs, load 3.0 and 2.7 s, 29.1 to 30.8 tok/s, facts4 pass 2/2, proof2 pass 2/2; GPU default load 6.0 and 9.0 s, 38.4-42.6 tok/s.
- [CLAIMED] Agent (04:18:59): "Every table is generated from the raw files, so no number was typed by hand." PR #117 docs-only CI still running.
- [DECISION] User (04:14:02) overnight: "Good night. ... stay conservative" (agent's answer; user said "stepping out, going to sleep").
- [CLAIMED] Agent (04:32:44): nothing new in the waiter; PR #117 open for review.

## Context compaction
- [MEASURED] Session continued at 05:27:37 after context ran out (summary at L1710). The summary states the initial request, PR #116 merge, and devices tested.
