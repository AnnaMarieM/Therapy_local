#!/usr/bin/env bash
# E03 (HOST): lab processing in the background with an FGS, started through the lab entry without instrumentation; am kill of the app. Main survives; hosts bound at background priority may die (recorded).
source "$(dirname "$0")/lib.sh"

need_lab; fresh_app
lab --es scenario processing --es plan "$(plan E03 "")" --ez fgs true --ez background true
until_ok 30000 have_batch || fail "no :batch host came up"
main0=$(pid_main); batch0=$(pid_batch)
"$ADB" shell am kill $APP
sleep 2 # the stimulus has to settle before the look; nothing waits on it
[[ "$(pid_main)" == "$main0" ]] || fail "main did not survive (FGS must keep it)"
[[ "$(pid_batch)" == "$batch0" ]] && timing batch_survived 1 || timing batch_survived 0
pass
