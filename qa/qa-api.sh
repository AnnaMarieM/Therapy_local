#!/usr/bin/env bash
# Host client of the QA control API (docs/QA_API.md, docs/AD/AD-16.md). QA build only (com.therapylocal.app.qa, test data, vault password 12345678).
#   qa/qa-api.sh [-s serial] [-p package] [--no-launch] <command> [args]
# Every call is `adb shell content call` against the QA build's provider; the JSON result is printed on stdout (exit 0 when "ok":true, 1 when
# the API answered with an error, 2 usage, 3 transport). Bash and the system adb only; no jq, no python.
set -uo pipefail

ADB=${ADB:-$(command -v adb)}
PKG=${QA_PACKAGE:-com.therapylocal.app.qa}
SERIAL=${ANDROID_SERIAL:-}
LAUNCH=1
die() { echo "qa-api: $*" >&2; exit "${2:-2}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIAL=$2; shift 2 ;;
    -p) PKG=$2; shift 2 ;;
    --no-launch) LAUNCH=0; shift ;;
    -h|--help|help) grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -5; exit 0 ;;
    *) break ;;
  esac
done
[[ $# -ge 1 ]] || die "no command; try: status setup unlock seed_clients import_session wait results swap_roles get_settings set_setting set_summary_format reset screenshot raw"
[[ -n "$ADB" ]] || die "adb not found"
case "$PKG" in
  com.therapylocal.app.qa) ;;
  *.qa) ;;
  *) die "refusing package $PKG: the QA API exists only in the QA build (com.therapylocal.app.qa); the real app is never touched" ;;
esac
a() { "$ADB" ${SERIAL:+-s "$SERIAL"} "$@"; }
BASE=/sdcard/Android/data/$PKG/files
STD_PASSWORD=12345678

jesc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
launch() { [[ $LAUNCH = 1 ]] && a shell am start -n "$PKG/com.therapylocal.app.MainActivity" >/dev/null 2>&1; return 0; }

# call <command> <json>: prints the result JSON, returns 0 (ok), 1 (API error), 3 (transport)
call() {
  local cmd=$1 json=${2:-\{\}} b64 out file
  b64=$(printf '%s' "$json" | base64 -w0 2>/dev/null || printf '%s' "$json" | base64 | tr -d '\n')
  out=$(a shell content call --uri "content://$PKG.qaapi" --method "$cmd" --arg "b64:$b64" 2>&1) || { echo "$out" >&2; return 3; }
  file=$(printf '%s' "$out" | sed -n 's/.*[ {]file=\([^,}]*\).*/\1/p' | head -1)
  if [[ -z $file ]]; then
    # a refused caller or a provider that is not there: no result file
    echo "$out" >&2; return 3
  fi
  local res; res=$(a exec-out cat "$file") || return 3
  printf '%s\n' "$res"
  [[ $res == *'"ok":true'* ]]
}

# phone_audio <host file | phone path>: a host file is converted to WAV if needed and pushed to files/api/in/; prints the path the API takes
phone_audio() {
  local file=$1 local_wav name
  if [[ -f $file ]]; then
    local_wav=$file
    if [[ ${file,,} != *.wav ]]; then
      command -v ffmpeg >/dev/null || die "$file is not a WAV and ffmpeg is not installed (qa/to-wav.sh converts too)"
      local_wav=$(mktemp --suffix=.wav); ffmpeg -loglevel error -y -i "$file" -ar 16000 -ac 1 "$local_wav" || die "ffmpeg failed"
    fi
    name=$(basename "${file%.*}").wav
    a shell mkdir -p "$BASE/api/in" >/dev/null
    a push "$local_wav" "$BASE/api/in/$name" >/dev/null || die "adb push failed" 3
    echo "api/in/$name"
  else
    echo "$file"   # already on the phone: relative to files/ or absolute inside it
  fi
}

# backend options shared by the model commands: --backend auto|cpu|gpu|npu|nnapi, --force
BACKEND_JSON=""
backend_opt() {
  case "$1" in
    --backend) BACKEND_JSON+=",\"backend\":\"$2\""; return 2 ;;
    --force) BACKEND_JSON+=",\"force\":true"; return 1 ;;
  esac
  return 0
}

