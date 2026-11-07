#!/bin/bash
# usage: lab.sh <tag> <timeout_s> <am extras...>
S=$ANDROID_SERIAL; P=com.therapylocal.app.qa; D=/sdcard/Android/data/$P/files
tag=$1; to=$2; shift 2
B=/home/martin/projects/therapylocal_workspaces/.bigcache/npueval
exec 9>/tmp/tl-device-$ANDROID_SERIAL.lock; flock 9
adb -s $S shell rm -f $D/out/prooflab_$tag.json
adb -s $S shell am force-stop $P
adb -s $S logcat -c
adb -s $S shell am start -n $P/com.therapylocal.app.debug.ProofLabActivity --es tag $tag "$@" >/dev/null
for ((t=0;t<to;t+=5)); do
  sleep 5
  adb -s $S shell "test -f $D/out/prooflab_$tag.json" 2>/dev/null && break
  adb -s $S get-state >/dev/null 2>&1 || { echo "DEVICE GONE"; break; }
done
adb -s $S pull $D/out/prooflab_$tag.json $B/out/ 2>&1 | tail -1
adb -s $S logcat -d -s PROOFLAB:I | cut -c1-200 | grep -v '"reply"' > $B/logs/$tag.log
