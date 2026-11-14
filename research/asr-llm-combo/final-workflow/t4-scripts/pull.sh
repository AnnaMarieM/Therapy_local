#!/bin/bash
D=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/gpu
$D/rsh.sh 'cd /content/fw && tar czf /content/fw_t4.tgz t4' > /dev/null
$D/colab.sh download -s therapy-asr /content/fw_t4.tgz $D/fw_t4.tgz | tail -1
mkdir -p /home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/research/asr-llm-combo/final-workflow
tar xzf $D/fw_t4.tgz -C /home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/research/asr-llm-combo/final-workflow
