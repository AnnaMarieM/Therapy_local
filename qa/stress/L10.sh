#!/usr/bin/env bash
# L10 (HOST): lab processing with delay 3 s at initialize in :batch, then Vault.lock() during the load, then restart the app. The next start records no strike for the batch backend: its PENDING entry is judged by :batch host_exit why=client_died (halt 10), not by main SIGNALED 9.
source "$(dirname "$0")/lib.sh"

need_root; need_lab; fresh_app
RULE="{\"role\":\"batch\",\"model\":\"fake-llm\",\"at\":\"initialize\",\"when\":{\"nth\":1,\"times\":1},\"do\":{\"kind\":\"delay\",\"delay_ms\":3000}}"
lab --es scenario processing --es plan "$(plan L10 "$RULE")"
until_ok 30000 log_has engine-batch.log "evt=backend_phase.*phase=initialize" || fail "the load never reached initialize"
before=$(adbsh run-as $APP cat shared_prefs/backend_guard.xml 2>/dev/null)
lab --es action vault_lock
until_ok 2000 all_hosts_gone || fail "hosts alive after Vault.lock()"
lab --es scenario processing --es plan "$(plan L10b "")"
until_ok 30000 have_batch || fail "no :batch host after the restart"
snapshot_logs
grep -q "evt=host_exit.*why=client_died" "$OUT_DIR/host/$TEST_ID/engine-batch.log" || fail "engine-batch.log has no host_exit why=client_died"
grep -Eh "evt=backend_strike" "$OUT_DIR/host/$TEST_ID"/tl*.log | grep -q . && fail "a strike was recorded for the batch backend"
after=$(adbsh run-as $APP cat shared_prefs/backend_guard.xml 2>/dev/null)
echo "$before" > "$OUT_DIR/host/$TEST_ID/guard-before.xml"; echo "$after" > "$OUT_DIR/host/$TEST_ID/guard-after.xml"
pass
