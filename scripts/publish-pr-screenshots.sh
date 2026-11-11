#!/usr/bin/env bash
# AD-15: publishes a pull request's screenshots. Pushes the PNGs to the `pr-screenshots` branch (p<PR>/<head sha7>/<screen>/<WxH>.png), keeping
# the latest KEEP commits of this PR, then creates or updates the sticky comment(s) that show them.
#   GH_TOKEN REPO PR HEAD_SHA SRC=<dir with manifest.tsv, results.tsv and <screen>/<WxH>.png> [KEEP=2] [REMOTE_URL=...] [DRY_RUN=1]
# Two jobs pushing at once: the push is rejected for one of them, which fetches again and redoes its commit (up to 8 times).
set -euo pipefail
: "${PR:?}" "${HEAD_SHA:?}" "${SRC:?}" "${REPO:?}"
KEEP="${KEEP:-2}"
BRANCH=pr-screenshots
MAX_COMMITS="${MAX_COMMITS:-400}"   # past this the branch restarts from one commit: history keeps every deleted image, so deleting alone does not shrink it
HERE="$(cd "$(dirname "$0")" && pwd)"
short="${HEAD_SHA:0:7}"
REMOTE_URL="${REMOTE_URL:-https://x-access-token:${GH_TOKEN:-}@github.com/${REPO}.git}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
git init -q
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git remote add origin "$REMOTE_URL"

pushed=""
for attempt in 1 2 3 4 5 6 7 8; do
  if git fetch -q --depth=1 origin "$BRANCH" 2>/dev/null; then
    git checkout -q -B work FETCH_HEAD
  else
    git checkout -q --orphan work
    git rm -rq --cached . 2>/dev/null || true
    find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  fi
  count="$(cat .count 2>/dev/null || echo 0)"
  rm -rf "p$PR/$short"
  mkdir -p "p$PR/$short"
  cp -r "$SRC"/. "p$PR/$short/"
  rm -f "p$PR/$short"/manifest.tsv "p$PR/$short"/results.tsv
  date +%s > "p$PR/$short/.time"
  # Only the latest KEEP commits of this pull request stay.
  # shellcheck disable=SC2012
  for d in $(for t in p"$PR"/*/.time; do echo "$(cat "$t") $(dirname "$t")"; done | sort -rn | tail -n +$((KEEP + 1)) | cut -d' ' -f2); do rm -rf "$d"; done
  count=$((count + 1))
  force=()
  if [[ "$count" -gt "$MAX_COMMITS" ]]; then
    git checkout -q --orphan fresh
    count=1
    force=(--force)
  fi
  echo "$count" > .count
  git add -A
  git commit -q -m "p$PR: $short"
  if [[ "${DRY_RUN:-}" == 1 ]]; then pushed="$(git rev-parse HEAD)"; break; fi
  if git push -q "${force[@]}" origin "HEAD:refs/heads/$BRANCH" 2>/dev/null; then pushed="$(git rev-parse HEAD)"; break; fi
  echo "push rejected (attempt $attempt), fetching again"
  sleep $((RANDOM % 4 + attempt))
done
[[ -n "$pushed" ]] || { echo "could not push to $BRANCH" >&2; exit 1; }
echo "screenshots are at $BRANCH@$pushed"

# The comment(s). The metadata files of the tour are read from SRC, not published.
out="$work/comments"
python3 "$HERE/pr-screenshots-comment.py" --root "$SRC" --repo "$REPO" --pr "$PR" --head "$HEAD_SHA" --branch-sha "$pushed" --out "$out"
[[ "${DRY_RUN:-}" == 1 ]] && { cp -r "$out" "${DRY_OUT:-/tmp/pr-screenshots-comments}"; exit 0; }

existing="$(gh api --paginate "repos/$REPO/issues/$PR/comments?per_page=100" \
  --jq '.[] | select(.user.login == "github-actions[bot]") | select(.body | startswith("<!-- pr-screenshots:")) | "\(.id) \(.body | capture("^<!-- pr-screenshots:(?<n>[0-9]+) -->").n)"')"
declare -A id_of=()
while read -r id n; do [[ -n "${id:-}" ]] && id_of[$n]="$id"; done <<<"$existing"
last=0
for f in "$out"/comment-*.md; do
  n="$(basename "$f" .md)"; n="${n#comment-}"; last="$n"
  if [[ -n "${id_of[$n]:-}" ]]; then
    gh api -X PATCH "repos/$REPO/issues/comments/${id_of[$n]}" -F "body=@$f" --jq .html_url
  else
    gh api -X POST "repos/$REPO/issues/$PR/comments" -F "body=@$f" --jq .html_url
  fi
done
# Parts that are no longer needed (fewer comments than last time).
for n in "${!id_of[@]}"; do
  [[ "$n" -gt "$last" ]] && gh api -X DELETE "repos/$REPO/issues/comments/${id_of[$n]}"
done
echo "branch_sha=$pushed" >> "${GITHUB_OUTPUT:-/dev/null}"