cmd=$1; shift
case "$cmd" in
  status) call status ;;
  setup|unlock)
    pw=${1:-$STD_PASSWORD}; call "$cmd" "{\"password\":\"$(jesc "$pw")\"}" ;;
  seed_clients)
    f=${1:-$(dirname "$0")/../e2e/sittings/clients.json}
    [[ -f $f ]] || die "no such file: $f"
    call seed_clients "$(cat "$f")" ;;
  import_session)
    [[ $# -ge 2 ]] || die "usage: import_session <client> <audio file> [--session id] [--date yyyy-MM-dd] [--language xx]"
    client=$1; file=$2; shift 2
    session=""; date=""; lang=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --session) session=$2 ;; --date) date=$2 ;; --language) lang=$2 ;;
        *) die "unknown option $1" ;;
      esac; shift 2
    done
    phone=$(phone_audio "$file") || exit $?
    json="{\"client\":\"$(jesc "$client")\",\"file\":\"$(jesc "$phone")\""
    [[ -n $session ]] && json+=",\"session\":\"$(jesc "$session")\""
    [[ -n $date ]] && json+=",\"date\":\"$date\""
    [[ -n $lang ]] && json+=",\"language\":\"$lang\""
    launch; call import_session "$json}" ;;
  wait)
    total=${1:-600}; end=$((SECONDS + total)); launch
    while :; do
      left=$((end - SECONDS)); per=$(( left < 25 ? left : 25 )); (( per < 1 )) && per=1
      res=$(call wait "{\"timeout_s\":$per}") || { printf '%s\n' "$res"; exit 1; }
      if [[ $res == *'"idle":true'* ]]; then printf '%s\n' "$res"; exit 0; fi
      if (( SECONDS >= end )); then printf '%s\n' "$res"; echo "qa-api: wait timed out after ${total}s" >&2; exit 4; fi
    done ;;
  results)
    json="{"; out=""; sep=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --client) json+="$sep\"client\":\"$(jesc "$2")\""; sep=, ;;
        --session) json+="$sep\"session\":\"$(jesc "$2")\""; sep=, ;;
        -o) out=$2 ;;
        *) die "unknown option $1" ;;
      esac; shift 2
    done
    if [[ -n $out ]]; then call results "$json}" >"$out"; rc=$?; echo "qa-api: results -> $out" >&2; exit $rc; fi
    call results "$json}" ;;
  import_clients) f=${1:-$(dirname "$0")/../e2e/sittings/clients.json}; [[ -f $f ]] || die "no such file: $f"; call import_clients "$(cat "$f")" ;;
  import_manifest)
    [[ $# -ge 1 && -f $1 ]] || die "usage: import_manifest <manifest.json>  (clients and sessions; see docs/QA_API.md)"
    launch; call import_manifest "$(cat "$1")" ;;
  export)
    json="{"; out=""; sep=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --client) json+="$sep\"client\":\"$(jesc "$2")\""; sep=, ;;
        --session) json+="$sep\"session\":\"$(jesc "$2")\""; sep=, ;;
        -o) out=$2 ;;
        *) die "unknown option $1" ;;
      esac; shift 2
    done
    if [[ -n $out ]]; then call export "$json}" >"$out"; rc=$?; echo "qa-api: export -> $out" >&2; exit $rc; fi
    call export "$json}" ;;
  transcribe|diarize)
    [[ $# -ge 1 ]] || die "usage: $cmd <audio file> [--language xx] [--start s] [--dur s] [--speakers n] [--backend b] [--force]"
    file=$1; shift; BACKEND_JSON=""; extra=""
    while [[ $# -gt 0 ]]; do
      backend_opt "$@"; n=$?
      if (( n > 0 )); then shift $n; continue; fi
      case "$1" in
        --engine) extra+=",\"engine\":\"$2\"" ;; --beam) extra+=",\"beam\":$2" ;; --translate) extra+=",\"translate\":true"; shift; continue ;;
        --language) extra+=",\"language\":\"$2\"" ;; --start) extra+=",\"start_s\":$2" ;; --dur) extra+=",\"dur_s\":$2" ;; --speakers) extra+=",\"speakers\":$2" ;;
        *) die "unknown option $1" ;;
      esac; shift 2
    done
    phone=$(phone_audio "$file") || exit $?
    call "$cmd" "{\"file\":\"$(jesc "$phone")\"$extra$BACKEND_JSON}" ;;
  embed)
    [[ $# -ge 1 ]] || die "usage: embed <text> [--query] [--vectors] [--backend b] [--force]"
    text=$1; shift; BACKEND_JSON=""; extra=""
    while [[ $# -gt 0 ]]; do
      backend_opt "$@"; n=$?
      if (( n > 0 )); then shift $n; continue; fi
      case "$1" in --query) extra+=",\"query\":true"; shift; continue ;; --vectors) extra+=",\"vectors\":true"; shift; continue ;; *) die "unknown option $1" ;; esac
    done
    call embed "{\"text\":\"$(jesc "$text")\"$extra$BACKEND_JSON}" ;;
  ask)
    [[ $# -ge 1 ]] || die "usage: ask <user text> [--system s] [--schema file.json] [--max-tokens n] [--audio wav [--start s] [--dur s]] [--backend b] [--force]"
    user=$1; shift; BACKEND_JSON=""; extra=""
    while [[ $# -gt 0 ]]; do
      backend_opt "$@"; n=$?
      if (( n > 0 )); then shift $n; continue; fi
      case "$1" in
        --system) extra+=",\"system\":\"$(jesc "$2")\"" ;; --max-tokens) extra+=",\"max_tokens\":$2" ;;
        --schema) [[ -f $2 ]] || die "no such file: $2"; extra+=",\"schema\":$(cat "$2")" ;;
        --audio) phone=$(phone_audio "$2") || exit $?; extra+=",\"file\":\"$(jesc "$phone")\"" ;;
        --start) extra+=",\"start_s\":$2" ;; --dur) extra+=",\"dur_s\":$2" ;;
        --temperature) extra+=",\"temperature\":$2" ;; --top-k) extra+=",\"top_k\":$2" ;;
        *) die "unknown option $1" ;;
      esac; shift 2
    done
    call ask "{\"user\":\"$(jesc "$user")\"$extra$BACKEND_JSON}" ;;
  run_job)
    [[ $# -ge 2 ]] || die "usage: run_job <transcribe|translate|roles|window|summary|localize> <session> [--backend b] [--force] [--timeout s]"
    kind=$1; sess=$2; shift 2; BACKEND_JSON=""; total=600
    while [[ $# -gt 0 ]]; do
      backend_opt "$@"; n=$?
      if (( n > 0 )); then shift $n; continue; fi
      case "$1" in --timeout) total=$2 ;; *) die "unknown option $1" ;; esac; shift 2
    done
    launch; end=$((SECONDS + total)); jid=""
    while :; do
      left=$((end - SECONDS)); per=$(( left < 25 ? left : 25 )); (( per < 1 )) && per=1
      res=$(call run_job "{\"kind\":\"$kind\",\"session\":\"$(jesc "$sess")\",\"timeout_s\":$per${jid:+,\"job_id\":$jid}$BACKEND_JSON}") || { printf '%s\n' "$res"; exit 1; }
      jid=$(printf '%s' "$res" | sed -n 's/.*"job_id":\([0-9]*\).*/\1/p')
      if [[ $res == *'"settled":true'* ]]; then printf '%s\n' "$res"; exit 0; fi
      if (( SECONDS >= end )); then printf '%s\n' "$res"; echo "qa-api: job not settled after ${total}s" >&2; exit 4; fi
    done ;;
  swap_roles) [[ $# -ge 1 ]] || die "usage: swap_roles <session>"; launch; call swap_roles "{\"session\":\"$(jesc "$1")\"}" ;;
  get_settings) call get_settings ;;
  set_setting) [[ $# -ge 2 ]] || die "usage: set_setting <key> <value>   (keys and values: qa/qa-api.sh get_settings)"; launch; call set_setting "{\"setting\":\"$(jesc "$1")\",\"value\":\"$(jesc "$2")\"}" ;;
  set_summary_format) [[ $# -ge 1 ]] || die "usage: set_summary_format <format id>"; launch; call set_summary_format "{\"format\":\"$(jesc "$1")\"}" ;;
  reset) call reset ;;
  raw) [[ $# -ge 1 ]] || die "usage: raw <command> [json]"; call "$1" "${2:-\{\}}" ;;
  screenshot)
    [[ $# -ge 1 ]] || die "usage: screenshot <file.png>"
    launch; sleep 1; a exec-out screencap -p >"$1" && echo "qa-api: screenshot -> $1" >&2 ;;
  *) die "unknown command: $cmd" ;;
esac
