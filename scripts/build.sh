#!/usr/bin/env bash
# The one build script. Developers and CI both run it, so the APK comes from the same pinned environment.
# Usage: scripts/build.sh            lint, unit tests, debug APK -> dist/Therapy-main.apk (+ .sha256)
#        scripts/build.sh --no-test  skip lint and unit tests
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="therapy-builder:$(sha256sum "$root/docker/Dockerfile" | cut -c1-12)"
gradle_home="${GRADLE_USER_HOME_HOST:-$HOME/.gradle-therapy-builder}"

tasks=(clean lint :app:testDebugUnitTest assembleDebug)
[[ "${1:-}" == "--no-test" ]] && tasks=(clean assembleDebug)

# A developer's local.properties points at their own SDK; hide it so the image SDK is always used.
mask=()
[[ -f "$root/local.properties" ]] && mask=(-v /dev/null:/workspace/local.properties:ro)

# The builder is always linux/amd64 (Google ships no arm64 aapt2). Arm hosts run it emulated:
# Docker Desktop on macOS does this out of the box; on Linux/arm64 register QEMU once:
#   docker run --privileged --rm tonistiigi/binfmt --install amd64
if ! docker run --rm --platform linux/amd64 --entrypoint true "$(grep -m1 '^FROM' "$root/docker/Dockerfile" | sed 's/.*--platform=[^ ]* //')" 2>/dev/null; then
  echo "Cannot run linux/amd64 containers here. On Linux/arm64 run: docker run --privileged --rm tonistiigi/binfmt --install amd64" >&2
  exit 1
fi

docker build -q --platform linux/amd64 -t "$image" "$root/docker" >/dev/null
mkdir -p "$gradle_home" "$root/dist"

# --user keeps files owned by the caller; HOME/ANDROID_USER_HOME keep tool state out of the image.
docker run --rm --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle-home -e ANDROID_USER_HOME=/tmp/home/.android \
  -e SOURCE_DATE_EPOCH="$(git -C "$root" log -1 --format=%ct)" \
  -v "$root":/workspace \
  -v "$gradle_home":/gradle-home \
  "${mask[@]}" \
  -w /workspace \
  "$image" \
  bash -c 'mkdir -p "$HOME" && ./gradlew --no-daemon --console=plain "$@"' _ "${tasks[@]}"

cp "$root/app/build/outputs/apk/debug/app-debug.apk" "$root/dist/Therapy-main.apk"
(cd "$root/dist" && sha256sum Therapy-main.apk | tee Therapy-main.apk.sha256)
