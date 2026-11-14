# usage: run.sh <serial> <label> <lang> <wav> <extra args...>   (runs whisper-cli on device, logs RSS/time/thermal)
S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
ser=$1; label=$2; lang=$3; wav=$4; shift 4
mkdir -p $S/out
adb -s $ser shell "cd /data/local/tmp/asr-bench && /system/bin/time -v ./${BIN:-whisper-cli} -m ${MODEL:-ggml-large-v3-turbo-q5_0.bin} -f $wav -l $lang -nt $*" > $S/out/$label.txt 2> $S/out/$label.err
adb -s $ser shell "dumpsys thermalservice | grep -iE 'status|Temperature' | head -8" > $S/out/$label.thermal 2>&1
