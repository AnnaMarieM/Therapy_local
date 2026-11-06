#!/usr/bin/env bash
# F08 (HOST): the F02 storm with the app backgrounded and an FGS, started through the lab entry without instrumentation; restarts succeed in the background.
source "$(dirname "$0")/lib.sh"

need_lab; fresh_app
lab --es scenario processing --es plan "$(plan_f02 F08)" --ez fgs true --ez background true
until_ok $((40000 * ${SCALE%.*})) log_has engine-batch.log "evt=host_start.*start_no=3" || fail "the third start (gen 3) never came up in the background"
timing third_start "$ELAPSED"
log_has tl.log "evt=engine_restart" || fail "no engine_restart in tl.log"
pass
