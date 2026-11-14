#!/bin/bash
# stage2.sh <pipeline-id> <e2b|e4b> <turns-file|script> <en|src> <pivot 0|1> <port> [lanes]
P=$1; M=$2; TF=$3; TL=$4; PV=$5; PORT=$6; LANES=${7:-"1 2 3 4"}
B=/content/llama.cpp/build/bin
export LD_LIBRARY_PATH=$B:$LD_LIBRARY_PATH
UP=E2B
[ $M = e4b ] && UP=E4B
G=$(python3 /content/w/modelpath.py $UP)
if [ $PV = 1 ]; then PVS=true; else PVS=false; fi
OPTS='{"translate_back":true,"pivot":'$PVS'}'
for L in $LANES; do
  if [ $TF = script ]; then EX="--engine script"; else EX="--engine $P --turns-file $TF --turns-lang $TL"; fi
  python3 /content/w/repo/e2e/combo/llm.py $EX --model gemma4-$M --tag +roles --lane $L ${CL:+--clients $CL} --llama-bin $B --gguf $G --port $PORT --opts "$OPTS" --out /content/fw/t4/$P/stage2_$M/lane$L
done
echo STAGE2_DONE $P $M
