#!/usr/bin/env bash
# qa/fetch.sh URL OUT [expected-bytes]   download that survives a connection that keeps dropping, and a server that rate-limits.
# Resumes from what is on disk (-C -), treats a connection that is up but crawling (< 16 KB/s for 30 s) as dead and reconnects, retries
# with a growing pause, and stops only when OUT has exactly the expected size. HTTP errors (429, 5xx...) are failures, never content.
set -uo pipefail
URL="$1"; OUT="$2"; WANT="${3:-}"
mkdir -p "$(dirname "$OUT")"

pause() { sleep $(( $1 > 6 ? 45 : 2 << $1 )); }

# The size comes from the server, but only from a successful answer: an error page has a length too.
fails=0
while [[ -z "$WANT" ]]; do
  head="$(curl -sIL --connect-timeout 15 --max-time 30 "$URL" | tr -d '\r')"
  code="$(awk '/^HTTP/{c=$2} END{print c}' <<<"$head")"
  if [[ "$code" =~ ^2 ]]; then
    WANT="$(awk 'tolower($1)=="content-length:"{n=$2} END{print n}' <<<"$head")"
    [[ "$WANT" =~ ^[0-9]+$ && "$WANT" -gt 0 ]] && break
  fi
  # A missing or forbidden file will not appear by waiting; a rate limit (429), a server error or no answer at all will.
  case "$code" in 400|401|403|404|410) echo "fetch: HTTP $code is permanent, not retrying: $URL" >&2; exit 2;; esac
  WANT=""; fails=$((fails+1)); echo "fetch: size lookup failed (HTTP ${code:-none}, attempt $fails): $URL" >&2
  [[ "$fails" -gt 40 ]] && { echo "fetch: giving up on $URL" >&2; exit 1; }
  pause $fails
done

fails=0
while :; do
  have=$(stat -c %s "$OUT" 2>/dev/null || echo 0)
  if [[ "$have" -eq "$WANT" ]]; then exit 0; fi
  if [[ "$have" -gt "$WANT" ]]; then echo "fetch: $OUT is longer than expected, starting over" >&2; rm -f "$OUT"; have=0; fi
  curl -sL --fail -C - --connect-timeout 15 --speed-limit 16384 --speed-time 30 -o "$OUT" "$URL"; rc=$?
  now=$(stat -c %s "$OUT" 2>/dev/null || echo 0)
  if [[ "$now" -gt "$have" ]]; then fails=0; else fails=$((fails+1)); fi
  [[ "$rc" -ne 0 && "$now" -ne "$WANT" ]] && echo "fetch: curl exit $rc at $now/$WANT bytes (failures without progress: $fails)" >&2
  if [[ "$fails" -gt 40 ]]; then echo "fetch: giving up on $URL" >&2; exit 1; fi
  [[ "$now" -ne "$WANT" ]] && pause $fails
done
