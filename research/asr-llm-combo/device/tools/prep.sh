S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
FF=$S/ff/ffmpeg-7.0.2-arm64-static/ffmpeg
W=/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora
$FF -loglevel error -y -i $W/e2e/corpus/human/tomas-b/s01.m4a -t 30 -ar 16000 -ac 1 audio/cs30.wav
$FF -loglevel error -y -i $W/e2e/corpus/human/daniel-r/s01.m4a -t 30 -ar 16000 -ac 1 audio/en30.wav
ls -la audio
curl -sL -o whisper.tar.gz https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/heads/master && mkdir -p src && tar -xzf whisper.tar.gz -C src --strip-components=1
ls src | head -30
ls ndk/android-ndk-r27c/shader-tools/*/
find ndk -name "vulkan.hpp" | head -2
ls ndk/android-ndk-r27c/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan | head -3
