#!/usr/bin/env bash
# AOT-compile embeddinggemma-2-740m_NPU_Uncompiled.litertlm for Qualcomm SM8750 (HTP V79).
# Why: the on-device compile of the uncompiled file takes 17 s to 5 min at every load and is not cached (LiteRT-LM 0.18.1), the official SM8750 file fails to invoke.
# Recipe: litert-torch litert_lm_npu_compiler (official, documented in its Colab notebook):
# https://github.com/google-ai-edge/litert-torch/blob/main/litert_torch/generative/export_hf/experimental/litert_lm_npu_compiler/colab/litert_lm_npu_compiler.ipynb
# usage: embed-aot.sh <root-dir>   (venv, downloads and output live under it; scripts stay in the repo)
set -euo pipefail
ROOT=${1:?root dir}
REPO=litert-community/embeddinggemma-2-740m-litert-lm
REV=66011bd4f00a8b36a1d3c4ae7bf908b4891b4881
FILE=embeddinggemma-2-740m_NPU_Uncompiled.litertlm
SRC_SHA=9cb5b084e34dc528f43c947eec81eff020be0dc79ce155f2497c3f2efa92e94f
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
QAIRT_SEEN=$(find "$(dirname "$SDK")/../.." -maxdepth 4 -type d -name '2.*' 2>/dev/null | head -3 | tr '\n' ' ')
echo "QAIRT dirs in SDK package: $QAIRT_SEEN"
strings "$SDK/libQnnHtp.so" | grep -m3 -E "QNN_SDK_VERSION|2\.50|backend_build_id" || true
case "$QAIRT_SEEN" in *"$WANT_QAIRT"*) echo "QAIRT version OK: $WANT_QAIRT";; *) echo "QAIRT MISMATCH: wanted $WANT_QAIRT"; exit 3;; esac
echo "app runtime: qnn-runtime 2.50.0, litert-npu-runtime-qualcomm 2.3.0"

# licence gate
"$PY" -I - <<'P'
import json,urllib.request,sys
for r in ["litert-community/embeddinggemma-2-740m-litert-lm"]:
    d=json.load(urllib.request.urlopen("https://huggingface.co/api/models/"+r))
    lic=d["cardData"].get("license"); print(r,"license:",lic,"gated:",d.get("gated"))
    if lic!="apache-2.0" or d.get("gated"): sys.exit("LICENCE NOT CLEARLY REDISTRIBUTABLE - stop")
P

IN="$ROOT/data/$FILE"
curl -fL --retry 5 -o "$IN" "https://huggingface.co/$REPO/resolve/$REV/$FILE"
GOT=$(sha256sum "$IN" | cut -d' ' -f1)
echo "SOURCE sha256 $GOT size $(stat -c%s "$IN")"
[ "$GOT" = "$SRC_SHA" ] || { echo "source sha mismatch"; exit 4; }
curl -fL -o "$ROOT/out/LICENSE-embeddinggemma.txt" https://www.apache.org/licenses/LICENSE-2.0.txt
echo "Source: $REPO @ $REV (apache-2.0). Modified: AOT-compiled for Qualcomm $SOC with litert-torch 0.10.0, QAIRT $WANT_QAIRT." > "$ROOT/out/NOTICE.txt"

OUT="$ROOT/out/embeddinggemma-2-740m_ours_${SOC}.litertlm"
free -g; df -h "$ROOT" | tail -1
/usr/bin/time -v "$PY" -I -m litert_torch.generative.export_hf.experimental.litert_lm_npu_compiler.litert_lm_npu_compiler \
  --input_litertlm="$IN" --output_litertlm="$OUT" --backend=qualcomm --soc_model=$SOC 2>&1 | tail -n 80 | grep -v "^$" || { echo "compile failed"; exit 5; }
[ -s "$OUT" ] || { echo "no output"; exit 5; }
echo "OUTPUT sha256 $(sha256sum "$OUT" | cut -d' ' -f1) size $(stat -c%s "$OUT")"
