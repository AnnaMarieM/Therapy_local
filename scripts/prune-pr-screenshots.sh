#!/usr/bin/env bash
# AD-15: a pull request closed. Removes its images from the `pr-screenshots` branch and replaces its sticky comment(s) with a short note.
#   GH_TOKEN REPO PR [REMOTE_URL=...]
set -euo pipefail
: "${PR:?}" "${REPO:?}"
BRANCH=pr-screenshots
REMOTE_URL="${REMOTE_URL:-https://x-access-token:${GH_TOKEN:-}@github.com/${REPO}.git}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
git init -q
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git remote add origin "$REMOTE_URL"
for attempt in 1 2 3 4 5 6 7 8; do
  if ! git fetch -q --depth=1 origin "$BRANCH" 2>/dev/null; then echo "no $BRANCH branch: nothing to remove"; break; fi
  git checkout -q -B work FETCH_HEAD
  [[ -d "p$PR" ]] || { echo "no images of #$PR"; break; }
  git rm -rq "p$PR"
  git commit -q -m "p$PR: closed, images removed"
  if git push -q origin "HEAD:refs/heads/$BRANCH" 2>/dev/null; then echo "removed p$PR"; break; fi
  sleep $((RANDOM % 4 + attempt))
done
[[ -n "${GH_TOKEN:-}" && "${DRY_RUN:-}" != 1 ]] || exit 0
gh api --paginate "repos/$REPO/issues/$PR/comments?per_page=100" \
  --jq '.[] | select(.user.login == "github-actions[bot]") | select(.body | startswith("<!-- pr-screenshots:")) | "\(.id) \(.body | capture("^<!-- pr-screenshots:(?<n>[0-9]+) -->").n)"' |
while read -r id n; do
  printf '<!-- pr-screenshots:%s -->\nThe screenshots of this pull request were removed when it closed.\n' "$n" > "$work/note.md"
  gh api -X PATCH "repos/$REPO/issues/comments/$id" -F "body=@$work/note.md" --silent
done
