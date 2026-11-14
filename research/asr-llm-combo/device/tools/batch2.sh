S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S; P=192.168.1.15:5555
bash run2.sh poco_npu_q8_cs_b5 cs cs30.wav -t 6 -bs 5 -bo 5 -dev 1
bash run2.sh poco_npu_q8_cs_g cs cs30.wav -t 6 -bs 1 -bo 1 -dev 1
bash run2.sh poco_npu_q8_en_b5 en en30.wav -t 6 -bs 5 -bo 5 -dev 1
MODEL=ggml-large-v3-turbo-q8_0.bin bash run.sh $P poco_cpu_q8_cs_b5_t6 cs cs30.wav -t 6 -bs 5 -bo 5 -ng
BIN=whisper-cli-cl MODEL=ggml-large-v3-turbo-q8_0.bin bash run.sh $P poco_cl_q8_cs_b5 cs cs30.wav -t 6 -bs 5 -bo 5
echo done > batch2.done
