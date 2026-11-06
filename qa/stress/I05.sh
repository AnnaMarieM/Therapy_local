#!/usr/bin/env bash
# I05 (HOST): main is killed with the lab running processing in the background and no FGS; the hosts exit within 2 s, and engine-batch.log shows host_client_died then host_exit.
source "$(dirname "$0")/lib.sh"

need_root; need_lab; fresh_app
lab --es scenario processing --es plan "$(plan I05 "")" --ez background true
until_ok 30000 have_batch || fail "no :batch host came up"
"$ADB" shell kill -9 "$(pid_main)"
until_ok $((2000 * ${SCALE%.*})) all_hosts_gone || fail "hosts still alive ${ELAPSED} ms after main died"
timing host_exit_latency "$ELAPSED"
snapshot_logs
grep -Eq "evt=host_client_died" "$OUT_DIR/host/$TEST_ID/engine-batch.log" || fail "engine-batch.log has no host_client_died"
awk "/evt=host_client_died/{d=1} d&&/evt=host_exit/{f=1} END{exit !f}" "$OUT_DIR/host/$TEST_ID/engine-batch.log" || fail "host_exit does not follow host_client_died"
pass
