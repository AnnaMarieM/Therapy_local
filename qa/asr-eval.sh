#!/usr/bin/env bash
# Runs real recordings through the real Canary model on the phone and prints what they said, with speed.
#   qa/asr-eval.sh [providers] [seconds]      e.g. qa/asr-eval.sh cpu,nnapi 60
# Recordings: 16 kHz mono wavs named cs_*.wav / en_*.wav in qa/eval-audio/ (made from any audio with qa/to-wav.sh). A same-named .txt is the
# reference: the run then reports word error rate (VERBOSE=1 prints every clip, otherwise only the bad ones).
# Models missing on the phone are fetched from Hugging Face to /tmp/asr-models once and pushed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(command -v adb)}"
PROVIDERS="${1:-cpu}"; SECONDS_="${2:-60}"
EXT=/sdcard/Android/data/com.therapylocal.app/files

# Same names, sizes and repo as ModelRegistry.CANARY_1B_V2.
dir="$EXT/models/canary-1b-v2"
while read -r n sz _; do
  "$ADB" shell "[ -s $dir/$n ]" </dev/null 2>/dev/null && continue
  local_f="/tmp/asr-models/canary-1b-v2/$n"
  "$ROOT/qa/fetch.sh" "https://huggingface.co/fussraider/canary-1b-v2-sherpa-onnx-int8/resolve/main/$n" "$local_f" "$sz"
  "$ADB" shell "mkdir -p $dir" </dev/null && "$ADB" push "$local_f" "$dir/$n" </dev/null >/dev/null
done <<'CN'
encoder.int8.onnx 857603131
decoder.int8.onnx 557033012
tokens.txt 208022
CN

"$ADB" shell "rm -rf $EXT/eval; mkdir -p $EXT/eval"
for w in "$ROOT"/qa/eval-audio/*.wav "$ROOT"/qa/eval-audio/*.txt; do "$ADB" push "$w" "$EXT/eval/" >/dev/null; done

SKIP_BUILD="${SKIP_BUILD:-}" "$ROOT/qa/run-device-tests.sh" AsrEvalTest >/dev/null || true
"$ADB" shell am instrument -w -e class com.therapylocal.app.eval.AsrEvalTest \
  -e providers "$PROVIDERS" -e seconds "$SECONDS_" -e verbose "${VERBOSE:-0}" com.therapylocal.app.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | grep -E 'OK \(|FAIL|Error' || true
"$ADB" shell "cat $EXT/eval/results.txt"
