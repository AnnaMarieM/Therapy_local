#!/usr/bin/env bash
# The one way to build. Developers and every CI job run this, so every APK comes from the same pinned container.
# Usage: scripts/build.sh [full|fast]
#   full (default)  clean, lint, unit tests, debug APK and test APK, then verifyNoFaultsInRelease over the release, feel and qa APKs
#   fast            clean and the two APKs only
# Output: dist/Therapy-main.apk, dist/Therapy-main-androidTest.apk, dist/Therapy-main.apk.sha256
# Windows: use scripts/build.ps1 (same container, same docker/inside.sh).
set -euo pipefail

mode="${1:-full}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="therapy-builder:$(sha256sum "$root/docker/Dockerfile" | cut -c1-12)"
gradle_home="${GRADLE_USER_HOME_HOST:-$HOME/.gradle-therapy-builder}"

# A developer's local.properties points at their own SDK; hide it so the image SDK is always used.
mask=()
[[ -f "$root/local.properties" ]] && mask=(-v "$(mktemp):/workspace/local.properties:ro")

docker build -q -t "$image" "$root/docker" >/dev/null
mkdir -p "$gradle_home" "$root/dist"

# KVM is not involved: aapt2 on arm64 runs under qemu-user, which is always software emulation. Say so in the log.
arch="$(docker run --rm --entrypoint uname "$image" -m)"
if [[ "$arch" == "x86_64" ]]; then
  echo "[build] container arch $arch: aapt2 runs natively"
else
  echo "[build] container arch $arch: aapt2 is x86_64-only, running it under qemu-user software emulation (no KVM needed, slower resource steps)"
fi

# --user keeps files owned by the caller; HOME/ANDROID_USER_HOME keep tool state out of the image.
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle-home -e ANDROID_USER_HOME=/tmp/home/.android \
  -e SOURCE_DATE_EPOCH="$(git -C "$root" log -1 --format=%ct)" \
  -e GIT_COMMIT="$(git -C "$root" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)" \
  -v "$root":/workspace \
  -v "$gradle_home":/gradle-home \
  "${mask[@]}" \
  -w /workspace \
  "$image" \
  bash docker/inside.sh "$mode"
