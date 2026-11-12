#!/bin/bash
# Runs inside the builder container. Called by scripts/build.sh and scripts/build.ps1; do not call Gradle for APKs anywhere else.
#   full  clean, lint, unit tests, debug APK, the instrumented-test APK and verifyNoFaultsInRelease (release, feel, qa carry no fault code)
#   fast  clean and the two APKs only
#   qa    clean and the QA APK only (com.therapylocal.app.qa, test data; CI runs this beside `fast`)
#   checks  lint, unit tests and verifyNoFaultsInRelease, no APKs copied out (CI runs this beside `fast`: full = fast + checks)
set -euo pipefail
mode="${1:-full}"
case "$mode" in
  full) tasks=(clean lint :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest verifyNoFaultsInRelease) ;;
  fast) tasks=(clean assembleDebug assembleDebugAndroidTest) ;;
  qa) tasks=(clean :app:assembleQa) ;;
  checks) tasks=(lint :app:testDebugUnitTest verifyNoFaultsInRelease) ;;
  *) echo "unknown mode: $mode (full|fast|qa|checks)" >&2; exit 2 ;;
esac

mkdir -p "$HOME"
./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 "${tasks[@]}"

[[ "$mode" == checks ]] && exit 0

if [[ "$mode" == qa ]]; then
  mkdir -p dist
  cp app/build/outputs/apk/qa/app-qa.apk dist/Therapy-main-qa.apk
  (cd dist && sha256sum Therapy-main-qa.apk | tee Therapy-main-qa.apk.sha256)
  exit 0
fi

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Therapy-main.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk dist/Therapy-main-androidTest.apk
(cd dist && sha256sum Therapy-main.apk | tee Therapy-main.apk.sha256)
