#!/bin/bash
# usage: push_model.sh <local file> <remote subdir or ''> ; pushes ONE file, then prints the on-device sha256
. "$(dirname "$0")/common.sh"
f=$1; sub=$2; name=$(basename "$f")
adbq shell "mkdir -p $D/models/$sub"
adbq push "$f" "$D/models/${sub:+$sub/}$name"
adbq shell "sha256sum $D/models/${sub:+$sub/}$name"
