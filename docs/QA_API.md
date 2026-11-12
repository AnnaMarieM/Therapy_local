# QA control API

Everything you do by hand or with UI taps on the QA build is one call. Decision: [AD-16](AD/AD-16.md). The API is compiled from the `qa` source set (`app/src/qa/java/com/therapylocal/app/qaapi/`): the QA build (`com.therapylocal.app.qa`) has it, `release`, `debug` and `feel` do not, and the `build` job fails if a trace of it is found in the release or feel APK (`verifyNoQaApiInRelease`). QA holds **test data only**.

```
qa/qa-api.sh [-s serial] [-p package] [--no-launch] <command> [args]     # bash and the system adb; -s picks the phone
```

The standard QA vault password is **`12345678`**. `setup` and `unlock` use it when you give none. It exists only in the QA source set.

## How it works

- **Transport.** An exported content provider, authority `<applicationId>.qaapi`, called with `adb shell content call --uri content://com.therapylocal.app.qa.qaapi --method <command> --arg b64:<base64 of a JSON object>` (the script does this). The manifest asks for `android.permission.DUMP` (the shell has it, apps cannot get it) **and** every call checks that the caller's uid is the adb shell's (2000); any other caller, including the app itself, gets `forbidden_caller` and nothing is written. No socket, no HTTP, no network. The provider exists only in the QA manifest.
- **Result.** JSON: `{"api_version":1,"command":"status","ok":true,"data":{...}}`, or `{"ok":false,"error":{"code":"vault_locked","message":"..."}}`. The result is always written to `/sdcard/Android/data/com.therapylocal.app.qa/files/api/<command>.json` (atomic) and the call returns a Bundle with `ok`, `code`, `file`, `bytes` and, when under 100 kB, `json`. The script reads the file and prints it. Exit code: 0 ok, 1 API error, 2 usage, 3 transport, 4 timeout. Messages never contain clinical text; results do (it is test data) and live only in `files/api/`, which `reset` empties.
- **Locked vault.** Only `status`, `setup` and `unlock` work. Everything else answers `vault_locked` (or `vault_not_set_up`). A QA vault whose password you do not know cannot be reset through the API; clear it as `AGENTS.md` says (`adb shell pm clear com.therapylocal.app.qa`, which also deletes the models).
- **Real app.** The script refuses any package that does not end in `.qa`; the app refuses every command unless its application id ends in `.qa` and is not `com.therapylocal.app`. Nothing here touches `com.therapylocal.app`.
- **Bring the app forward.** Starting the queue runner is a foreground-service start, which Android refuses for an app in the background. Commands that queue work (`import_*`, `wait`, `run_job`, `swap_roles`, `set_summary_format`) therefore first run `am start` on the QA `MainActivity` (turn off with `--no-launch`). The screen must be on.
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
| `set_summary_format <id>` | The vault-wide summary format (`soap`, ...); each client's last summary is rebuilt. `unknown format` lists them. | `qa/qa-api.sh set_summary_format soap` |
| `set_gemma_variant <e2b\|e4b>` | Which Gemma build the memory LLM uses; the app picks by RAM otherwise (E4B needs 11 GiB). The file must be in the models folder or it is downloaded. Survives `reset`. | `qa/qa-api.sh set_gemma_variant e4b` |
| `reset` | Wipes the QA vault: keys, database, retained audio, results. **The downloaded models are not touched** (`models_kept`). The vault is then `not_set_up`: run `setup`. Needs an unlocked vault. | `qa/qa-api.sh reset && qa/qa-api.sh setup` |
| `transcribe <audio> [--language xx] [--start s] [--dur s] [--backend b] [--force]` | Canary on one clip (at most the live-cue limit; use `--start`/`--dur`). Returns `text`, `audio_sec`, `load_ms`, `dur_ms`, `rtf`, `backend` (used), `backend_requested`, `model`. | `qa/qa-api.sh transcribe a.wav --language cs --backend gpu` |
| `diarize <audio> [--speakers n] [--backend b] [--force]` | The diarizer on a whole recording (default 2 speakers, AD-07): `turns` (`start_ms`, `end_ms`, `speaker`), `speakers`, `load_ms`, `dur_ms`, `rtf`, `backend`, `model`. | `qa/qa-api.sh diarize petr-k_s01.wav` |
| `embed <text> [--query] [--vectors] [--backend b] [--force]` | The text embedder (`raw embed '{"texts":[...]}'` for several): `dim`, `norm`, per-text `dur_ms`, `backend`, `model`; `--vectors` adds the vectors. | `qa/qa-api.sh embed "walks in the park" --query --backend npu` |
| `ask <user text> [--system s] [--schema file.json] [--max-tokens n] [--backend b] [--force]` | One LLM call, a fresh conversation: `text`, `chars`, `load_ms`, `dur_ms`, `backend`, `model`. Also `temperature` and `top_k` via `raw`. | `qa/qa-api.sh ask "Say hi" --backend cpu` |
| `run_job <transcribe\|roles\|window\|summary> <session> [--backend b] [--force] [--timeout s]` | Queues that pipeline job for the session and follows it until it settles (`window` queues the extract and commit jobs after it; `extract`/`commit` need a window and cannot be queued alone, follow them with `wait`). Returns `status`, `attempts`, `error_code`, `dur_ms`. | `qa/qa-api.sh run_job summary petr-k_s01 --backend gpu` |
| `raw <command> [json]` | Any command with a JSON argument. | `qa/qa-api.sh raw status` |
| `screenshot <file.png>` | Host side: brings the app forward and takes `adb exec-out screencap -p`. Not an API command. | `qa/qa-api.sh screenshot out/home.png` |

