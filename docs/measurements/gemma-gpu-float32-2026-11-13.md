# Gemma 4 on the GPU: FLOAT32 against the default precision, Pixel 8, Galaxy Tab S7 and POCO F8 Pro, 2026-11-13

All the runs of one night, with the state each device was in. They back [AD-19](../AD/AD-19.md) (Gemma E2B runs on the GPU with FLOAT32 activations) and the open questions in #48 and #63. Times are the host clock (CET); the phones' own clocks are UTC. Raw json and log files are not committed (public repo); they are on the host in `~/therapy-measurements/gpu-float32-2026-11-13/`. No device addresses, serial numbers or personal data are recorded here.

## Summary

**Established, with the runs that show it**
- **FLOAT32 fixes the GPU output on the Pixel 8 and the tablet.** With the default GPU precision Gemma E2B fails the second output proof (`wrong_keys` on the Pixel 8, `too_few_objects` on the tablet). With FLOAT32 both proofs pass on all three devices, 2 of 2 times each in the lab runs. The POCO passes with either.
- **The merged app picks the GPU.** In the probe runs of the PR #116 APK, with the order NPU, GPU, CPU, Gemma E2B ran on the GPU on all three devices and passed both proofs in every run but one (T1: the GPU engine died during open and the ladder fell back to the CPU; the app stayed up).
- **The POCO F8 Pro does not care about the lock screen.** Its locked runs (X1 to X4) match its lab speeds.

**Seen, but the cause is not established**
- **On the Pixel 8 and the tablet the same GPU load is much slower in some states.** Load took 45 s awake, 325 to 344 s locked and (as reported) on battery, and 68 to 74 s locked and charging on the Pixel 8; on the tablet 36 s awake, 160.6 s then 50.4 s locked and probably on battery, and 43 to 45 s locked and charging. On the Pixel 8 the two charging runs loaded about 5 times faster than the two unplugged locked runs, which points at the power state; the tablet's charging runs were close to its awake run. But no run changes one thing at a time, the charging series was taken later in the night than the others, and the tablet's 160.6 s then 50.4 s happened with no change of state, so a warm-up or cold-start effect is as likely. **Treat "power state" and "cold device" as hypotheses.**
- **No run compares FLOAT32 with the default precision locked, on the same build.** So nothing here says FLOAT32 caused the slowdowns, or did not.

## Devices and build

| Device | SoC / GPU | RAM | Android |
|---|---|---|---|
| Pixel 8 | Google Tensor G3 / Mali-G715 | 7573 MB | 17 (SDK 37) |
| Galaxy Tab S7 (SM-T970) | Snapdragon SM8250 / Adreno 650 | 5763 MB | 13 (SDK 33) |
| POCO F8 Pro (2510DPC44G) | Snapdragon SM8750 / Adreno 830 | 11388 MB | 16 (SDK 36) |

QA build `com.therapylocal.app.qa`, LiteRT-LM `litertlm-android` 0.18.1. **The ladder runs (table below) used the CI QA APK of PR #116**, SHA-256 `3297f1d797c451f02b05da672a987c300c94c21d4e644600044145bf985993a4`, installed with `adb install -r`. The **lab runs** used the QA build that was on each phone (the Pixel 8 reported commit `c934eac1f11a`; the tablet had a local `app-qa.apk`, SHA-256 `46e136b7f8f812789ba4944a888cff6cadd2b5e61ca98f1d2068a1386d69fb9e`; the POCO's commit is unknown). The lab harness builds its own engine config, so the app build matters little for it. Model files: `gemma-4-E2B-it.litertlm` (2 588 147 712 bytes) and `gemma-4-E4B-it.litertlm` (3 659 530 240 bytes), as already on the devices.

## Method

