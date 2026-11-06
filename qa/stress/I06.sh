#!/usr/bin/env bash
# I06 (HOST): lab processing with canary text, then the lab calls the Vault.lock() kill path; all hosts are dead within 2 s and no canary is in anything the app wrote (L5 over files/, cache/, external files, logs).
source "$(dirname "$0")/lib.sh"

need_root; need_lab; fresh_app
CANARY=CANARY-PT-7f3a-I06
lab --es scenario processing --es plan "$(plan I06 "")" --es canary "$CANARY"
until_ok 30000 have_batch || fail "no :batch host came up"
lab --es action vault_lock
until_ok 2000 all_hosts_gone || fail "hosts alive ${ELAPSED} ms after Vault.lock()"
timing host_exit_latency "$ELAPSED"
hits=$(adbsh "grep -rl $CANARY /data/data/$APP/files /data/data/$APP/cache /sdcard/Android/data/$APP 2>/dev/null")
[[ -z "$hits" ]] || fail "canary found in: $hits"
pass
