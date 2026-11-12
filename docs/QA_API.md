# QA control API

Everything you do by hand or with UI taps on the QA build is one call. Decision: [AD-16](AD/AD-16.md). The API is compiled from the `qa` source set (`app/src/qa/java/com/therapylocal/app/qaapi/`): the QA build (`com.therapylocal.app.qa`) has it, `release`, `debug` and `feel` do not, and the `build` job fails if a trace of it is found in the release or feel APK (`verifyNoQaApiInRelease`). QA holds **test data only**.

```
qa/qa-api.sh [-s serial] [-p package] [--no-launch] <command> [args]     # bash and the system adb; -s picks the phone
```

The standard QA vault password is **`12345678`**. `setup` and `unlock` use it when you give none. It exists only in the QA source set.

## How it works

- **Transport.** An exported content provider, authority `<applicationId>.qaapi`, called with `adb shell content call --uri content://com.therapylocal.app.qa.qaapi --method <command> --arg b64:<base64 of a JSON object>` (the script does this). The manifest asks for `android.permission.DUMP` (the shell has it, apps cannot get it) **and** every call checks that the caller's uid is the adb shell's (2000); any other caller, including the app itself, gets `forbidden_caller` and nothing is written. No socket, no HTTP, no network. The provider exists only in the QA manifest.
- **Result.** JSON: `{"api_version":1,"command":"status","ok":true,"data":{...}}`, or `{"ok":false,"error":{"code":"vault_locked","message":"..."}}`. The result is always written to `/sdcard/Android/data/com.therapylocal.app.qa/files/api/<command>.json` (atomic) and the call returns a Bundle with `ok`, `code`, `file`, `bytes` and, when under 100 kB, `json`. The script reads the file and prints it. Exit code: 0 ok, 1 API error, 2 usage, 3 transport, 4 timeout. Messages never contain clinical text; results do (it is test data) and live only in `files/api/`, which `reset` empties.
- **Locked vault.** Only `status`, `get_settings`, `setup` and `unlock` work. Everything else answers `vault_locked` (or `vault_not_set_up`). A QA vault whose password you do not know cannot be reset through the API; clear it as `AGENTS.md` says (`adb shell pm clear com.therapylocal.app.qa`, which also deletes the models).
- **Real app.** The script refuses any package that does not end in `.qa`; the app refuses every command unless its application id ends in `.qa` and is not `com.therapylocal.app`. The backend and the settings setters check the id again. Release, feel and debug builds do not contain the code at all: `verifyNoQaApiInRelease` scans their APKs and `scripts/check-qa-api-guard.sh` the sources and the script. Nothing here touches `com.therapylocal.app`.
- **Bring the app forward.** Starting the queue runner is a foreground-service start, which Android refuses for an app in the background. Commands that queue work (`import_*`, `wait`, `run_job`, `swap_roles`, `set_setting`) therefore first run `am start` on the QA `MainActivity` (turn off with `--no-launch`). The screen must be on.
- **After `unlock` / `setup` through the API the app's own screen stays on its password page** (the gate is UI state). The data side works; for UI screenshots unlock in the UI. There is no `screen` command: navigation is local Compose state with no seam to drive it; use `qa/qa-api.sh screenshot f.png` (host `screencap` of whatever is shown) and `qa/screenshots.sh`.
- **Logs.** `adb logcat -s TL`, tag `QaApi`: command name, result code, counts, sizes, times and the backend, as `key=value`. Never arguments, results or text ([`LOGGING.md`](LOGGING.md)).

## Commands

