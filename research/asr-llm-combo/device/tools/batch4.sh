S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
M=ggml-large-v3-q8_0.bin
for s in 192.168.1.15:5555 192.168.1.11:5555 192.168.1.7:5555; do adb -s $s push audio/cs30.wav audio/cs_harm30.wav audio/en30.wav /data/local/tmp/asr-bench/ > /dev/null; done
# Poco NPU dir
P=192.168.1.15:5555
adb -s $P shell "mkdir -p /data/local/tmp/asr-bench/hx2 && cp /data/local/tmp/llama-b11869/lib/libggml*.so /data/local/tmp/asr-bench/hx2/"
adb -s $P push build-hx2/bin/whisper-cli build-hx2/bin/libwhisper.so /data/local/tmp/asr-bench/hx2/ > /dev/null
cat > run3.sh <<'EOF'
# NPU run in hx2 dir: run3.sh label lang wav "ENV=.. ENV2=.." extra...
S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
label=$1; lang=$2; wav=$3; envs=$4; shift 4
adb -s 192.168.1.15:5555 shell "cd /data/local/tmp/asr-bench/hx2 && export LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. WHISPER_KV_CPU=1 $envs && /system/bin/time -v ./whisper-cli -m ../ggml-large-v3-q8_0.bin -f ../$wav -l $lang -nt $*" > $S/out/$label.txt 2> $S/out/$label.err
adb -s 192.168.1.15:5555 shell "dumpsys thermalservice | grep -iE 'Thermal Status' | head -2" > $S/out/$label.thermal 2>&1
EOF
(
 export MODEL=$M
 bash run3.sh v3_poco_npu_cs_b5 cs cs30.wav "" -t 6 -bs 5 -bo 5 -dev 1
 bash run3.sh v3_poco_npu_harm_b5 cs cs_harm30.wav "" -t 6 -bs 5 -bo 5 -dev 1
 bash run3.sh v3_poco_npu_cs_g cs cs30.wav "" -t 6 -bs 1 -bo 1 -dev 1
 bash run3.sh v3_poco_npu_en_b5 en en30.wav "" -t 6 -bs 5 -bo 5 -dev 1
 bash run3.sh v3_poco_npuenc_cpudec_cs_b5 cs cs30.wav "WHISPER_DECODER_CPU=1" -t 6 -bs 5 -bo 5 -dev 1
 bash run3.sh v3_poco_npuenc_cpudec_harm_b5 cs cs_harm30.wav "WHISPER_DECODER_CPU=1" -t 6 -bs 5 -bo 5 -dev 1
 bash run.sh $P v3_poco_cpu_cs_b5_t6 cs cs30.wav -t 6 -bs 5 -bo 5 -ng
 bash run.sh $P v3_poco_cpu_harm_b5_t6 cs cs_harm30.wav -t 6 -bs 5 -bo 5 -ng
 bash run.sh $P v3_poco_cpu_cs_g_t6 cs cs30.wav -t 6 -bs 1 -bo 1 -ng
 bash run.sh $P v3_poco_cpu_en_b5_t6 en en30.wav -t 6 -bs 5 -bo 5 -ng
) &
(
 export MODEL=$M
 X=192.168.1.11:5555
 bash run.sh $X v3_pixel_cpu_cs_b5_t4 cs cs30.wav -t 4 -bs 5 -bo 5 -ng
 bash run.sh $X v3_pixel_cpu_harm_b5_t4 cs cs_harm30.wav -t 4 -bs 5 -bo 5 -ng
 bash run.sh $X v3_pixel_cpu_cs_g_t4 cs cs30.wav -t 4 -bs 1 -bo 1 -ng
 bash run.sh $X v3_pixel_cpu_en_b5_t4 en en30.wav -t 4 -bs 5 -bo 5 -ng
) &
(
 export MODEL=$M
 T=192.168.1.7:5555
 bash run.sh $T v3_tab_cpu_cs_b5_t4 cs cs30.wav -t 4 -bs 5 -bo 5 -ng
 bash run.sh $T v3_tab_cpu_harm_b5_t4 cs cs_harm30.wav -t 4 -bs 5 -bo 5 -ng
 bash run.sh $T v3_tab_cpu_cs_g_t4 cs cs30.wav -t 4 -bs 1 -bo 1 -ng
 bash run.sh $T v3_tab_cpu_en_b5_t4 en en30.wav -t 4 -bs 5 -bo 5 -ng
) &
wait
echo done > batch4.done
