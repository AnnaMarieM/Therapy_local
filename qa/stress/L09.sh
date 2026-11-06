#!/usr/bin/env bash
# L09 (HOST): adb install -r of the same APK while the hosts run; the hosts die for the package update, classified BENIGN, and no strikes follow after the restart.
source "$(dirname "$0")/lib.sh"

need_lab; fresh_app
APK="${APK:-dist/Therapy-main.apk}"; [[ -f "$APK" ]] || skip "apk_missing:$APK"
lab --es scenario processing --es plan "$(plan L09 "")"
until_ok 30000 have_batch || fail "no :batch host came up"
"$ADB" install -r -t "$APK" >/dev/null || fail "install -r failed"
until_ok 5000 all_hosts_gone || fail "hosts survived the package update"
lab --es scenario processing --es plan "$(plan L09b "")"
until_ok 30000 have_batch || fail "no :batch host after the update"
snapshot_logs
grep -Eh "evt=(backend_strike|backend_blocked)" "$OUT_DIR/host/$TEST_ID"/tl*.log | grep -q . && fail "a strike after the package update"
pass
