#!/bin/bash
# Runs inside the builder container. Called by scripts/build.sh and scripts/build.ps1; do not call Gradle for APKs anywhere else.
#   full  clean, lint, unit tests, debug APK, the instrumented-test APK and verifyNoFaultsInRelease and verifyNoQaApiInRelease (release, feel, qa carry no fault code)
#   fast  clean and the two APKs only
#   checks  lint, unit tests and verifyNoFaultsInRelease, no APKs copied out (CI runs this beside `fast`: full = fast + checks)
set -euo pipefail
mode="${1:-full}"
case "$mode" in
  full) tasks=(clean lint :app:testDebugUnitTest :app:testQaUnitTest assembleDebug assembleDebugAndroidTest verifyNoFaultsInRelease verifyNoQaApiInRelease) ;;
  fast) tasks=(clean assembleDebug assembleDebugAndroidTest) ;;
  checks) tasks=(lint :app:testDebugUnitTest :app:testQaUnitTest verifyNoFaultsInRelease verifyNoQaApiInRelease) ;;
  *) echo "unknown mode: $mode (full|fast|checks)" >&2; exit 2 ;;
esac

mkdir -p "$HOME"
./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 "${tasks[@]}"

[[ "$mode" == checks ]] && exit 0

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Therapy-main.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk dist/Therapy-main-androidTest.apk
(cd dist && sha256sum Therapy-main.apk | tee Therapy-main.apk.sha256)
