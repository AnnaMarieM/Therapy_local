#!/usr/bin/env bash
# Screenshots of the app at several display sizes, for looking at: dist/screens/<preset>/<name>.png. The qa build (its own vault, screenshots
# allowed). Start it already unlocked and with a client or two, or the shots show the gate.
#   qa/screenshots.sh                 every preset, portrait and landscape
#   PRESETS="1600x2560@320" qa/screenshots.sh
#   SPLIT=1 qa/screenshots.sh         also the app in split-screen beside the settings app (needs a tablet)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(command -v adb)}"
PKG=com.therapylocal.app.qa
OUT="$ROOT/dist/screens"
"$ADB" get-state >/dev/null 2>&1 || { echo "No device connected (adb devices)." >&2; exit 2; }
"$ADB" shell pm path "$PKG" >/dev/null 2>&1 || { echo "$PKG is not installed: ./gradlew :app:assembleQa and adb install -r first." >&2; exit 2; }

DEFAULT_PRESETS="1080x2400@420 600x960@213 1200x1920@240 1600x2560@320"
read -r -a presets <<< "${PRESETS:-$DEFAULT_PRESETS}"

restore() {
  "$ADB" shell wm size reset >/dev/null 2>&1
  "$ADB" shell wm density reset >/dev/null 2>&1
  "$ADB" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
  "$ADB" shell settings put system user_rotation 0 >/dev/null 2>&1
}
trap restore EXIT INT TERM

shot() { mkdir -p "$OUT/$1"; "$ADB" exec-out screencap -p > "$OUT/$1/$2.png"; echo "$OUT/$1/$2.png"; }

"$ADB" shell settings put system accelerometer_rotation 0
for p in "${presets[@]}"; do
  size="${p%@*}"; dpi="${p#*@}"; dir="${size}-${dpi}dpi"
  "$ADB" shell wm size "$size"; "$ADB" shell wm density "$dpi"
  for rot in 0 1; do
    name=$([[ $rot == 0 ]] && echo portrait || echo landscape)
    "$ADB" shell settings put system user_rotation "$rot"
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 4
    shot "$dir" "$name"
  done
  if [[ -n "${SPLIT:-}" ]]; then
    "$ADB" shell settings put system user_rotation 1
    "$ADB" shell am start -n "$PKG/com.therapylocal.app.MainActivity" --windowingMode 3 >/dev/null 2>&1   # 3 = split-screen primary
    "$ADB" shell am start -a android.settings.SETTINGS --windowingMode 4 >/dev/null 2>&1                  # 4 = split-screen secondary
    sleep 4
    shot "$dir" "split-landscape"
  fi
done
echo "Done. Screens in $OUT"
