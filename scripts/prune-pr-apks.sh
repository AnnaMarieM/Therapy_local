#!/usr/bin/env bash
# Deletes the per-pull-request APK releases (`pr-<number>`, published by the release-pr job of ci.yml), so no stale APK stays downloadable.
#   GH_TOKEN REPO [PR]
# With PR: that pull request's release (it just closed). Without: a sweep that deletes every pr-<number> release whose pull request is
# closed or merged, for a close event that was missed or a CI run that published after the close.
set -euo pipefail
: "${REPO:?}"

delete() {
  if gh release view "pr-$1" --repo "$REPO" >/dev/null 2>&1; then
    gh release delete "pr-$1" --repo "$REPO" --cleanup-tag --yes
    echo "deleted pr-$1"
  else
    echo "no release pr-$1"
  fi
}

if [[ -n "${PR:-}" ]]; then
  delete "$PR"
  exit 0
fi

gh release list --repo "$REPO" --limit 1000 --json tagName -q '.[].tagName' | { grep -E '^pr-[0-9]+$' || true; } | while read -r tag; do
  n="${tag#pr-}"
  # Only a pull request known to be closed or merged loses its release; an API hiccup leaves it for the next sweep.
  state="$(gh pr view "$n" --repo "$REPO" --json state -q .state 2>/dev/null || echo UNKNOWN)"
  case "$state" in
    CLOSED|MERGED) delete "$n" ;;
    *) echo "pr-$n kept ($state)" ;;
  esac
done
