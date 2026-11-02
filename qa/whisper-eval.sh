#!/usr/bin/env bash
# Runs real recordings through the real Whisper models on the phone and prints what they said, with speed.
#   qa/whisper-eval.sh [sizes] [providers] [seconds]      e.g. qa/whisper-eval.sh small,base cpu,nnapi 60
# Recordings: 16 kHz mono wavs named cs_*.wav / en_*.wav in qa/eval-audio/ (made from any audio with qa/to-wav.sh). A same-named .txt is the
# reference: the run then reports word error rate (VERBOSE=1 prints every clip, otherwise only the bad ones).
# Models missing on the phone are fetched from Hugging Face to /tmp/whisper-models once and pushed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(command -v adb)}"
SIZES="${1:-small}"; PROVIDERS="${2:-cpu}"; SECONDS_="${3:-60}"
EXT=/sdcard/Android/data/com.therapylocal.app/files

# Same names, sizes and repos as ModelRegistry; Czech uses the multilingual build, English the .en one.
for size in ${SIZES//,/ }; do
  if [[ "$size" == parakeet ]]; then
    dir="$EXT/models/parakeet-tdt-0.6b-v3"
    while read -r n sz _; do
      "$ADB" shell "[ -s $dir/$n ]" </dev/null 2>/dev/null && continue
      local_f="/tmp/whisper-models/parakeet-v3/$n"
      "$ROOT/qa/fetch.sh" "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/$n" "$local_f" "$sz"
      "$ADB" shell "mkdir -p $dir" </dev/null && "$ADB" push "$local_f" "$dir/$n" </dev/null >/dev/null
    done <<'PK'
encoder.int8.onnx 652184281
decoder.int8.onnx 11845275
joiner.int8.onnx 6355277
tokens.txt 93939
PK
    continue
  fi
  variants=("$size"); case "$size" in tiny|base|small) variants+=("$size.en");; esac   # the big builds serve English too
  for variant in "${variants[@]}"; do
    dir="$EXT/models/whisper-$variant"
    for f in encoder.int8.onnx decoder.int8.onnx tokens.txt; do
      "$ADB" shell "[ -s $dir/$variant-$f ]" </dev/null 2>/dev/null && continue
      local_f="/tmp/whisper-models/$variant/$variant-$f"
      if [[ ! -s "$local_f" || ! -e "$local_f.ok" ]]; then
        mkdir -p "$(dirname "$local_f")"
        "$ROOT/qa/fetch.sh" "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$variant/resolve/main/$variant-$f" "$local_f" && touch "$local_f.ok"
      fi
      "$ADB" shell "mkdir -p $dir" </dev/null && "$ADB" push "$local_f" "$dir/$variant-$f" </dev/null >/dev/null
    done
  done
done

"$ADB" shell "rm -rf $EXT/eval; mkdir -p $EXT/eval"
for w in "$ROOT"/qa/eval-audio/*.wav "$ROOT"/qa/eval-audio/*.txt; do "$ADB" push "$w" "$EXT/eval/" >/dev/null; done

SKIP_BUILD="${SKIP_BUILD:-}" "$ROOT/qa/run-device-tests.sh" WhisperEvalTest >/dev/null || true
"$ADB" shell am instrument -w -e class com.therapylocal.app.eval.WhisperEvalTest \
  -e sizes "$SIZES" -e providers "$PROVIDERS" -e seconds "$SECONDS_" -e verbose "${VERBOSE:-0}" com.therapylocal.app.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | grep -E 'OK \(|FAIL|Error' || true
"$ADB" shell "cat $EXT/eval/results.txt"