Error codes: `bad_request`, `unknown_command`, `forbidden_caller`, `not_qa_build`, `vault_locked`, `vault_not_set_up`, `vault_already_set_up`, `bad_password`, `not_found`, `bad_path`, `busy`, `failed`. Paths in arguments must be inside `/sdcard/Android/data/com.therapylocal.app.qa/files/` (relative paths are taken from there); anything else is `bad_path`.

## Choosing the backend

`--backend auto|cpu|gpu|npu|nnapi` (default `auto`). `auto` is the normal ladder (NPU, then GPU, then CPU, [AD-08](AD/AD-08.md), [AD-09](AD/AD-09.md)). A named backend is put first through the same `BackendPolicy` the QA probes use, and the CPU stays the fallback, so a backend the phone cannot run falls back instead of failing; the answer's `backend` is the one that actually loaded and `backend_requested` the one you asked for. `--force` also skips the guard's blocks and the memory gate for that one accelerator (as `BackendProbeActivity` does): the engine process may die and main carries on ([AD-01](AD/AD-01.md)). There is no new gate, and the engines write their usual `backend_*` lines. The policy is set for the duration of the call and then put back.

The model commands build the same engine classes the pipeline does (`SpeechRecogniser`, `SpeakerDiarizer`, `Embedder`, `MemoryLlm` through the engine hosts), so they answer `busy` while the job queue is working: one model at a time. Pipeline jobs report no `backend_used` yet: the engines the runner loads log `backend_proven` (tag `TL`); `run_job` returns the requested backend and says so.

## Export and import (round trip)

`export` writes `files/api/export.json`:

```
{"format":"therapy-qa-manifest","format_version":1,"summary_format":"soap",
 "clients":[{"id","name","lang","first_name","last_name","created_at"}],
 "sessions":[{"client","session","started_at","speaker_map":{"S0":"therapist",...},
              "segments":[{"idx","startMs","endMs","speaker","role","text"}],
              "speaker_maps":[{"version","map","source","confidence","createdAt"}],
              "summary":{"state","stale","format","text","sections","generatedAt"},
              "facts":[{"heading","content","evidence","segIdx","segTo","startMs","endMs","speaker","role",...}]}]}
```

`import_manifest` takes this file back: it creates the clients and each session from `segments` and the current `speaker_map`, then queues the pipeline from the window job on. **Not round-tripped:** audio (never exported; import audio again with `import_session`), the older speaker-map versions (only the current map is imported, as version 1 with source `import`), and facts, summaries and windows (derived: the pipeline makes them again, so they can differ from the export, which is the point of a regression run). Session ids and client ids are kept, so an import into a reset vault reproduces the same ids.

## What is not here

- `screen`/`open` to a named screen: no navigation seam (see above).
- Instrumented test of the provider (shell user accepted, other app refused): the instrumented tests run against the `debug` build, which has no provider. The JVM tests (`QaApiTest`, `./gradlew :app:testQaUnitTest`) cover parsing, JSON shapes, the shell-uid check, the locked-vault rule and the refusal on a non-QA id; the provider itself is exercised by `qa/qa-api.sh` on a phone.
- The `bench/` runners are host-side (llama.cpp and the LiteRT CLI on CI machines) and stay there; `ask`, `transcribe`, `diarize` and `embed` are their on-phone counterpart. `qa/asr-eval.sh` still uses its instrumented `AsrEvalTest` (debug APK).

## Adding a command

A feature a test run needs adds its command in the same change ([AD-16](AD/AD-16.md) point 7): a method on `QaBackend`, its entry in `QaApi.commands`, a JVM test, a row in the table above, and, if it takes arguments, a case in `qa/qa-api.sh`.
