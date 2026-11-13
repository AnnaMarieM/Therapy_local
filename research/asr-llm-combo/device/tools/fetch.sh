set -x
cd /tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device/dl
curl -sL -o ndk.zip https://dl.google.com/android/repository/android-ndk-r27c-linux.zip && unzip -q ndk.zip -d /tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device/ndk
curl -sL -o ff.tar.xz https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz && tar -xf ff.tar.xz -C /tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device/ff
cd /tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device/models
curl -sL -O https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin
curl -sL -O https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin
echo DONE
