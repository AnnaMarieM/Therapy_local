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
  -v "$root":/workspace \
  -v "$gradle_home":/gradle-home \
  "${mask[@]}" \
  -w /workspace \
  "$image" \
  bash -c 'mkdir -p "$HOME" && ./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 "$@"' _ "${tasks[@]}"

cp "$root/app/build/outputs/apk/debug/app-debug.apk" "$root/dist/Therapy-main.apk"
(cd "$root/dist" && sha256sum Therapy-main.apk | tee Therapy-main.apk.sha256)
