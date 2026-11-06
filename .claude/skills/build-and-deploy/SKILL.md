---
name: build-and-deploy
description: How to build, install and ship MindVault (com.therapylocal.app), and the two kinds of build - the open QA build (adb-driven, screenshots, no fingerprint) and the customer build as shipped (fingerprint, FLAG_SECURE, no debug exports). Use when asked to build, deploy, install on a phone/tablet, run device tests, take screenshots, cut a release, or pick a build type/variant.
---

# Build and deploy

## Architecture of the build
- **One builder.** Every APK for tests, CI and releases is built inside the pinned `therapy-builder` Docker image (`docker/Dockerfile`, tagged with the first 12 hex of the Dockerfile's sha256, so editing the Dockerfile rebuilds the image). Never run `./gradlew` on the host for an APK you will test or ship.
- **Entry points:** `scripts/build.sh` (Linux/macOS, bash) and `scripts/build.ps1` (Windows, PowerShell). Both do the same thing: build the image, `docker run` with the repo mounted at `/workspace`, then run `docker/inside.sh <mode>`, which runs Gradle and copies the APKs to `dist/`. `inside.sh` is the single place that lists the Gradle tasks.
- **Image contents:** Temurin JDK 17 (digest-pinned), Android cmdline-tools, `platforms;android-34`, `build-tools;34.0.0`, and Google's `aapt2` (checksum-verified), exposed as `/opt/aapt2/aapt2` and passed to Gradle with `-Pandroid.aapt2FromMavenOverride`.
- **CPU architecture (x86_64 vs arm64).** The image runs natively on both. Only `aapt2` is x86_64-only: on an arm64 host (Apple silicon, Windows-on-Arm, an arm64 Linux box, GitHub's `ubuntu-24.04-arm`) the launcher runs it under `qemu-user-static` (software emulation, no KVM needed, slower resource steps). Both entry points print which case they are in. The app itself is arm64-v8a (plus x86_64 in debug only, for the CI emulator). Host `adb` must match the host CPU: the SDK's bundled adb is x86 and does not run on arm64, so use the system `adb` (`qa/*.sh` already do: `ADB=${ADB:-$(command -v adb)}`).
- **Reproducible.** The same commit must give byte-identical APKs on x86_64 and arm64: fixed `/workspace` path, `TZ=UTC`, `SOURCE_DATE_EPOCH` from the last commit, the same aapt2 binary. CI job `reproducible-arm` rebuilds on arm64 and diffs the sha256 against the x86_64 build. Do not add timestamps, host paths or non-pinned downloads to the build.
- **Gradle cache.** Linux: host folder `~/.gradle-therapy-builder` (override with `GRADLE_USER_HOME_HOST`). Windows: the named Docker volume `therapy-builder-gradle-home` (bind mounts from NTFS are slow). `local.properties` (your own SDK path) is masked inside the container so the image SDK is always used.
- **Requirements:** Docker (Docker Desktop with Linux containers on Windows), `git` (for `SOURCE_DATE_EPOCH`), and `adb` on the host for deploying. Nothing else: no host JDK or Android SDK is needed to build.

## Build: Linux / macOS (bash)
```bash
scripts/build.sh full   # clean, lint, unit tests, debug APK + androidTest APK
scripts/build.sh fast   # clean + the two APKs only
# -> dist/Therapy-main.apk, dist/Therapy-main-androidTest.apk, dist/Therapy-main.apk.sha256
```
It takes minutes: run it as a background command and look at the log afterwards.

## Build: Windows (PowerShell)
```powershell
.\scripts\build.ps1 full    # or: .\scripts\build.ps1 fast   (Docker Desktop, Linux containers)
# -> dist\Therapy-main.apk, dist\Therapy-main-androidTest.apk, dist\Therapy-main.apk.sha256
adb install -r dist\Therapy-main.apk
adb shell am start -n com.therapylocal.app/.MainActivity
```
CI only parses `build.ps1` (`powershell-syntax` job), because Linux containers do not run on GitHub's Windows runners; it is exercised by hand. The `qa/*.sh` helpers below are bash: on Windows run them from WSL2 or Git Bash with `adb` on the PATH, or issue the equivalent `adb` commands yourself (they are plain `adb install`, `adb shell am instrument`, `adb shell wm size`, `adb exec-out screencap -p`). There are no PowerShell versions of them yet.

## Build the other types (QA, release, feel)
`build.sh` / `build.ps1` only build the **debug** type. For the others run the same image with another Gradle task (`:app:assembleQa`, `:app:assembleRelease`, `:app:assembleFeel`). Output: `app/build/outputs/apk/<type>/app-<type>.apk`.

Linux / macOS:
```bash
image="therapy-builder:$(sha256sum docker/Dockerfile | cut -c1-12)"   # macOS: shasum -a 256
docker run --rm --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle-home -e ANDROID_USER_HOME=/tmp/home/.android \
  -v "$PWD":/workspace -v "$HOME/.gradle-therapy-builder":/gradle-home -v "$(mktemp):/workspace/local.properties:ro" -w /workspace "$image" \
  bash -c 'mkdir -p $HOME && ./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 :app:assembleQa'
```
Windows (PowerShell):
```powershell
$root = (Get-Location).Path
$tag = (Get-FileHash -Algorithm SHA256 docker\Dockerfile).Hash.ToLower().Substring(0, 12)
docker run --rm `
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle-home -e ANDROID_USER_HOME=/tmp/home/.android `
  -v "${root}:/workspace" -v therapy-builder-gradle-home:/gradle-home -w /workspace "therapy-builder:$tag" `
  bash -c 'mkdir -p $HOME && ./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 :app:assembleQa'
```
(Build the image first if it is missing: `docker build -q -t therapy-builder:<tag> docker`; the normal build scripts do this.) Note these runs skip the `SOURCE_DATE_EPOCH` the scripts set, so such an APK is for testing, not for the reproducibility comparison.

## The two variants

There are two things we care about. Everything else is a convenience build between them.

| | **QA build** ("open") | **Customer build** ("as shipped") |
|---|---|---|
| Gradle type | `qa` | `release` |
| App id | `com.therapylocal.app.qa` (own vault and models, installs beside the real app, never touches it) | `com.therapylocal.app` |
| Unlock | **Password only.** No fingerprint (`BIOMETRIC=false`), so a script can unlock via adb | **Password or fingerprint/Class 3 face** (`BIOMETRIC=true`, Keystore key, BiometricPrompt) |
| Screenshots / screen recording / recents preview | **Allowed** (`SECURE_WINDOW=false`) | **Blocked**: `FLAG_SECURE` on the window, patient names never reach screenshots or recents |
| Debug harness (`memory_lab`, raw ML screen) | Reachable | Not reachable |
| Decrypted audio export (`EXPORT_AUDIO`) | On (debug aid) | **Off** |
| Transcript text in logs (`LOG_TEXT`) | Off | Off (never turn on; this is a therapy app, see `docs/LOGGING.md`) |
| ABI | arm64-v8a | arm64-v8a only |
| Speed | Release speed (R8 off, optimised) | Release speed |
| Signing | Shared debug key | **Unsigned today**: `release` has no signingConfig. A real customer signing key/process is not in the repo; do not invent one |
| Data | **Test data only**, never a real client | Real patient data |

Use QA for anything driven by adb: screenshots (`qa/screenshots.sh`), UI automation, tablet/size checks, demos with fake clients. Treat it as insecure by design and never put real client data in it.

Use the customer build for anything that must behave as it will for a therapist: fingerprint unlock, `FLAG_SECURE`, lock/erase behaviour, final performance checks. Security behaviour (see `docs/SECURITY.md`) is only verified on this one, never on QA. Never "fix" a QA-only limitation by loosening `BIOMETRIC`/`SECURE_WINDOW` on `release`; the flags are set per build type in `app/build.gradle.kts`.

### The in-between builds
| Type | Use | Notes |
|---|---|---|
| `debug` (what `scripts/build.sh` makes and CI publishes as the rolling `main` prerelease) | Day-to-day dev, instrumented tests, `adb install -r` | Has the app id of the real app, the shared debug key, biometrics and `FLAG_SECURE` **on** (so adb screencap is black), `EXPORT_AUDIO` on, x86_64 + arm64 |
| `feel` | Tuning motion/haptics (debug Compose stutters) | Release speed, debug key, same app id as the real app, security flags **on** |

Per-type flags live in `app/build.gradle.kts` (`buildTypes`, `buildTypes.configureEach`). Check them there if this table seems out of date.

## Deploy to a device (adb)
- **Update with `adb install -r`, never uninstall.** Uninstalling deletes the ~2.6 GB model. Everyone signs with `app/debug.keystore` (committed) so updates install over each other; a different key forces an uninstall. See `docs/CONNECT_PHONE.md`.
- Dev/test: `adb install -r dist/Therapy-main.apk` (PowerShell: `dist\Therapy-main.apk`) then `adb shell am start -n com.therapylocal.app/.MainActivity`.
- QA: `adb install -r app/build/outputs/apk/qa/app-qa.apk`, package `com.therapylocal.app.qa`. It downloads its own models.
- Several devices: pass `-s <serial>` (`adb devices -l`).

## Tests on a device
```bash
qa/run-device-tests.sh              # all instrumented tests (builds first unless SKIP_BUILD=1)
qa/run-device-tests.sh ui           # one package; or a class: AdaptiveLayoutTest[#method]
qa/size-sweep.sh [filter]           # same tests under several `wm size`/`wm density` presets; restores the display afterwards
SPLIT=1 qa/screenshots.sh           # screenshots per size/rotation into dist/screens/ (QA build must be installed and unlocked)
```
The runner installs the **debug** APKs over the app with the real app id. The tests use their own databases, but warn the user if a real vault is on that device.

## CI and release
- `.github/workflows/ci.yml`: `build` (`scripts/build.sh`, x86_64), `reproducible-arm` (arm64 rebuild, sha256 must match), `powershell-syntax` (parses `build.ps1` on Windows), `android-test` (emulator, phone and tablet profiles), `release-main` (pushes to `main` publish `Therapy-main.apk` as the rolling "Latest main build" prerelease; this is the **debug** build, not a customer build).
- A customer release (signed `release` APK) is not automated yet. If asked to produce one, say what is missing (signing key and distribution channel) instead of shipping the debug APK or an unsigned one.
