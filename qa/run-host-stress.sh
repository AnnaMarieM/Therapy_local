#!/usr/bin/env bash
# The HOST lane (catalogue tier HOST, nine rows: I05 I06 I07 E03 F08 L01 L02 L09 L10): shell scripts that drive a non-instrumented app
# through the DUMP-protected lab entry, and kill, re-clock and reinstall it from outside. adb only; `adb root` where a row says so
# (google_apis images allow it).
#   qa/run-host-stress.sh                  all rows
#   qa/run-host-stress.sh I05 L09          some rows
#   OUT_DIR=out SCALE=1 APK=dist/Therapy-main.apk qa/run-host-stress.sh
# Results: $OUT_DIR/stress/results.jsonl (one tl.stress.v1 line per row, status passed|failed|skipped), $OUT_DIR/host/<id>/ (the logs of
# each row), plus the same pull as qa/run-device-tests.sh. Exit status: 1 when any row failed; a skipped row never fails the run but is
# counted in the summary (qa/stress-summary.py).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ADB="${ADB:-$(command -v adb)}" OUT_DIR="${OUT_DIR:-$ROOT/out}" SCALE="${SCALE:-1}" LANE="${LANE:-host-stress}"
export APK="${APK:-$ROOT/dist/Therapy-main.apk}"
"$ADB" get-state >/dev/null 2>&1 || { echo "No phone connected (adb devices)." >&2; exit 2; }
mkdir -p "$OUT_DIR/stress"
: > "$OUT_DIR/stress/results.jsonl"

if [[ -z "${SKIP_INSTALL:-}" ]]; then
  [[ -f "$APK" ]] || { echo "APK missing: $APK (build first or SKIP_INSTALL=1)" >&2; exit 1; }
  "$ADB" install -r -t "$APK" >/dev/null || exit 1
fi
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" logcat -G 16M >/dev/null 2>&1; "$ADB" logcat -c >/dev/null 2>&1

ROWS=("$@"); [[ ${#ROWS[@]} -gt 0 ]] || ROWS=(I05 I06 I07 E03 F08 L01 L02 L09 L10)
failed=0
for id in "${ROWS[@]}"; do
  script="$ROOT/qa/stress/$id.sh"
  [[ -x "$script" ]] || { echo "no such row: $id" >&2; failed=1; continue; }
  echo "== $id"
  TEST_ID="$id" timeout $((300 * ${SCALE%.*})) "$script" || { rc=$?; [[ $rc -eq 124 ]] && echo "$id timed out" >&2; failed=1; }
  # A clean start for the next row, whatever the last one did.
  "$ADB" shell am force-stop com.therapylocal.app >/dev/null 2>&1
done

"$ADB" logcat -d -b all > "$OUT_DIR/logcat.txt" 2>&1
"$ADB" shell dumpsys activity exit-info com.therapylocal.app > "$OUT_DIR/exits.txt" 2>&1
python3 "$ROOT/qa/stress-summary.py" "$OUT_DIR/stress/results.jsonl" --title "$LANE" || true
exit $failed
