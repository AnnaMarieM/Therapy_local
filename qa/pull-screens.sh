#!/usr/bin/env bash
# Pulls the screenshot tour's output (AD-15) from the device into $1: <screen>/<WxH>.png, manifest.tsv, results.tsv.
set -uo pipefail
ADB="${ADB:-$(command -v adb)}"
OUT="${1:?usage: qa/pull-screens.sh <dir>}"
mkdir -p "$OUT"
"$ADB" pull /sdcard/Android/data/com.therapylocal.app/files/screens/. "$OUT/" >/dev/null
ls "$OUT" | wc -l
