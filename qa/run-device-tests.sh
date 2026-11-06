#!/usr/bin/env bash
# Builds the app and its instrumented tests with scripts/build.sh, installs both with adb and runs them on the connected phone.
#   qa/run-device-tests.sh                 all instrumented tests
#   qa/run-device-tests.sh language        only tests in the language package
#   qa/run-device-tests.sh ClientLanguageTest[#method]   one class or method
#   SKIP_BUILD=1 qa/run-device-tests.sh    reuse the last build
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
"$ADB" shell am instrument -w -r "${FILTER[@]}" "$APP_ID.test/$RUNNER" | tr -d '\r' | tee "$OUT" \
  | grep -E '^INSTRUMENTATION_STATUS: (test|class)=' | paste - - | sed -E 's/INSTRUMENTATION_STATUS: //g' | awk '!seen[$0]++' || true

echo "== result"
grep -E 'INSTRUMENTATION_STATUS_CODE: -[12]|stack=|^OK \(|^FAILURES' "$OUT" | head -40 || true
if grep -q '^OK (' "$OUT"; then echo "PASS: $(grep '^OK (' "$OUT")"; exit 0; fi
echo "FAIL (full log: $OUT)"; exit 1
