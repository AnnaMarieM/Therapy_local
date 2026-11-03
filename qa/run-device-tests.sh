#!/usr/bin/env bash
# Builds the app and its instrumented tests, installs both with adb and runs them on the connected phone.
#   qa/run-device-tests.sh                 all instrumented tests
#   qa/run-device-tests.sh language        only tests in the language package
#   qa/run-device-tests.sh ClientLanguageTest[#method]   one class or method
#   SKIP_BUILD=1 qa/run-device-tests.sh    reuse the last build
# Gradle's own device bridge is not used: it tries the SDK's x86 adb and fails here.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# The SDK's own adb is an x86 binary and does not run on this arm64 machine: use the system one.
ADB="${ADB:-$(command -v adb)}"
APP_ID=com.therapylocal.app
RUNNER=androidx.test.runner.AndroidJUnitRunner

# Gradle 8.10 cannot run on JDK 25: use JAVA_HOME if it is 17-21, else the JDK 17 tarball from ~/dl-android (unpacked to /tmp/jdk17).
java_major() { "$1/bin/java" -version 2>&1 | sed -n '1s/.*"\([0-9]*\).*/\1/p'; }
if [[ -z "${JAVA_HOME:-}" ]] || ! [[ "$(java_major "$JAVA_HOME" 2>/dev/null || echo 0)" =~ ^(17|18|19|20|21)$ ]]; then
  JDK="$(ls -d /tmp/jdk17/jdk-17* 2>/dev/null | head -1 || true)"
  if [[ -z "$JDK" && -f "$HOME/dl-android/jdk.tgz" ]]; then
    mkdir -p /tmp/jdk17 && tar xzf "$HOME/dl-android/jdk.tgz" -C /tmp/jdk17
    JDK="$(ls -d /tmp/jdk17/jdk-17* | head -1)"
  fi
  [[ -n "$JDK" ]] || { echo "No JDK 17-21 found. Set JAVA_HOME." >&2; exit 2; }
  export JAVA_HOME="$JDK"
fi

"$ADB" get-state >/dev/null 2>&1 || { echo "No phone connected (adb devices)." >&2; exit 2; }

if [[ -z "${SKIP_BUILD:-}" ]]; then
  echo "== build"
  (cd "$ROOT" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline -q 2>&1 | grep -v '^WARNING' || true)
fi
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APK" && -f "$TEST_APK" ]] || { echo "Build failed: APKs missing." >&2; exit 1; }

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
