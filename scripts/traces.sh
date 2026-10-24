#!/bin/bash
# Pull and summarise the app's trace logs from the phone.
#   scripts/traces.sh pull [dir]      copy the rotating logs to ./traces (or dir)
#   scripts/traces.sh summary [dir]   pull, then print stage durations per chunk, span stats, backends, errors
#   scripts/traces.sh live            follow logcat (tag TL) on the phone
#   scripts/traces.sh grep <expr>     pull, then grep the logs, for example: grep 'rid=3'
set -euo pipefail
PKG=com.therapylocal.app
REMOTE=/sdcard/Android/data/$PKG/files/logs
HERE=$(cd "$(dirname "$0")" && pwd)
cmd=${1:-summary}
dir=${2:-traces}

pull() {
  rm -rf "$dir" && mkdir -p "$dir"
  adb pull "$REMOTE/." "$dir/" >/dev/null
  ls -1 "$dir" | sed 's/^/pulled /'
}

case "$cmd" in
  pull) pull ;;
  summary)
    pull
    # oldest first so the table reads in time order
    files=$(ls -1 "$dir"/tl*.log | sort -r)
    python3 -I "$HERE/trace_summary.py" $files ;;
  live) adb logcat -v time -s TL ;;
  grep) pull; shift; grep -h "$@" "$dir"/tl*.log ;;
  *) echo "usage: $0 pull|summary|live|grep <expr> [dir]"; exit 1 ;;
esac
