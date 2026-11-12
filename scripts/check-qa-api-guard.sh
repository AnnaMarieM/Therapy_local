#!/usr/bin/env bash
# The QA control API exists only in the QA build (AD-16, docs/QA_API.md). Source-level checks that need no Android build:
#   1. the wrapper qa/qa-api.sh refuses every package that does not end in .qa, before it runs adb (a stub adb must never be called);
#   2. no source set other than qa and testQa mentions the qaapi package, QaSettings or the command names;
#   3. only the qa manifest declares the provider.
# The APK-level check is the Gradle task verifyNoQaApiInRelease; the per-command checks are the JVM tests in app/src/testQa.
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
bad() { echo "[qa-guard] FAIL: $*" >&2; fail=1; }

# 1. the wrapper
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
printf '#!/bin/sh\necho called >> "%s/adb-called"\nexit 0\n' "$tmp" > "$tmp/adb"; chmod +x "$tmp/adb"
for pkg in com.therapylocal.app com.therapylocal.app.debug com.therapylocal.app.feel com.example.other qa com.therapylocal.app.qa.evil .qa.x; do
  for how in -p env; do
    rm -f "$tmp/adb-called"
    if [[ $how = -p ]]; then out=$(ADB="$tmp/adb" qa/qa-api.sh -p "$pkg" --no-launch status 2>&1); else out=$(ADB="$tmp/adb" QA_PACKAGE="$pkg" qa/qa-api.sh --no-launch status 2>&1); fi
    rc=$?
    [[ $rc -ne 0 ]] || bad "wrapper accepted package '$pkg' ($how)"
    [[ -e "$tmp/adb-called" ]] && bad "wrapper called adb for package '$pkg' ($how)"
    [[ $out == *refusing* ]] || bad "wrapper gave no refusal for '$pkg' ($how): $out"
  done
done
# a .qa package gets past the check (the stub adb is then reached)
rm -f "$tmp/adb-called"
ADB="$tmp/adb" qa/qa-api.sh -p com.therapylocal.app.qa --no-launch status >/dev/null 2>&1
[[ -e "$tmp/adb-called" ]] || bad "wrapper did not accept com.therapylocal.app.qa"

# 2. nothing outside the qa source sets knows the API
pattern='qaapi|QaApi|QaSettings|QaSetting\b|QaGuard|QaAppBackend|get_settings|set_setting|set_summary_format|not_qa_build'
for d in app/src/*/; do
  case "$d" in app/src/qa/|app/src/testQa/) continue ;; esac
  if grep -rEn "$pattern" "$d" >/dev/null 2>&1; then bad "$d mentions the QA API: $(grep -rEln "$pattern" "$d" | head -3 | tr '\n' ' ')"; fi
done
[[ -d app/src/main && -d app/src/debug ]] || bad "expected source sets are missing"

# 3. the provider is declared in the qa manifest only
for m in app/src/*/AndroidManifest.xml; do
  has=0; grep -q 'QaApiProvider' "$m" && has=1
  if [[ $m = app/src/qa/AndroidManifest.xml ]]; then [[ $has = 1 ]] || bad "$m does not declare the provider"; else [[ $has = 0 ]] || bad "$m declares the QA provider"; fi
done

[[ $fail = 0 ]] && echo "[qa-guard] ok: wrapper refuses non-.qa packages, no QA API outside the qa source set" || exit 1