- **Lab runs** (`ProofLabActivity`, forced backend, engine in the main process): `EngineConfig` with `maxNumTokens = 4096`, a fresh cache folder per run, and `activationDataType` unset (default) or `FLOAT32`. Greedy sampling (`topK = 1`, `temperature = 0`, `topP = 1`, seed 1), at most 400 output tokens. Cases: `facts4` (the shipped proof 1), `proof2` (the shipped proof 2, the app's extraction instruction on three made-up sentences), `memprobe` (the #48 prompt, length only). Decode speed is the engine's own benchmark counter. `am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.ProofLabActivity --es file gemma-4-E2B-it.litertlm --ei ctx 4096 --es cases facts4,proof2,memprobe --ei topk 1 --es temp 0.0 --es backend GPU --es dtype FLOAT32 --es tag <tag>`.
- **Ladder runs** (`MemProbeActivity`, order `NPU,GPU,CPU`, Gemma E2B in the `:batch` engine process, 2 calls of at most 250 output tokens): the real backend ladder with both proofs. **"Load" includes the proofs and, on the POCO, a failed NPU attempt.** The probe's ladder logged `respect_blocks=false`, so a guard block from earlier strikes would not have skipped a backend in these runs; the app's own path honours blocks. `am start -n com.therapylocal.app.qa/com.therapylocal.app.debug.MemProbeActivity --es variant e2b --es backend NPU,GPU,CPU --ei calls 2 --ei maxout 250 --es lang en --es tag <tag>`. The reinstall of the APK resets the guard (it is keyed on the install time), but that does not matter for these runs for the reason above.
- **Embedder** (`BackendProbeActivity`, `--es model embed --es backend GPU --ez force true --ei calls 5`): 3 fixed texts, 5 calls each (15 calls), against a CPU reference in the same probe.
- **Phone state.** Screen and lock state of each run are given as known. For the runs of the plugged-in series (P5, P6, T5, T6) the battery, charge, thermal, battery-saver and Doze state were recorded before and after each probe; for the earlier runs they were not, and "not plugged" is what was reported or what was seen at 05:10, not a recording. The lab runs (matrix and Pixel 8 FLOAT32) were driven with wake key presses; the lock state was not recorded.

## Results

### 1. Lab runs, Gemma E2B, forced backend (decode tokens per second, range over all runs and cases; engine load in seconds per run)

| Device | Backend | Runs | Engine init (s) | Decode (tok/s) | Proof verdicts |
|---|---|---|---|---|---|
| POCO F8 Pro | CPU | 2 | 3.0, 2.7 | 29.1 to 30.8 | facts4: pass (2 of 2); proof2: pass (2 of 2) |
| POCO F8 Pro | GPU, default precision | 2 | 6.0, 9.0 | 38.4 to 42.6 | facts4: pass (2 of 2); proof2: pass (2 of 2) |
| POCO F8 Pro | GPU, FLOAT32 | 2 | 5.7, 8.9 | 37.3 to 42.8 | facts4: pass (2 of 2); proof2: pass (2 of 2) |
| Galaxy Tab S7 | CPU | 2 | 5.6, 6.4 | 12.7 to 14.2 | facts4: pass (2 of 2); proof2: pass (2 of 2) |
| Galaxy Tab S7 | GPU, default precision | 2 | 11.8, 11.1 | 13.7 to 15.7 | facts4: pass (2 of 2); proof2: fail too_few_objects (2 of 2) |
| Galaxy Tab S7 | GPU, FLOAT32 | 2 | 10.3, 10.1 | 14.6 to 15.3 | facts4: pass (2 of 2); proof2: pass (2 of 2) |
| Pixel 8 | GPU, FLOAT32 | 2 | 34.9, 32.9 | 13.7 to 14.0 | facts4: pass (2 of 2); proof2: pass (2 of 2) |

The Pixel 8's CPU and default-precision GPU speeds come from the earlier `MemProbeActivity` runs of 2026-11-07 ([pixel8-2026-11-07.md](pixel8-2026-11-07.md)): CPU 5.9 tok/s, default GPU 11.0 to 12.6 tok/s with the proof failing. On 2026-11-13 the Pixel 8 repeated it: forced CPU load 1.6 s and calls of 51.1, 48.7 and 49.1 s (250 output tokens); forced default-precision GPU failed proof 2 (`wrong_keys`) after 131 s, with proof 1 decoding at 11.3 tok/s.

### 2. Ladder runs of the PR #116 APK, Gemma E2B (order NPU, GPU, CPU)

Every GPU load passed both proofs. A CPU load runs no proofs (the CPU is the reference).

| Run | Device | Screen | Power | Backend chosen | Load (s) | Call 1 (s) | Call 2 (s) | Lowest free memory (MB) | Note |
|---|---|---|---|---|---|---|---|---|---|
| P1 | Pixel 8 | awake, unlocked (checked at the start) | not plugged (as reported; not recorded) | GPU | 45.7 | 24.1 | 23.3 | 562 |  |
| P2 | Pixel 8 | awake, unlocked (checked at the start) | not plugged (as reported; not recorded) | GPU | 45.0 | 24.5 | 21.3 | 488 |  |
| P3 | Pixel 8 | locked | not plugged (as reported; not recorded) | GPU | 344.2 | 45.7 | 42.2 | 131 | `low_memory` set |
| P4 | Pixel 8 | locked | not plugged (as reported; not recorded) | GPU | 325.4 | 35.5 | 30.8 | 503 |  |
| P5 | Pixel 8 | locked | plugged in (AC) | GPU | 67.6 | 31.1 | 27.9 | 433 |  |
| P6 | Pixel 8 | locked | plugged in (AC) | GPU | 74.1 | 35.4 | 30.6 | 458 |  |
| T1 | Galaxy Tab S7 | locked or screen off (not recorded) | not recorded; GPU died, CPU fallback | CPU | 150.6 | 34.2 | 22.5 | 1005 |  |
| T2 | Galaxy Tab S7 | awake, unlocked | not plugged (as reported; not recorded) | GPU | 35.9 | 20.1 | 19.5 | 869 |  |
| T3 | Galaxy Tab S7 | locked | not recorded (plugged in at about 05:10 as reported) | GPU | 160.6 | 50.7 | 25.5 | 807 |  |
| T4 | Galaxy Tab S7 | locked | not recorded (plugged in at about 05:10 as reported) | GPU | 50.4 | 23.7 | 24.9 | 806 |  |
| T5 | Galaxy Tab S7 | locked | plugged in (USB) | GPU | 42.7 | 22.4 | 21.6 | 879 |  |
| T6 | Galaxy Tab S7 | locked | plugged in (USB) | GPU | 44.8 | 24.9 | 22.0 | 917 |  |
| X1 | POCO F8 Pro | locked (screen woken briefly) | not plugged (seen at 05:10; not recorded) | GPU | 30.3 | 6.7 | 6.2 | 2751 |  |
| X2 | POCO F8 Pro | locked (screen woken briefly) | not plugged (seen at 05:10; not recorded) | GPU | 24.8 | 6.7 | 6.2 | 2834 |  |
| X3 | POCO F8 Pro | locked | not plugged (seen at 05:10; not recorded) | GPU | 27.6 | 6.6 | 6.2 | 2816 |  |
| X4 | POCO F8 Pro | locked | not plugged (seen at 05:10; not recorded) | GPU | 24.2 | 6.6 | 6.2 | 2907 |  |

- **T1:** the GPU attempt ran 146.6 s and then `engine batch died during open: unknown exit not settled in time`. The ladder tried the CPU, which loaded in 150.6 s total. The app stayed up. Cause not found.
- **POCO (X1 to X4):** the NPU was tried first and failed proof 2 (`too_few_objects`), then the GPU was proven; the load times include that failed attempt. The same NPU behaviour is in #113.
- **Pixel 8 and tablet:** the NPU was skipped (no validated build for the SoC).
- **P3** set `low_memory` and ended with 131 MB free; no other run did.

### 3. Charging and locked: phone state recorded around each probe

| Device | Moment | Power | Battery | Battery temp (°C) | Thermal status | Free memory (MB) |
|---|---|---|---|---|---|---|
| Pixel 8 | probe 1 start 05:11:48 | AC | 87 % | 25.7 | 0 | 2593 |
| Pixel 8 | probe 1 result file: 1 at 05:14:02 | AC | 88 % | 25.5 | 0 | 3005 |
| Pixel 8 | probe 2 start 05:14:04 | AC | 88 % | 25.5 | 0 | 2945 |
| Pixel 8 | probe 2 result file: 1 at 05:16:30 | AC | 89 % | 25.8 | 0 | 3039 |
| Galaxy Tab S7 | probe 1 start 05:11:57 | USB | 40 % | 25.8 | 0 | 2910 |
| Galaxy Tab S7 | probe 1 result file: 1 at 05:13:32 | USB | 41 % | 26.1 | 0 | 2812 |
| Galaxy Tab S7 | probe 2 start 05:13:33 | USB | 41 % | 26.1 | 0 | 3003 |
| Galaxy Tab S7 | probe 2 result file: 1 at 05:15:12 | USB | 41 % | 26.3 | 0 | 2807 |

Thermal status was 0 (none) and battery saver off at every snapshot; Doze (light idle) was `ACTIVE`, i.e. not idle.

### 4. Embedder on the GPU (not changed by AD-19)

| Device | CPU median per call (ms) | GPU median per call (ms) | Open CPU / GPU (s) | Lowest cosine to the CPU vectors |
|---|---|---|---|---|
| Pixel 8 | 250 | 109 | 2.7 / 5.5 | 0.9996 |
| Galaxy Tab S7 | 116 | 104 | 1.0 / 2.0 | 0.9974 |
| POCO F8 Pro | 69 | 33 | 1.0 / 1.7 | 0.9996 |

### 5. Gemma E4B on the POCO F8 Pro (one run each, lab harness)

| Backend | Engine init (s) | Decode by case (tok/s) | Proofs |
|---|---|---|---|
| CPU (1 run; unreliable, see text) | 10.3 | 4.3, 6.4, 15.3 | pass, pass, n/a (no verdict case) |
| GPU, default precision | 9.8 | 19.4, 20.5, 20.8 | pass, pass, n/a (no verdict case) |
| GPU, FLOAT32 | 14.3 | 18.9, 19.9, 20.3 | pass, pass, n/a (no verdict case) |

**The E4B CPU row is unreliable**: decode swung from 4.3 to 15.3 tok/s inside one run, and the phone was in use around then. The CPU-to-GPU speed-up for E4B cannot be stated. The GPU default and FLOAT32 runs were taken with the probe in front, and the phone was left alone. An earlier GPU attempt gave no result for 8 minutes while another app was in front of the probe and was abandoned.

## Not measured

- A locked run with the default precision on the same build, to separate the effect of FLOAT32 from the effect of the phone's state.
- Runs that vary one thing at a time (screen, charger, warm or cold start, in turn), and repeats: most cells here are 1 or 2 runs.
- Peak memory of the engine host with FLOAT32 against the default; only system free memory at sample points.
- A queued job in the app's foreground-service queue on a locked phone. The probes start from adb, which is not what the app does.
- E4B on the Pixel 8 and the tablet; the cause of T1.
- Battery drain and heat beyond the snapshots in section 3.
