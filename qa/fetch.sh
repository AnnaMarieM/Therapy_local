#!/usr/bin/env bash
# qa/fetch.sh URL OUT [expected-bytes]   download that survives a connection that keeps dropping.
# Resumes from what is on disk (-C -), treats a connection that is up but crawling (< 16 KB/s for 30 s) as dead and reconnects, retries
# forever with a short pause, and only stops when OUT has the expected size (or, without one, when the server says it is complete).
set -uo pipefail
URL="$1"; OUT="$2"; WANT="${3:-}"
mkdir -p "$(dirname "$OUT")"
if [[ -z "$WANT" ]]; then WANT="$(curl -sIL --connect-timeout 15 --max-time 30 "$URL" | tr -d '\r' | awk 'tolower($1)=="content-length:"{n=$2} END{print n}')"; fi
fails=0
while :; do
  have=$(stat -c %s "$OUT" 2>/dev/null || echo 0)
  if [[ -n "$WANT" && "$have" -eq "$WANT" ]]; then exit 0; fi
  if [[ -n "$WANT" && "$have" -gt "$WANT" ]]; then rm -f "$OUT"; have=0; fi
  curl -sL -C - --connect-timeout 15 --speed-limit 16384 --speed-time 30 -o "$OUT" "$URL"; rc=$?
  now=$(stat -c %s "$OUT" 2>/dev/null || echo 0)
  if [[ "$now" -gt "$have" ]]; then fails=0; else fails=$((fails+1)); fi
  [[ -z "$WANT" && $rc -eq 0 ]] && exit 0
  sleep $(( fails > 5 ? 30 : 1 << fails ))
done
