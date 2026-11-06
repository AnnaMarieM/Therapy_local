#!/usr/bin/env bash
# L02 (HOST): wall clock -1 day during the idle wait; the idle stop fires at idleMs +- 300 ms, measured on the shell's own monotonic clock.
source "$(dirname "$0")/lib.sh"

need_root; need_lab; fresh_app
IDLE_MS=1000
lab --es scenario processing --es plan "$(plan L02 "")"
until_ok 30000 have_batch || fail "no :batch host"
until_ok 30000 log_has tl.log "evt=engine_unbound" || fail "the lab never unbound"
up0=$(awk "{print int(\$1*1000)}" < <(adbsh cat /proc/uptime))
"$ADB" shell date "$(date -d "-1 day" +%m%d%H%M%Y.%S)" >/dev/null
trap "\"\$ADB\" shell settings put global auto_time 1; \"\$ADB\" shell date \"\$(date +%m%d%H%M%Y.%S)\" >/dev/null" EXIT
until_ok 10000 log_has tl.log "evt=engine_idle_stop" || fail "no engine_idle_stop"
up1=$(awk "{print int(\$1*1000)}" < <(adbsh cat /proc/uptime))
d=$((up1 - up0)); timing idle_stop_ms "$d"
(( d >= IDLE_MS - 300 && d <= IDLE_MS + 300 + 600 )) || fail "idle stop after $d ms, expected $IDLE_MS +- 300 (plus the 600 ms poll granularity of the unbind look)"
pass
