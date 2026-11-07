#!/bin/bash
# usage: run_probe.sh <Activity> <outfile-prefix> <tag> [am start extras...]; waits for DONE, saves json + logcat + before/after to .bigcache
. "$(dirname "$0")/common.sh"
act=$1; prefix=$2; tag=$3; shift 3
adbq logcat -c
echo "before $(snap)" > $OUT/${tag}.meta
adbq shell am start -n $PKG/com.therapylocal.app.debug.$act "$@" >> $OUT/${tag}.meta
start=$(date +%s)
until adbq logcat -d -s MEMPROBE:I EMBEDPROBE:I BACKENDPROBE:I | grep -q "DONE tag=$tag"; do
  sleep 3; [ $(( $(date +%s) - start )) -gt ${TIMEOUT:-600} ] && { echo TIMEOUT >> $OUT/${tag}.meta; break; }
done
echo "after $(snap)" >> $OUT/${tag}.meta
adbq logcat -d > $OUT/${tag}.logcat
adbq pull $D/out/${prefix}_${tag}.json $OUT/${prefix}_${tag}.json >/dev/null 2>&1
cat $OUT/${tag}.meta
