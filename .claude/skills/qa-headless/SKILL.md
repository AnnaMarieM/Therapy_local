---
name: qa-headless
description: Run the QA build (com.therapylocal.app.qa) on a phone completely headless over adb - locked screen, screen off, nothing on the display, no taps. Use when asked to run, test, benchmark or drive the app on a device in the background, run a pipeline/workflow on a recording, push models, read results or stage timings, or when the queue won't start because the phone is locked.
---

# Headless QA runs

The QA build is made to be driven entirely over adb. Everything below works with the phone **locked, dozing, and in someone's pocket**. Never ask the user to unlock the phone, never send wake/unlock/swipe keys, never `am start` the activity. Verified on the Poco (2510DPC44G, HyperOS) on 2026-11-13: with the keyguard showing and the phone dozing, `status`, `unlock`, `get_settings` and `wait` worked and the transcribe job started and ran after the allowlist step. Other devices not tried.

## Rules
- Only `com.therapylocal.app.qa`. Never touch `com.therapylocal.app`. Never uninstall either app. Never `pm clear` the QA app unless recovering (it deletes the multi-GB models).
- Standard QA vault password `12345678`; `qa/qa-api.sh unlock` uses it.
- Use `-s <serial>` on every adb and qa-api call. A phone on USB has a short serial; wireless ones are `ip:port` and the port changes (re-scan, see `docs/CONNECT_PHONE.md`).
- Do not change screen, brightness, timeout or lock settings.
- Run long jobs as background commands and wait on process exit. Watch thermal: `adb -s S shell dumpsys thermalservice | grep -m1 'Thermal Status'`; pause at status >= 2.

## The one trick: power allowlist
Starting the queue runner is a foreground-service start. Android refuses that for an app in the background, and `MemoryService` is not exported so the shell cannot start it. `qa-api.sh` normally gets around it by `am start`-ing the activity, which needs the screen on and shows the app. On a locked phone that fails (`startForegroundService() not allowed due to mAllowStartForeground false`, jobs stay pending).

Fix: put the QA app on the device-idle (power) allowlist once. Apps on it are exempt from the background foreground-service restriction.

```bash
S=<serial>
adb -s $S shell cmd deviceidle whitelist +com.therapylocal.app.qa
adb -s $S shell cmd deviceidle whitelist | grep therapylocal      # check
```
Then every call uses `--no-launch` and the queue runs with the keyguard showing. Undo with `whitelist -com.therapylocal.app.qa`. Not checked: whether the entry survives a reboot or `install -r`; re-check with the grep above after either.

## Recipe
```bash
S=<serial>; Q="qa/qa-api.sh -s $S --no-launch"
adb -s $S install -r <qa.apk>                                   # keeps models
adb -s $S shell cmd deviceidle whitelist +com.therapylocal.app.qa
$Q status                       # vault state, models, job counts (works locked)
$Q setup    # or: $Q unlock     # data side only; the app's own screen stays on its password page
$Q set_setting workflow quality-cs     # fast | quality | quality-cs
$Q set_setting whisper_beam 5
# import the recording (see docs/QA_API.md: import_session / import_manifest), then:
$Q wait 3600                    # loops 25 s calls; exit 4 = timeout; reason says why
$Q results ...                  # see docs/QA_API.md
```
`qa/workflow_run.py --serial S --device poco --pipeline fast|quality|quality-cs --wav-dir D --recordings tomas-b --out DIR` does the whole thing and saves results.json, stages.json, driver.log. It resets the vault first.

Whisper runs on the CPU, Gemma on the GPU unless the user says otherwise (`--backend auto|cpu|gpu|npu|nnapi` on `run_job`).

## Reading what happened
- Stage timings: structured `Stage` log lines (`evt=stage_done stage=... wall_ms=...`), `adb -s S logcat -d -s TL`.
- Jobs: `$Q status` -> `jobs` (pending, running, failed, done, open_kinds).
- Which model ran a stage: the `*_done` log line (`translate_done` records the model).
- Screenshots need the screen: use the real UI path only when the user asks for pictures; not part of headless work.

## Caching and resuming
Slow stages (Whisper per turn about 27 min, E4B facts about 15 min on the Poco) are cached so later tests reuse them. Design, layout and limits: `research/asr-llm-combo/final-workflow/cache/README.md`.
- Snapshot: add `--cache-dir research/asr-llm-combo/final-workflow/cache` (one recording, e.g. `--recordings tomas-b`; optional `--run-id`, `--note "locked, charging"`, `--models-manifest f.json`). After each stage (`transcribe translate roles window summary`) it writes `<platform>/<run-id>/<recording>/after-<stage>/` with export, results, settings, stage log, UTC time and META. Existing snapshots are never overwritten.
- Resume: `--resume-from <stage> --from-cache <.../after-<stage>>` imports the cached segments and speaker map (`import_manifest`, `process:false`) and runs only the later jobs with `run_job`. Same `--pipeline` as the cache. Use a new `--run-id` when caching the resumed run.
- Supported: after-transcribe (runs translate onward). Not supported, and the script says so before touching the phone: after diarize (turns are not exported), and after translate, roles, window for a non-English client (`import_manifest` drops `text_en`; fix is proposed in the README, not built).
- Do not write into a cache directory a watcher is filling; read only.
- Host tests: `python3 -m unittest qa/test_stage_cache.py`.

## Gotchas
- `wait` can report `models_not_ready` or `runner_not_running` instead of progress; the second means the allowlist step is missing or the service was killed.
- After a reboot Android may need the user's PIN once before app data is readable (standard file-based encryption). Not tested here; if `status` then fails with a transport error, ask the user for that one unlock.
- Pixel 8 and Tab S7 were dropped from the final-workflow scope; Poco only unless told otherwise.
- Save raw results into the repo with the serial replaced by `POCO`; no logcat dumps.
