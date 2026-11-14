S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
( T=192.168.1.7:5555
  bash run.sh $T tab_cpu_cs_b5_t4_rerun cs cs30.wav -t 4 -bs 5 -bo 5 -ng
  MODEL=ggml-large-v3-turbo-q8_0.bin bash run.sh $T tab_cpu_q8_cs_b5_t4 cs cs30.wav -t 4 -bs 5 -bo 5 -ng
  bash run.sh $T tab_cpu_cs_g_t4_rerun cs cs30.wav -t 4 -bs 1 -bo 1 -ng ) &
( X=192.168.1.11:5555
  MODEL=ggml-large-v3-turbo-q8_0.bin bash run.sh $X pixel_cpu_q8_cs_b5_t4 cs cs30.wav -t 4 -bs 5 -bo 5 -ng
  BIN=whisper-cli-vk MODEL=ggml-large-v3-turbo-q8_0.bin bash run.sh $X pixel_vk_q8_cs_b5 cs cs30.wav -t 4 -bs 5 -bo 5 ) &
wait; echo done > batch3.done
