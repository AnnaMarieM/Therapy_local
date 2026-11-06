#!/usr/bin/env bash
# Architecture decisions are immutable once merged (AGENTS.md, docs/AD/). Fails if a docs/AD/AD-*.md file that exists at BASE
# was modified, deleted, renamed or had its mode changed between BASE and HEAD. New decision files are fine.
# Usage: scripts/check-ad-immutable.sh <base-commit> [head-commit]
set -euo pipefail

base="${1:?usage: check-ad-immutable.sh <base-commit> [head-commit]}"
head="${2:-HEAD}"

# A first push or a base that is not in the history (all zeros) has nothing to protect.
if [[ "$base" =~ ^0+$ ]] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
  echo "[ad-check] base $base not available, nothing to compare"
  exit 0
fi

# --no-renames lists a rename as delete + add, so the old path shows up as D. Only paths present at base matter.
changed="$(git diff --no-renames --name-status "$base" "$head" -- 'docs/AD/AD-*.md' | awk '$1 != "A" { print $1 "\t" $2 }')"

if [[ -z "$changed" ]]; then
  echo "[ad-check] ok: no existing decision in docs/AD/ was changed ($base..$head)"
  exit 0
fi

echo "[ad-check] FAILED: these accepted decisions were changed. Decisions are immutable once merged."
echo "$changed" | while IFS=$'\t' read -r status path; do
  case "$status" in
    M) what="modified" ;;
    D) what="deleted or renamed" ;;
    T) what="type changed" ;;
    *) what="changed ($status)" ;;
  esac
  echo "[ad-check]   $path: $what"
  [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::error file=$path::$path is an accepted decision and must not be $what. Record a new AD that supersedes it."
done
echo "[ad-check] To change a decision, add a new docs/AD/AD-xx.md with 'Supersedes | AD-yy' and update docs/AD/README.md."
exit 1
