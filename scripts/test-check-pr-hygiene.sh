#!/usr/bin/env bash
# Self-test for check-pr-hygiene.sh on a throwaway repository.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q -b main . && git config user.email t@t && git config user.name t
git commit -q --allow-empty -m "base"
base="$(git rev-parse HEAD)"
expect() { # name, expected exit code, command...
  local name="$1" want="$2"; shift 2
  set +e; "$@" >/dev/null 2>&1; local got=$?; set -e
  [ "$got" -eq "$want" ] || { echo "FAIL $name: exit $got, wanted $want"; exit 1; }
  echo "ok   $name"
}
git commit -q --allow-empty -m "clean change"
head="$(git rev-parse HEAD)"
export PR_TITLE="A title" PR_BODY="A clean body"
expect "clean" 0 "$here/check-pr-hygiene.sh" "$base" "$head"
PR_BODY=$'text\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)' expect "footer in body" 1 "$here/check-pr-hygiene.sh" "$base" "$head"
PR_BODY=$'text\nhttps://claude.ai/code/session_abc' expect "session link in body" 1 "$here/check-pr-hygiene.sh" "$base" "$head"
PR_TITLE="x Co-Authored-By: Someone" expect "coauthor in title" 1 "$here/check-pr-hygiene.sh" "$base" "$head"
git commit -q --allow-empty -m $'change\n\nCo-Authored-By: Someone <a@b.c>'
expect "co-authored trailer in a commit" 1 "$here/check-pr-hygiene.sh" "$base" "$(git rev-parse HEAD)"
git reset -q --hard "$head"
git commit -q --allow-empty -m $'change\n\nClaude-Session: https://claude.ai/code/session_x'
expect "session trailer in a commit" 1 "$here/check-pr-hygiene.sh" "$base" "$(git rev-parse HEAD)"
echo "all ok"
