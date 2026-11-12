#!/usr/bin/env bash
# No AI attribution in pull requests (AGENTS.md): no "Co-authored-by" trailer, no "Claude-Session" trailer, no "Generated with Claude Code"
# footer and no claude.ai session link, in the PR title, the PR body or any commit message of the PR.
# Usage: scripts/check-pr-hygiene.sh <base-ref-or-commit> <head-commit>   (title and body come from PR_TITLE and PR_BODY)
set -euo pipefail

base="${1:?usage: check-pr-hygiene.sh <base> <head>}"
head="${2:?usage: check-pr-hygiene.sh <base> <head>}"

pattern='(^|[[:space:]])co-authored-by:|^claude-session:|generated with[^a-z]*(\[)?claude code|claude\.ai/code/session'
bad=0

hit() {
  if printf '%s\n' "$2" | grep -n -i -E "$pattern" >/dev/null; then
    echo "::error::$1 contains AI attribution (Co-authored-by, Claude-Session, 'Generated with Claude Code' or a claude.ai session link). Remove it."
    printf '%s\n' "$2" | grep -n -i -E "$pattern" | sed 's/^/    /'
    bad=1
  fi
}

hit "The PR title" "${PR_TITLE:-}"
hit "The PR description" "${PR_BODY:-}"

# Commits of the PR itself: reachable from head, not from the base. Merges of the base into the PR add no message of their own.
while read -r sha; do
  hit "Commit ${sha:0:7}" "$(git log -1 --format=%B "$sha")"
done < <(git rev-list --no-merges "$base..$head")

if [ "$bad" -ne 0 ]; then
  echo "pr-hygiene: failed. No PR may carry AI attribution; amend the commits (git rebase / filter-branch) and edit the PR text."
  exit 1
fi
echo "pr-hygiene: clean"
