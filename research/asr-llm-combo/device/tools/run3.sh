# NPU run in hx2 dir: run3.sh label lang wav "ENV=.. ENV2=.." extra...
S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
label=$1; lang=$2; wav=$3; envs=$4; shift 4
adb -s 192.168.1.15:5555 shell "cd /data/local/tmp/asr-bench/hx2 && export LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. WHISPER_KV_CPU=1 $envs && /system/bin/time -v ./whisper-cli -m ../ggml-large-v3-q8_0.bin -f ../$wav -l $lang -nt $*" > $S/out/$label.txt 2> $S/out/$label.err
adb -s 192.168.1.15:5555 shell "dumpsys thermalservice | grep -iE 'Thermal Status' | head -2" > $S/out/$label.thermal 2>&1
