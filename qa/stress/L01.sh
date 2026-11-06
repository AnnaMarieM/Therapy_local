#!/usr/bin/env bash
# L01 (HOST): wall clock +3 days during the F02 backoff; the backoff intervals are unchanged (elapsedRealtime) and there is no burst of restarts.
source "$(dirname "$0")/lib.sh"

need_root; need_lab; fresh_app
lab --es scenario processing --es plan "$(plan_f02 L01)"
until_ok 30000 log_has tl.log "evt=engine_died" || fail "no engine_died"
"$ADB" shell date "$(date -d "+3 days" +%m%d%H%M%Y.%S)" >/dev/null
trap "\"\$ADB\" shell settings put global auto_time 1; \"\$ADB\" shell date \"\$(date +%m%d%H%M%Y.%S)\" >/dev/null" EXIT
until_ok $((40000 * ${SCALE%.*})) log_has engine-batch.log "evt=host_start.*start_no=3" || fail "no third start"
snapshot_logs
bo=$(grep -h "evt=engine_restart" "$OUT_DIR/host/$TEST_ID/tl.log" | sed -E "s/.*backoff_ms=([0-9]+).*/\1/" | tr "\n" " ")
note "backoffs: $bo"
[[ "$bo" == "250 500 "* ]] || fail "backoff intervals changed by the clock jump: $bo"
[[ $(grep -c "evt=engine_restart" "$OUT_DIR/host/$TEST_ID/tl.log") -le 2 ]] || fail "burst of restarts after the clock jump"
pass
