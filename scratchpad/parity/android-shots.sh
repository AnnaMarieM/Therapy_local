#!/usr/bin/env bash
# Android half of the Android-vs-iOS screenshot parity pass (lane A2b, issue #133).
#
# Drives the QA build (com.therapylocal.app.qa) through the screens the iOS app captures
# with its DEBUG `-MVShot` harness, and writes one PNG per screen:
#
#   scratchpad/parity/android-shots.sh                    # -> /tmp/opencode/android-shots/
#   OUT=dist/parity/android scratchpad/parity/android-shots.sh
#
# The iOS half is the `screenshots` job in .github/workflows/ios.yml (artifact
# `ios-screenshots`). The comparison is a vision pass over both sets; the screen names
# match, see README.md next to this file.
#
# Needs a phone or an emulator attached over adb (docs/CONNECT_PHONE.md). There is no
# local emulator on the CI host (no KVM), so in practice this runs on the POCO.
# It reads only: it taps the app's own UI and never changes a client, deletes a session
# or downloads a model. A QA vault that is not set up yet is set up with the standard
# QA password, because the unlock screen is the first thing to photograph.
set -euo pipefail

PKG=com.therapylocal.app.qa
ACTIVITY=com.therapylocal.app.MainActivity
PASSWORD=12345678
OUT=${OUT:-/tmp/opencode/android-shots}
ADB=${ADB:-adb}

die() { echo "error: $*" >&2; exit 1; }

command -v "$ADB" >/dev/null 2>&1 || die "adb not found in PATH"
[[ -n "$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')" ]] \
  || die "no adb device attached: plug the QA phone in (docs/CONNECT_PHONE.md) or boot an emulator, then rerun. Nothing was captured."
"$ADB" shell pm path "$PKG" >/dev/null 2>&1 \
  || die "$PKG is not installed: ./gradlew :app:assembleQa and adb install -r first."

mkdir -p "$OUT"

# Animations off, so the same state is on screen when the screenshot is taken.
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
restore() {
  "$ADB" shell settings put global window_animation_scale 1 >/dev/null 2>&1 || true
  "$ADB" shell settings put global transition_animation_scale 1 >/dev/null 2>&1 || true
  "$ADB" shell settings put global animator_duration_scale 1 >/dev/null 2>&1 || true
}
trap restore EXIT INT TERM

# --- looking at the screen ---------------------------------------------------------
# `uiautomator` is the only seam the compiled QA app has (its Compose navigation is local
# state, docs/QA_API.md), so labels are matched in the accessibility tree. If the in-app
# copy changes, update the labels at the bottom of this file, not the helpers.

ui() { "$ADB" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 && "$ADB" exec-out cat /sdcard/window_dump.xml; }

# center_of <substring> [--field]: prints "x y" of the first clickable node whose text or
# content-desc contains the substring (case-insensitive), falling back to the first match;
# with --field, of the first EditText.
center_of() {
  local needle=$1 mode=${2:-any}
  ui | python3 -c '
import re, sys
needle = sys.argv[1].lower()
mode = sys.argv[2]
xml = sys.stdin.read()
found = []
for node in re.findall(r"<node[^>]*>", xml):
    attrs = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", node))
    if mode == "field":
        if attrs.get("class") != "android.widget.EditText":
            continue
    else:
        hay = (attrs.get("text", "") + " " + attrs.get("content-desc", "")).lower()
        if needle not in hay:
            continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", attrs["bounds"]))
    found.append((attrs.get("clickable") == "true", (x1 + x2) // 2, (y1 + y2) // 2))
if not found:
    sys.exit(1)
clickable = [f for f in found if f[0]]
_, x, y = (clickable or found)[0]
print(x, y)
' "$needle" "$mode"
}

tap_text() {  # tap_text <substring>
  local xy
  xy=$(center_of "$1") || return 1
  # shellcheck disable=SC2086
  "$ADB" shell input tap $xy
  sleep 1
}

# The gate buttons are press-and-release ("Release to create"); a short hold is a press.
press_text() {  # press_text <substring> [hold_ms]
  local xy
  xy=$(center_of "$1") || return 1
  # shellcheck disable=SC2086
  "$ADB" shell input swipe $xy $xy "${2:-700}"
  sleep 1
}

type_password() {
  local xy
  xy=$(center_of "" --field) || return 1
  # shellcheck disable=SC2086
  "$ADB" shell input tap $xy
  sleep 1
  "$ADB" shell input text "$PASSWORD"
  sleep 1
  "$ADB" shell input keyevent KEYCODE_ENTER >/dev/null 2>&1 || true
  return 0
}

shot() {  # shot <name>
  sleep 2
  "$ADB" exec-out screencap -p > "$OUT/$1.png"
  echo "  $OUT/$1.png"
}

# --- the walk ----------------------------------------------------------------------

"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 6
echo "gate"
shot gate

# First run: language, four welcome pages, permissions, speech language, then create a
# password. Later runs: straight to the unlock page. Walk whatever is in the way.
for _ in $(seq 1 14); do
  if center_of "" --field >/dev/null 2>&1; then break; fi
  if press_text "continue" 900; then continue; fi
  if press_text "set a password" 900; then continue; fi
  if press_text "allow" 900; then continue; fi
  break
done
echo "password page"
shot gate-password

if center_of "" --field >/dev/null 2>&1; then
  type_password
  if press_text "create" 900; then
    sleep 8   # first run also derives the key
  elif press_text "unlock" 400; then
    sleep 5
  fi
fi
echo "home"
shot home

# A client page: home rows are "open <Name>", so match the first one.
if tap_text "open "; then
  sleep 2
  shot profile
  # The session page is the record button on the client page (best effort: the label
  # is only present when the client has no session in progress).
  if tap_text "record"; then
    sleep 3
    shot session
    "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    sleep 1
  fi
  "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 1
else
  echo "  (no client on this vault: profile/session skipped)"
fi

if tap_text "new client"; then
  sleep 2
  shot add-client
  "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 1
fi

if tap_text "settings"; then
  sleep 2
  shot settings
fi

echo "Done. Android screens in $OUT — compare with the iOS artifact ios-screenshots, see README.md."
