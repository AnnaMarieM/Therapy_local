#!/bin/bash
# usage: colab.sh <colab args...>; mounts repo worktree and scratchpad at same paths
S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad
W=/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora
exec docker run --rm -v $HOME/secrets/tokens/colab-cli:/root/.config/colab-cli -v $S:$S -v $W:$W:ro colab-cli:local "$@" 2>&1 | grep -v -E '^\[colab\] (A new|Run|To silence)'
