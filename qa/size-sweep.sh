#!/usr/bin/env bash
# Runs the instrumented tests under several screen sizes and densities on the connected device, then puts the display back.
#   qa/size-sweep.sh                  every preset, all tests
#   qa/size-sweep.sh ui               every preset, only the ui package
#   PRESETS="800x1280@213 1600x2560@320" qa/size-sweep.sh   your own list (WxH@dpi in px and dpi)
#   SKIP_BUILD=1 qa/size-sweep.sh     reuse the last build (first run builds)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(command -v adb)}"
"$ADB" get-state >/dev/null 2>&1 || { echo "No device connected (adb devices)." >&2; exit 2; }

# Phone, 7" tablet, 10" tablet, and the halves of a 10" tablet's split-screen (a smaller window is the same as a smaller display here).
DEFAULT_PRESETS="1080x2400@420 600x960@213 1200x1920@240 1600x2560@320 2560x1600@320 800x2560@320 1280x1600@320"
read -r -a presets <<< "${PRESETS:-$DEFAULT_PRESETS}"

restore() {
  "$ADB" shell wm size reset >/dev/null 2>&1
  "$ADB" shell wm density reset >/dev/null 2>&1
  "$ADB" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
  echo "== display restored"
}
trap restore EXIT INT TERM

"$ADB" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
fail=0
first=1
for p in "${presets[@]}"; do
  size="${p%@*}"; dpi="${p#*@}"
  echo "== preset $size @ ${dpi}dpi"
  "$ADB" shell wm size "$size" && "$ADB" shell wm density "$dpi" || { echo "cannot set $p"; fail=1; continue; }
  sleep 2
  if [[ $first == 1 ]]; then first=0; else export SKIP_BUILD=1; fi
  "$ROOT/qa/run-device-tests.sh" "$@" || { echo "FAILED at $p"; fail=1; }
done
exit $fail
