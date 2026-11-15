#!/bin/bash
cd /home/user/Therapy_local
run(){ # label bindir gguf port
  python3 e2e/combo/llm.py --engine script --model $1 --lane 1 --llama-bin $2 --gguf $3 --ngl 0 --port $4 --out /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/out > /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/out_$1.log 2>&1
}
mkdir -p /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/out
run bonsai2-9b /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/prism-llama/build/bin /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/models/Ternary-Bonsai-2-9B-PQ2_0.gguf 8201
run gemma4-e4b /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/stock/llama-b11960 /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/models/gemma-4-E4B-it-Q4_K_M.gguf 8202
run gemma4-e2b /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/stock/llama-b11960 /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/models/gemma-4-E2B-it-Q4_K_M.gguf 8203
while [ ! -f /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/dl.done ]; do sleep 10; done
run bonsai2-27b /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/prism-llama/build/bin /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/models/Ternary-Bonsai-2-27B-PQ2_0.gguf 8204
echo ALLDONE > /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/all.done
