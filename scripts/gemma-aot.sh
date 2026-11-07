#!/usr/bin/env bash
# AOT-compile gemma-4-E2B-it_NPU_Uncompiled.litertlm for Qualcomm SM8750 (HTP V79).
# Recipe: litert-torch litert_lm_npu_compiler (official, documented in its Colab notebook):
# https://github.com/google-ai-edge/litert-torch/blob/main/litert_torch/generative/export_hf/experimental/litert_lm_npu_compiler/colab/litert_lm_npu_compiler.ipynb
# usage: gemma-aot.sh <root-dir>   (venv, downloads and output live under it; scripts stay in the repo)
set -euo pipefail
ROOT=${1:?root dir}
REPO=litert-community/gemma-4-E2B-it-litert-lm
REV=3d48132a00cf76de57d926de30f5c5c5ed9b8c0d
FILE=gemma-4-E2B-it_NPU_Uncompiled.litertlm
SRC_SHA=b4f86de05f54bda24e8633ef20375b81b3fcc5dace7c6ac9f4269644267ca4aa
SOC=SM8750
WANT_QAIRT=2.50.0.260828   # must match app runtime com.qualcomm.qti:qnn-runtime:2.50.0
mkdir -p "$ROOT"/{venv,data,out}
python3 -m venv "$ROOT/venv"
PY="$ROOT/venv/bin/python"
"$PY" -m pip install -q --upgrade pip
"$PY" -m pip install -q torch==2.10.0 --index-url https://download.pytorch.org/whl/cpu
"$PY" -m pip install -q litert-torch==0.10.0 ai-edge-litert==2.3.0 litert-lm-builder==0.19.1 \
  huggingface_hub "ai-edge-litert[model-utils]==2.3.0"
# Qualcomm SDK: sdist, downloads the QAIRT zip from softwarecenter.qualcomm.com during build
"$PY" -m pip install -v ai-edge-litert-sdk-qualcomm==2.3.0 2>&1 | grep -E "Downloading SDK|QAIRT|ERROR|Successfully" || true
"$PY" -m pip install -q --no-deps ai-edge-litert-sdk-qualcomm==2.3.0 || true
echo "=== VERSIONS ==="; "$PY" -m pip freeze | grep -i -E "litert|torch|flatbuffers|numpy"
SDK=$("$PY" -I -c "import ai_edge_litert_sdk_qualcomm as q;print(q.path_to_sdk_libs())")
echo "QNN host libs: $SDK"; ls "$SDK" | head -20
QAIRT_STR=$(strings "$SDK/libQnnHtp.so" | grep -m1 -oE "v2\.[0-9]+\.[0-9]+\.[0-9]{6}[0-9]*" || true)
echo "QAIRT string in libQnnHtp.so: $QAIRT_STR"
case "$QAIRT_STR" in v$WANT_QAIRT*) echo "QAIRT version OK: $WANT_QAIRT";; *) echo "QAIRT MISMATCH: wanted $WANT_QAIRT"; exit 3;; esac
echo "app runtime: qnn-runtime 2.50.0, litert-npu-runtime-qualcomm 2.3.0"

# licence gate
"$PY" -I - <<'P'
import json,urllib.request,sys
for r in ["litert-community/gemma-4-E2B-it-litert-lm","google/gemma-4-E2B-it"]:
    d=json.load(urllib.request.urlopen("https://huggingface.co/api/models/"+r))
    lic=d["cardData"].get("license"); print(r,"license:",lic,"gated:",d.get("gated"))
    if lic!="apache-2.0" or d.get("gated"): sys.exit("LICENCE NOT CLEARLY REDISTRIBUTABLE - stop")
P

IN="$ROOT/data/$FILE"
curl -fL --retry 5 -o "$IN" "https://huggingface.co/$REPO/resolve/$REV/$FILE"
GOT=$(sha256sum "$IN" | cut -d' ' -f1)
echo "SOURCE sha256 $GOT size $(stat -c%s "$IN")"
[ "$GOT" = "$SRC_SHA" ] || { echo "source sha mismatch"; exit 4; }
curl -fL -o "$ROOT/out/LICENSE-gemma-4.txt" https://www.apache.org/licenses/LICENSE-2.0.txt
echo "Source: $REPO @ $REV (apache-2.0). Modified: AOT-compiled for Qualcomm $SOC with litert-torch 0.10.0, QAIRT $WANT_QAIRT." > "$ROOT/out/NOTICE.txt"

OUT="$ROOT/out/gemma-4-E2B-it_Qualcomm_${SOC}.litertlm"
free -g; df -h "$ROOT" | tail -1
/usr/bin/time -v "$PY" -I -m litert_torch.generative.export_hf.experimental.litert_lm_npu_compiler.litert_lm_npu_compiler \
  --input_litertlm="$IN" --output_litertlm="$OUT" --backend=qualcomm --soc_model=$SOC 2>&1 | tail -n 80 | grep -v "^$" || { echo "compile failed"; exit 5; }
[ -s "$OUT" ] || { echo "no output"; exit 5; }
"$PY" -I "$(dirname "$0")/gemma-aot-inspect.py" "$OUT" "$IN" || true
echo "OUTPUT sha256 $(sha256sum "$OUT" | cut -d' ' -f1) size $(stat -c%s "$OUT")"
