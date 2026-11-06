#!/usr/bin/env bash
# Builds the app and its instrumented tests with scripts/build.sh, installs both with adb and runs them on the connected phone.
#   qa/run-device-tests.sh                 all instrumented tests
#   qa/run-device-tests.sh language        only tests in the language package
#   qa/run-device-tests.sh ClientLanguageTest[#method]   one class or method
#   SKIP_BUILD=1 qa/run-device-tests.sh    reuse the last build
#   INSTR_ARGS='-e annotation ...Full -e numShards 2 -e shardIndex 0 -e lane stress-a'   extra `am instrument` arguments (CI lanes)
#   OUT_DIR=out qa/run-device-tests.sh     after the run (also when it failed), pull what a failure needs into out/:
#                                          logs/ stress/ logcat.txt exits.txt dropbox.txt tombstones.txt meminfo-end.txt instrument.txt perfetto/
# The APKs come from scripts/build.sh (the pinned builder); Gradle's own device bridge is not used.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# The SDK's own adb is an x86 binary and does not run on this arm64 machine: use the system one.
ADB="${ADB:-$(command -v adb)}"
APP_ID=com.therapylocal.app
RUNNER=androidx.test.runner.AndroidJUnitRunner

"$ADB" get-state >/dev/null 2>&1 || { echo "No phone connected (adb devices)." >&2; exit 2; }

if [[ -z "${SKIP_BUILD:-}" ]]; then
  echo "== build"
  "$ROOT/scripts/build.sh" fast
fi
APK="$ROOT/dist/Therapy-main.apk"
TEST_APK="$ROOT/dist/Therapy-main-androidTest.apk"
[[ -f "$APK" && -f "$TEST_APK" ]] || { echo "Build failed: APKs missing." >&2; exit 1; }

# What a lane leaves behind (catalogue 4.2). Runs in an EXIT trap so it also happens when the tests failed; it never changes the exit status.
LOGS_DIR="/sdcard/Android/data/$APP_ID/files"
pull_results() {
  local rc=$?
  [[ -n "${OUT_DIR:-}" ]] || return $rc
  set +e +u +o pipefail
  mkdir -p "$OUT_DIR/logs" "$OUT_DIR/stress" "$OUT_DIR/perfetto"
  "$ADB" pull "$LOGS_DIR/logs/." "$OUT_DIR/logs/" >/dev/null 2>&1
  "$ADB" pull "$LOGS_DIR/stress/." "$OUT_DIR/stress/" >/dev/null 2>&1
  "$ADB" logcat -d -b all > "$OUT_DIR/logcat.txt" 2>&1
  "$ADB" shell dumpsys activity exit-info "$APP_ID" > "$OUT_DIR/exits.txt" 2>&1
  "$ADB" shell dumpsys dropbox --print data_app_crash data_app_native_crash data_app_anr SYSTEM_TOMBSTONE 2>&1 | head -c 5242880 > "$OUT_DIR/dropbox.txt"
  # Tombstones are only listable as root (google_apis images); the list is empty elsewhere. Their content is not pulled: it can hold prompt bytes.
  { "$ADB" shell ls -l /data/tombstones 2>&1; "$ADB" shell dumpsys dropbox 2>&1 | grep -i tombstone; } > "$OUT_DIR/tombstones.txt"
  "$ADB" shell dumpsys meminfo "$APP_ID" > "$OUT_DIR/meminfo-end.txt" 2>&1
  for f in $("$ADB" shell ls /data/misc/perfetto-traces/ 2>/dev/null | tr -d '\r' | grep '\.pftrace$'); do
    "$ADB" pull "/data/misc/perfetto-traces/$f" "$OUT_DIR/perfetto/$f" >/dev/null 2>&1
  done
  [[ -f "${OUT:-}" ]] && cp "$OUT" "$OUT_DIR/instrument.txt"
  echo "== pulled results into $OUT_DIR"
  return $rc
}
if [[ -n "${OUT_DIR:-}" ]]; then
  mkdir -p "$OUT_DIR"
  trap pull_results EXIT
  # The lane's logcat must reach back to the first test: 16 MB buffers, emptied first.
  "$ADB" logcat -G 16M >/dev/null 2>&1; "$ADB" logcat -c >/dev/null 2>&1
fi

# A sleeping screen means no activity can resume and every Compose test fails with "No compose hierarchies found".
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1

echo "== install"
"$ADB" install -r -t "$APK" >/dev/null
"$ADB" install -r -t "$TEST_APK" >/dev/null

FILTER=()
if [[ $# -gt 0 ]]; then
  case "$1" in
    *[A-Z]*) FILTER=(-e class "$(grep -rl "class ${1%%#*}\b" "$ROOT/app/src/androidTest" | head -1 | sed -E "s#.*/java/##; s#\.kt\$##; s#/#.#g")${1:+${1#"${1%%#*}"}}") ;;
    *)       FILTER=(-e package "$APP_ID.$1") ;;
  esac
fi

echo "== run ${FILTER[*]:-all}"
OUT="$(mktemp)"
read -r -a EXTRA_ARGS <<<"${INSTR_ARGS:-}"
"$ADB" shell am instrument -w -r "${FILTER[@]}" "${EXTRA_ARGS[@]}" "$APP_ID.test/$RUNNER" | tr -d '\r' | tee "$OUT" \
  | grep -E '^INSTRUMENTATION_STATUS: (test|class)=' | paste - - | sed -E 's/INSTRUMENTATION_STATUS: //g' | awk '!seen[$0]++' || true

echo "== result"
grep -E 'INSTRUMENTATION_STATUS_CODE: -[12]|stack=|^OK \(|^FAILURES' "$OUT" | head -40 || true
if grep -q '^OK (' "$OUT"; then echo "PASS: $(grep '^OK (' "$OUT")"; exit 0; fi
echo "FAIL (full log: $OUT)"; exit 1
