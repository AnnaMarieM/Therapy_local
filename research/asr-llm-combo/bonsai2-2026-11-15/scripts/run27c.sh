#!/bin/bash
cd /home/user/Therapy_local
export LLAMA_ARG_CACHE_RAM=0
python3 e2e/combo/llm.py --engine script --model bonsai2-27b --tag=-dr --lane 1 --clients daniel-r --llama-bin /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/prism-llama/build/bin --gguf /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/models/Ternary-Bonsai-2-27B-PQ2_0.gguf --ngl 0 --port 8204 --out /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/out > /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/out_27b_daniel-r.log 2>&1
echo ALLDONE > /tmp/claude-0/-home-user-Therapy-local/8e634e4e-629d-5fc3-92c5-08efd4bbb1c5/scratchpad/all.done
