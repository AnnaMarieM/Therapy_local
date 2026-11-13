S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
(
 P=192.168.1.15:5555
 bash run.sh $P poco_cpu_cs_b5_t6 cs cs30.wav -t 6 -bs 5 -bo 5 -ng
 bash run.sh $P poco_cpu_cs_g_t6 cs cs30.wav -t 6 -bs 1 -bo 1 -ng
 BIN=whisper-cli-cl bash run.sh $P poco_cl_cs_g cs cs30.wav -t 6 -bs 1 -bo 1
 BIN=whisper-cli-cl bash run.sh $P poco_cl_en_b5 en en30.wav -t 6 -bs 5 -bo 5
 bash run.sh $P poco_cpu_en_b5_t6 en en30.wav -t 6 -bs 5 -bo 5 -ng
) &
(
 P=192.168.1.11:5555
 bash run.sh $P pixel_cpu_cs_b5_t4 cs cs30.wav -t 4 -bs 5 -bo 5 -ng
 bash run.sh $P pixel_cpu_cs_g_t4 cs cs30.wav -t 4 -bs 1 -bo 1 -ng
 BIN=whisper-cli-vk bash run.sh $P pixel_vk_cs_g cs cs30.wav -t 4 -bs 1 -bo 1
 BIN=whisper-cli-vk bash run.sh $P pixel_vk_en_b5 en en30.wav -t 4 -bs 5 -bo 5
) &
(
 P=192.168.1.7:5555
 bash run.sh $P tab_cpu_cs_g_t4 cs cs30.wav -t 4 -bs 1 -bo 1 -ng
 bash run.sh $P tab_cpu_en_b5_t4 en en30.wav -t 4 -bs 5 -bo 5 -ng
) &
wait
echo BATCHDONE > batch.done
