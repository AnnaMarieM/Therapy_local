#!/usr/bin/env bash
# Shared helpers of the HOST rows (catalogue tier HOST, level H). Sourced by every qa/stress/<id>.sh; run them through qa/run-host-stress.sh.
#
# The lab entry (plan S11, not in the tree before it lands): `EngineLabActivity`, DUMP-protected, so `am start` from the shell works:
#   am start -n $APP/.debug.EngineLabActivity --es scenario processing|live|search --es plan '<fault plan json, catalogue 2.3>'
#            [--ez fgs true] [--ez background true] [--es canary CANARY-PT-...] [--es action vault_lock]
# `action vault_lock` calls the Vault.lock() kill path. Until S11 lands every script reports SKIP lab_entry_missing, which the summary counts.
#
# Every script ends in `pass`, `fail <message>` or `skip <reason>`: one `tl.stress.v1` line in $OUT_DIR/stress/results.jsonl, exit 0/1/0.
set -uo pipefail

APP=com.therapylocal.app
ADB="${ADB:-$(command -v adb)}"
OUT_DIR="${OUT_DIR:-out}"
SCALE="${SCALE:-1}"
LOGS=/sdcard/Android/data/$APP/files/logs
LAB=$APP/.debug.EngineLabActivity
TEST_ID="${TEST_ID:-$(basename "${BASH_SOURCE[1]:-$0}" .sh)}"
T0=$(date +%s%3N)
mkdir -p "$OUT_DIR/stress" "$OUT_DIR/host/$TEST_ID"

adbsh() { "$ADB" shell "$@" | tr -d '\r'; }
note() { echo "[$TEST_ID] $*" >&2; }

# Optional timing facts for the results line: timing name ms
declare -A TIMINGS=()
timing() { TIMINGS[$1]=$2; }

# JSON string escape without jq
jstr() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

finish() { # status message
  local status=$1 msg="${2:-}" dur=$(( $(date +%s%3N) - T0 )) timings="" k
  for k in "${!TIMINGS[@]}"; do timings+="\"$k\":${TIMINGS[$k]},"; done
  local failure="null" skip="null"
  [[ $status == failed ]] && failure="{\"assertion\":\"host-script\",\"message\":$(jstr "$msg"),\"first_divergent_event\":null}"
  [[ $status == skipped ]] && skip=$(jstr "$msg")
  printf '{"schema":"tl.stress.v1","lane":"%s","test":"%s","class":"HostScript","method":"%s","tier":"HOST","status":"%s","skip_reason":%s,"duration_ms":%s,"timings_ms":{%s},"failure":%s}\n' \
    "${LANE:-host-stress}" "$TEST_ID" "$TEST_ID" "$status" "$skip" "$dur" "${timings%,}" "$failure" >> "$OUT_DIR/stress/results.jsonl"
  snapshot_logs
  note "$status ${msg}"
}
pass() { finish passed; exit 0; }
fail() { finish failed "$*"; exit 1; }
skip() { finish skipped "$*"; exit 0; }

snapshot_logs() { "$ADB" pull "$LOGS/." "$OUT_DIR/host/$TEST_ID/" >/dev/null 2>&1 || true; }

need_root() { "$ADB" root >/dev/null 2>&1; sleep 1; [[ "$(adbsh id -u)" == 0 ]] || skip "adb_root_unavailable"; }
need_lab() { adbsh dumpsys package $APP | grep -q EngineLabActivity || skip "lab_entry_missing"; }

# pids of the app's processes: main, :live, :batch
pid_main()  { adbsh pidof $APP | awk '{print $1}'; }
pid_batch() { adbsh pidof "$APP:batch" | awk '{print $1}'; }
pid_live()  { adbsh pidof "$APP:live" | awk '{print $1}'; }
have_batch() { [[ -n "$(pid_batch)" ]]; }
have_live() { [[ -n "$(pid_live)" ]]; }

now_ms() { date +%s%3N; }

# until <timeout_ms> <command...>: polls every 100 ms (a bound, never a sleep to synchronise); returns the elapsed ms in $ELAPSED.
ELAPSED=0
until_ok() {
  local limit=$1; shift; local t=$(now_ms)
  while ! "$@"; do
    ELAPSED=$(( $(now_ms) - t ))
    (( ELAPSED > limit )) && return 1
    sleep 0.1
  done
  ELAPSED=$(( $(now_ms) - t )); return 0
}

log_has() { # file regex
  adbsh cat "$LOGS/$1" 2>/dev/null | grep -Eq "$2"
}
gone() { [[ -z "$(adbsh pidof "$APP:$1")" ]]; }
all_hosts_gone() { gone live && gone batch; }

lab() { # args to EngineLabActivity
  "$ADB" shell am start -W -n "$LAB" "$@" >/dev/null || fail "am start of the lab entry failed"
}
fresh_app() { # clean logs and a stopped app, so the run starts from nothing
  "$ADB" shell am force-stop $APP; "$ADB" shell rm -rf "$LOGS"; mkdir -p "$OUT_DIR"
}
plan() { # id rules-json  -> a plan with the lab defaults (catalogue 2.3)
  printf '{"v":1,"id":"%s","scale":%s,"gate":"allow_all","fake":{"llm":{"ctx":4096,"reply":"echo"},"embed":{"dim":768},"asr":{"mode":"crc"},"resident_mb":0},"rules":[%s]}' "$1" "$SCALE" "$2"
}
# The F02 plan: crash at host_start on the first two starts of :batch.
plan_f02() { plan "$1" '{"role":"batch","at":"host_start","when":{"start":[1,2]},"do":{"kind":"signal","sig":"SEGV","thread":"binder"}}'; }
