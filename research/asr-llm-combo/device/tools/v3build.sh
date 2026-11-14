S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
# download f16 large-v3 (3.1 GB) and build host tools in parallel
( cd models && curl -sL -O https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin; echo DL > $S/v3dl.done ) &
cmake -S pristine/whisper -B build-host -G Ninja -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON -DGGML_OPENMP=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF > build-host.log 2>&1
cmake --build build-host --target whisper-quantize whisper-cli -j8 >> build-host.log 2>&1
echo HOSTBUILD=$? >> build-host.log
wait
sha256sum models/ggml-large-v3.bin > v3f16.sha
./build-host/bin/whisper-quantize models/ggml-large-v3.bin models/ggml-large-v3-q8_0.bin q8_0 > v3quant.log 2>&1
sha256sum models/ggml-large-v3-q8_0.bin > v3q8.sha
ls -l models/ >> v3quant.log
echo V3DONE > v3.done
