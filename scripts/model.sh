#!/bin/bash
# Inspect or seed the on-device model so the app never has to re-download it.
#   scripts/model.sh check            show model file, size and sha256 on the phone
#   scripts/model.sh push <file>      copy a local .litertlm into the app's model folder
set -euo pipefail
DIR=/sdcard/Android/data/com.therapylocal.app/files/models
NAME=gemma-4-E2B-it.litertlm
case "${1:-check}" in
  check)
    adb shell "ls -l $DIR/"
    adb shell "sha256sum $DIR/$NAME" ;;
  push)
    adb shell "mkdir -p $DIR"
    adb push "$2" "$DIR/$NAME" ;;
  *) echo "usage: $0 check | push <file>"; exit 1 ;;
esac
