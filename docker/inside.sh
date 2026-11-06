#!/bin/bash
# Runs inside the builder container. Called by scripts/build.sh and scripts/build.ps1; do not call Gradle for APKs anywhere else.
#   full  clean, lint, unit tests, debug APK, the instrumented-test APK and verifyNoFaultsInRelease (release, feel, qa carry no fault code)
#   fast  clean and the two APKs only
set -euo pipefail
mode="${1:-full}"
case "$mode" in
  full) tasks=(clean lint :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest verifyNoFaultsInRelease) ;;
  fast) tasks=(clean assembleDebug assembleDebugAndroidTest) ;;
  *) echo "unknown mode: $mode (full|fast)" >&2; exit 2 ;;
esac

mkdir -p "$HOME"
./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 "${tasks[@]}"

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Therapy-main.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk dist/Therapy-main-androidTest.apk
(cd dist && sha256sum Therapy-main.apk | tee Therapy-main.apk.sha256)