| Command | What it does | Example |
|---|---|---|
| `status` | Build (application id, version, commit), vault `not_set_up`/`locked`/`unlocked`, models (`ready`, setup state, bytes, per file), job queue (idle, pending, blocked, failed, running kind). Works locked. | `qa/qa-api.sh status` |
| `setup [password]` | First-time vault set-up, then unlocked. Default password `12345678`, at least 8 characters. | `qa/qa-api.sh setup` |
| `unlock [password]` | Unlocks an existing vault. `bad_password` on a wrong one. | `qa/qa-api.sh unlock` |
| `seed_clients [file]` / `import_clients [file]` | Creates the clients of `{"clients":[{"id","name","lang"}]}` (default `e2e/sittings/clients.json`). Idempotent: existing ids are left alone and reported. | `qa/qa-api.sh seed_clients e2e/corpus/clients.json` |
| `import_session <client> <audio> [--session id] [--date yyyy-MM-dd] [--language xx]` | A session from audio: a host file is pushed (non-WAV converted with ffmpeg) to `files/api/in/`, replayed through the real chunker and queued from transcribe on. Re-running with the same session id does nothing. The client is an id or a name. Files are read only from the app's own files folder. | `qa/qa-api.sh import_session petr-k e2e/sittings/audio/petr-k_s01.wav --session petr-k_s01 --date 2026-03-01 --language cs` |
| `import_session` with `segments` | A session from a ready transcript (the export's format): `{"client","session","date"\|"started_at","language"?,"segments":[{"startMs","endMs","speaker","text"}],"speaker_map":{"S0":"therapist","S1":"patient"}?,"process":true}`. Queued from the window job on (`process:false` only stores it). Use `raw`. | `qa/qa-api.sh raw import_session '{"client":"c","session":"c_1","segments":[{"startMs":0,"endMs":900,"speaker":"S0","text":"Hello"}],"speaker_map":{"S0":"therapist"}}'` |
| `import_manifest <file>` | A whole set in one call: `{"clients":[...],"sessions":[ {import_session entries} ]}`, sessions in client and date order; a failing entry is listed in `failed`, the rest go on. Takes an export file back. | `qa/qa-api.sh import_manifest out/export.json` |
| `wait [seconds]` | Blocks until the job queue is idle (nothing running, nothing runnable pending, settled twice), default 600 s. The script loops 25 s calls. Exit 4 on timeout; `reason` says `models_not_ready`, `runner_not_running` or `running`. | `qa/qa-api.sh wait 1800` |
| `results [--client c] [--session s] [-o file]` | The `DevResults` document: per session the transcript with `speaker` and `role` per segment, `speakerMap` (current) and `speakerMaps` (every version), `summary` (state, `stale`, `format`, text, sections); `facts` with `segIdx`/`segTo`, `startMs`/`endMs`, `speaker`, `role`; `answers` for optional `queries`. | `qa/qa-api.sh results -o out/results.json` |
| `export [--client c] [--session s] [-o file]` | Clients and sessions in the import format, see below. | `qa/qa-api.sh export -o out/export.json` |
| `swap_roles <session>` | "Is this incorrect?": therapist and patient trade places (new speaker-map version, summary stale and rebuilt, facts re-extracted). `bad_request` when there is nothing to swap. | `qa/qa-api.sh swap_roles petr-k_s01` |
| `get_settings` | Every app setting as JSON: `{"settings":[{"key","type","values" (enum, bool) or "min"/"max" (int, float),"default","value","description"}]}`. Works with a locked vault (settings are not clinical data). See [Settings](#settings). | `qa/qa-api.sh get_settings` |
| `set_setting <key> <value>` | Sets one setting through the app's own setter and returns `setting`, `old`, `new`, `changed`. A value outside the allowed set or range is `bad_request` and the message lists what is allowed; nothing is coerced or clamped. Needs an unlocked vault. | `qa/qa-api.sh set_setting gemma_variant e4b` |
| `set_summary_format <id>` | Alias of `set_setting summary_format <id>` (argument `format`); also returns `format`. | `qa/qa-api.sh set_summary_format soap` |
| `reset` | Wipes the QA vault: keys, database, retained audio, results. **The downloaded models are not touched** (`models_kept`). The vault is then `not_set_up`: run `setup`. Needs an unlocked vault. | `qa/qa-api.sh reset && qa/qa-api.sh setup` |
| `transcribe <audio> [--language xx] [--start s] [--dur s] [--backend b] [--force]` | Canary on one clip (at most the live-cue limit; use `--start`/`--dur`). Returns `text`, `audio_sec`, `load_ms`, `dur_ms`, `rtf`, `backend` (used), `backend_requested`, `model`. | `qa/qa-api.sh transcribe a.wav --language cs --backend gpu` |
| `diarize <audio> [--speakers n] [--backend b] [--force]` | The diarizer on a whole recording (default 2 speakers, AD-07): `turns` (`start_ms`, `end_ms`, `speaker`), `speakers`, `load_ms`, `dur_ms`, `rtf`, `backend`, `model`. | `qa/qa-api.sh diarize petr-k_s01.wav` |
| `embed <text> [--query] [--vectors] [--backend b] [--force]` | The text embedder (`raw embed '{"texts":[...]}'` for several): `dim`, `norm`, per-text `dur_ms`, `backend`, `model`; `--vectors` adds the vectors. | `qa/qa-api.sh embed "walks in the park" --query --backend npu` |
| `ask <user text> [--system s] [--schema file.json] [--max-tokens n] [--backend b] [--force]` | One LLM call, a fresh conversation: `text`, `chars`, `load_ms`, `dur_ms`, `backend`, `model`. Also `temperature` and `top_k` via `raw`. | `qa/qa-api.sh ask "Say hi" --backend cpu` |
| `run_job <transcribe\|translate\|roles\|window\|summary\|localize> <session> [--backend b] [--force] [--timeout s]` | Queues that pipeline job for the session and follows it until it settles (`window` queues the extract and commit jobs after it; `extract`/`commit` need a window and cannot be queued alone, follow them with `wait`). Returns `status`, `attempts`, `error_code`, `dur_ms`. | `qa/qa-api.sh run_job summary petr-k_s01 --backend gpu` |
| `raw <command> [json]` | Any command with a JSON argument. | `qa/qa-api.sh raw status` |
| `screenshot <file.png>` | Host side: brings the app forward and takes `adb exec-out screencap -p`. Not an API command. | `qa/qa-api.sh screenshot out/home.png` |

Error codes: `bad_request`, `unknown_command`, `forbidden_caller`, `not_qa_build`, `vault_locked`, `vault_not_set_up`, `vault_already_set_up`, `bad_password`, `not_found`, `bad_path`, `busy`, `failed`. Paths in arguments must be inside `/sdcard/Android/data/com.therapylocal.app.qa/files/` (relative paths are taken from there); anything else is `bad_path`.

## Settings

The settings are the keys of the app's `settings` preferences that a user can change. `get_settings` lists them with their allowed values (taken from the app's own enums and ranges, so the list follows the app) and the current value; `set_setting` validates against that and then calls the same setter the app does. Values are typed in the JSON (strings, booleans, numbers); on the command line and in `set_setting` pass them as text (`true`, `20`, `0.5`).

| Key | Type | Values | Default | Applied through |
|---|---|---|---|---|
| `app_language` | enum | `en`, `cs` | `en` (the phone's language applies until one is chosen) | `LocaleManager` on Android 13+, the `app_language` pref before (as `LocaleStore` does; no activity needed). With `processing_language` `english` it also queues `localize` for every session, so facts and summaries follow the new language |
| `default_language` | enum | `en`, `cs` | `en` | `SpeechLanguages.setDefault` |
| `processing_language` | enum | `english`, `native` | `english` | `ProcessingLanguageSetting.set` ([AD-17](AD/AD-17.md)). `english`: the transcript is translated to English first, every job reads English, facts and summaries are shown translated into the app language. `native`: the client's language is processed as spoken. Applies to sessions processed or reprocessed afterwards; changing it makes derived data stale, so Reprocess offers a rebuild |
| `summary_format` | enum | the ids of `SummaryConfig.FORMATS` (`soap`, ...) | `soap` | `SessionSummaryRepository.changeFormat`: the last summary of each client is rebuilt |
| `gemma_variant` | enum | `e2b`, `e4b` | `e2b` (no value stored) | `ModelSetup.selectGemma`; downloads the file if it is missing |
| `backend_override` | enum | `auto`, `npu`, `gpu`, `nnapi`, `cpu` | `auto` | the `backend_override` pref and `BackendPolicy.override`. A running UI keeps showing the old chip until the app restarts, and the loaded engines are not reloaded (the model commands take `--backend` per call anyway) |
| `keep_screen_awake` | bool | `true`, `false` | `true` | `DisplayConfig.setKeepScreenAwake` |
| `thermal_log` | bool | `true`, `false` | `true` | the `thermal_log` pref; starts the in-app thermal sampler if the queue is running ([Thermal log](#thermal-log)) |
| `live_cue_interval_ms` | int | 1000 to 10000 | 2000 | `saveLiveCueSettings`, then `Engines.onLiveCueSettingsChanged()` |
| `live_cue_lookback_s` | int | 5 to `LiveCueConfig.MAX_LOOKBACK_S` (see `get_settings`) | 20 | same |
| `live_cue_smoothing` | float | 0.05 to 1 | 0.5 | same |
| `live_cue_score_floor` | float | 0 to 1 | 0 | same |

Not listed on purpose: `offer_reprocess` and `knows_reel` (one-shot UI hints), `model_download_wifi_only` (the network choice of the download in flight, set by the download itself), and the guard's and perf counters in their own preference files.

**`reset` and settings.** `reset` removes only `summary_format` (it belongs to the vault's summaries, so it is back to `soap`). Every other setting survives, `gemma_variant` included, so `run_device.py --reset --gemma e4b` can set it either side of the reset.

## Choosing the backend

`--backend auto|cpu|gpu|npu|nnapi` (default `auto`). `auto` is the normal ladder (NPU, then GPU, then CPU, [AD-08](AD/AD-08.md), [AD-09](AD/AD-09.md)). A named backend is put first through the same `BackendPolicy` the QA probes use, and the CPU stays the fallback, so a backend the phone cannot run falls back instead of failing; the answer's `backend` is the one that actually loaded and `backend_requested` the one you asked for. `--force` also skips the guard's blocks and the memory gate for that one accelerator (as `BackendProbeActivity` does): the engine process may die and main carries on ([AD-01](AD/AD-01.md)). There is no new gate, and the engines write their usual `backend_*` lines. The policy is set for the duration of the call and then put back.

The model commands build the same engine classes the pipeline does (`SpeechRecogniser`, `SpeakerDiarizer`, `Embedder`, `MemoryLlm` through the engine hosts), so they answer `busy` while the job queue is working: one model at a time. Pipeline jobs report no `backend_used` yet: the engines the runner loads log `backend_proven` (tag `TL`); `run_job` returns the requested backend and says so.

## Export and import (round trip)

`export` writes `files/api/export.json`:

```
{"format":"therapy-qa-manifest","format_version":1,"summary_format":"soap","processing_language":"english",
 "clients":[{"id","name","lang","first_name","last_name","created_at"}],
 "sessions":[{"client","session","started_at","speaker_map":{"S0":"therapist",...},
              "segments":[{"idx","startMs","endMs","speaker","role","text","text_en"}],
              "speaker_maps":[{"version","map","source","confidence","createdAt"}],
              "summary":{"state","stale","format","text","sections","generatedAt"},
              "facts":[{"heading","content","evidence","segIdx","segTo","startMs","endMs","speaker","role",...}]}]}
```

`text_en` is the English working text of a segment when the `translate` job made one ([AD-17](AD/AD-17.md); absent in `native` mode and for English clients), and `processing_language` is the mode the export was taken in. `facts` hold the stored text, which is English in `english` mode; the screens show the translation. Import ignores both.

`import_manifest` takes this file back: it creates the clients and each session from `segments` and the current `speaker_map`, then queues the pipeline from the window job on. **Not round-tripped:** audio (never exported; import audio again with `import_session`), the older speaker-map versions (only the current map is imported, as version 1 with source `import`), and facts, summaries and windows (derived: the pipeline makes them again, so they can differ from the export, which is the point of a regression run). Session ids and client ids are kept, so an import into a reset vault reproduces the same ids.

## What is not here

- `screen`/`open` to a named screen: no navigation seam (see above).
- Instrumented test of the provider (shell user accepted, other app refused): the instrumented tests run against the `debug` build, which has no provider. The JVM tests (`QaApiTest`, `./gradlew :app:testQaUnitTest`) cover parsing, JSON shapes, the shell-uid check, the locked-vault rule and the refusal on a non-QA id; the provider itself is exercised by `qa/qa-api.sh` on a phone.
- The `bench/` runners are host-side (llama.cpp and the LiteRT CLI on CI machines) and stay there; `ask`, `transcribe`, `diarize` and `embed` are their on-phone counterpart. `qa/asr-eval.sh` still uses its instrumented `AsrEvalTest` (debug APK).

## Adding a command

A feature a test run needs adds its command in the same change ([AD-16](AD/AD-16.md) point 7): a method on `QaBackend`, its entry in `QaApi.commands`, a JVM test, a row in the table above, and, if it takes arguments, a case in `qa/qa-api.sh`. A new user-facing setting is one entry in `appSettings` (`qaapi/QaSettings.kt`), nothing else.

## Thermal log

Heat and throttling can be lined up with the pipeline jobs (CPU, GPU, NPU model runs) from two records. Both are for the QA phone only and read system sensors, never app data.

**Host side, every sensor: `qa/thermal-log.sh -s SERIAL -o thermal.csv [-n SECONDS]`.** One long-lived `adb shell` loop on the phone writes a block per second (about 1 s apart, measured 0.99 to 1.03 s), and `qa/thermal/thermal_log.py` turns it into CSV, flushed after each sample. Per second: every HAL sensor of `dumpsys thermalservice` (name, value, throttling status), every readable `/sys/class/thermal/thermal_zone*/temp`, `dumpsys battery` (level, temperature, voltage, status, plugged, charge counter), the thermal status, `/proc/loadavg`, the CPU frequencies and `/sys/class/power_supply` (SELinux hides it from the shell on the SM8750 phone: marked unreadable). The file starts with `#` comment lines (read it with `pandas.read_csv(path, comment='#')`): phone model, SoC, Android version, the SoC thermal status at start, the exact sensor list and the unreadable ones. Columns are `group:source:name`; the **`soc`** group holds the application processor clusters, GPU, NPU/DSP (`nsp*`), `aoss`, modem, `ddr`, camera and video blocks, the PMICs (`pm*`), `skin` and `quiet`, plus `soc:soc_max_c`, the hottest of them each second. `power:` columns are volts, amps and percent readings that sit next to the temperatures (`vbat`, `ibat`, `socd`, `*-bcl-*`); they are not temperatures. `python3 qa/thermal/thermal_log.py summary thermal.csv` prints max and mean per sensor (SoC first, with an overall SoC max), seconds above 45 C for skin and battery, and throttle events. Tests: `python3 qa/thermal/test_thermal_log.py`, with real dumps in `qa/thermal/fixtures/`.

`e2e/sittings/run_device.py` starts it in the background before the import, stops it in a `finally`, and writes `<out>/thermal.csv` and `<out>/thermal-summary.txt` (printed at the end). `--no-thermal` turns it off. A sensor or command that cannot be read is an empty cell; the run never fails because of it.

**In the app, what an ordinary app can read: `thermal_log` setting.** `QaThermalSampler` (source set `qa` only, so `release`, `debug` and `feel` have neither the class nor the lines, and `verifyNoQaApiInRelease` fails if one gets in) writes, while the job queue has a running job and the setting is on (default on), one DEBUG line per second, `tag=Thermal evt=thermal_sample`: `job`, `bat_c`, `level`, `mv`, `ua` (BatteryManager current, microamps), `plugged`, `thermal_status`, `headroom_10s` (`PowerManager.getThermalHeadroom`), and `soc_max_c` and `soc` (`zone:C` list) for the SoC zones the app can read. It first logs `thermal_sensors_found` once per process (`zones_total`, `readable`, `unreadable`, `soc_readable`, `soc_zones`), then a `thermal_summary` (max/mean) when the queue goes quiet. Unreadable zones are counted, never logged one by one. Work outside the queue (the model commands of this API run only while the queue is idle) is not sampled; use the host record for those.
