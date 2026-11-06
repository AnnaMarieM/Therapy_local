#!/usr/bin/env bash
# I07 (HOST): am force-stop with both hosts up. The next app start has no strikes; the main exit is USER_REQUESTED (BENIGN), host exits are not counted as main's.
source "$(dirname "$0")/lib.sh"

need_lab; fresh_app
lab --es scenario live --es plan "$(plan I07 "")"
until_ok 30000 have_live || fail "no :live host came up"
"$ADB" shell am force-stop $APP
lab --es scenario processing --es plan "$(plan I07b "")"
until_ok 30000 have_batch || fail "no :batch host after the restart"
snapshot_logs
if grep -Eh "evt=(backend_strike|backend_blocked|backend_failed)" "$OUT_DIR/host/$TEST_ID"/tl*.log | grep -q .; then fail "a strike or block after force-stop"; fi
adbsh dumpsys activity exit-info $APP | grep -Eiq "user request|USER_REQUESTED" || fail "no user-requested exit record for main"
pass
