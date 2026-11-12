#!/bin/bash
# Once-per-second record of every temperature sensor of a QA phone plus battery state, as CSV. Host side, system adb, system sensors only:
# it reads `dumpsys thermalservice`, `dumpsys battery`, /sys/class/thermal, /sys/class/power_supply, /proc/loadavg and the CPU frequencies.
# It touches no app, no package and no app data (so it cannot touch com.therapylocal.app).
#
#   qa/thermal-log.sh -s SERIAL -o thermal.csv [-n SECONDS]     run until Ctrl-C / SIGTERM (or SECONDS)
#   qa/thermal/thermal_log.py summary thermal.csv                       max / mean per sensor, time above 45 C, throttle events
#
# One long-lived `adb shell` loop on the phone writes a raw text block per second, so timing stays near 1 s; qa/thermal/thermal_log.py turns
# the stream into the CSV (one flush per sample). docs/QA_API.md, "Thermal log".
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${ANDROID_SERIAL:-}"; OUT=""; SECS=0
while getopts "s:o:n:" o; do case $o in s) SERIAL=$OPTARG;; o) OUT=$OPTARG;; n) SECS=$OPTARG;; *) echo "usage: $0 -s SERIAL -o out.csv [-n seconds]" >&2; exit 2;; esac; done
[ -n "$SERIAL" ] && [ -n "$OUT" ] || { echo "usage: $0 -s SERIAL -o out.csv [-n seconds]" >&2; exit 2; }
case "$SECS" in ''|*[!0-9]*) echo "-n takes whole seconds" >&2; exit 2;; esac

read -r -d '' REMOTE <<'SH'
SECS=__SECS__
echo "@@H model $(getprop ro.product.model)"
echo "@@H soc_manufacturer $(getprop ro.soc.manufacturer)"
echo "@@H soc_model $(getprop ro.soc.model)"
echo "@@H platform $(getprop ro.board.platform)"
echo "@@H hardware $(getprop ro.hardware)"
echo "@@H release $(getprop ro.build.version.release)"
echo "@@H sdk $(getprop ro.build.version.sdk)"
echo "@@H build $(getprop ro.build.display.id)"
for z in /sys/class/thermal/thermal_zone*; do
  ty=; v=x; { read -r ty < "$z/type"; } 2>/dev/null; { read -r v < "$z/temp"; } 2>/dev/null
  ok=0; [ "$v" != x ] && ok=1
  echo "@@ZN ${z##*thermal_zone} $ok $ty"
done
for p in /sys/class/power_supply/*; do for f in temp current_now voltage_now capacity; do
  [ -d "$p" ] || continue; v=x; { read -r v < "$p/$f"; } 2>/dev/null; ok=0; [ "$v" != x ] && ok=1
  echo "@@PN ${p##*/} $f $ok"
done; done
for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do c=${f#/sys/devices/system/cpu/}; echo "@@FN ${c%%/*}"; done
t0=$(date +%s%3N); i=0
while :; do
  now=$(date +%s%3N)
  echo "@@S $now"
  echo "@@B"; dumpsys battery 2>&1
  echo "@@T"; dumpsys thermalservice 2>&1
  zs=; for z in /sys/class/thermal/thermal_zone*; do v=x; { read -r v < "$z/temp"; } 2>/dev/null; zs="$zs $v"; done; echo "@@Z $zs"
  ps=; for p in /sys/class/power_supply/*; do for f in temp current_now voltage_now capacity; do [ -d "$p" ] || continue; v=x; { read -r v < "$p/$f"; } 2>/dev/null; ps="$ps $v"; done; done; echo "@@P $ps"
  l=; { read -r l < /proc/loadavg; } 2>/dev/null; echo "@@L $l"
  fs=; for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do v=x; { read -r v < "$f"; } 2>/dev/null; fs="$fs $v"; done; echo "@@F $fs"
  i=$((i+1))
  [ "$SECS" -gt 0 ] && [ "$i" -ge "$SECS" ] && break
  d=$(( t0 + i*1000 - $(date +%s%3N) ))
  [ "$d" -gt 0 ] && { [ "$d" -ge 1000 ] && d=999; sleep "0.$(printf %03d "$d")"; }
done
SH
REMOTE="${REMOTE//__SECS__/$SECS}"

FIFO="$(mktemp -u)"; mkfifo "$FIFO" || exit 1
trap 'kill "$APID" 2>/dev/null; rm -f "$FIFO"' EXIT
adb -s "$SERIAL" shell "$REMOTE" > "$FIFO" 2>/dev/null &
APID=$!
python3 "$HERE/thermal/thermal_log.py" convert --out "$OUT" < "$FIFO" &
PPID_PY=$!
trap 'kill "$APID" 2>/dev/null' TERM INT
wait "$PPID_PY"
kill "$APID" 2>/dev/null; wait "$APID" 2>/dev/null
rm -f "$FIFO"
exit 0
